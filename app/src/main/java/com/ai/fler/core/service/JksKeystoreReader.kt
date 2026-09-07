package com.ai.fler.core.service

import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

/**
 * JKS（Java KeyStore）密钥库解析器（纯 Kotlin，不依赖平台 JKS 实现）。
 *
 * Android 平台不提供 JKS KeyStore（KeyStore.getInstance("JKS") 抛
 * KeyStoreException），导入的 .jks 无法经平台加载。本类按 OpenJDK
 * sun.security.provider.JavaKeyStore 的字节格式自行解析容器，并用 Sun 专有
 * 私钥保护算法（OID 1.3.6.1.4.1.42.2.17.1.1）解出 PKCS#8 私钥，使重打包
 * 签名直接支持 .jks，无需先转换 PKCS12。
 *
 * 容器格式（全大端，DataOutput 语义）：
 *   magic(4)=0xFEEDFEED  version(4)=1|2  entryCount(4)
 *   每条目 tag(4)：1=私钥 2=受信证书
 *     alias（writeUTF：2 字节长度 + modified UTF-8）
 *     date(8, millis)
 *     tag=1：keyLen(4) + EncryptedPrivateKeyInfo(DER)；certCount(4)；
 *            每证书 [version 2 多 certType(UTF)] + len(4) + X.509 DER
 *     tag=2：[version 2 多 certType(UTF)] + len(4) + DER
 *   尾部 20 字节：SHA1(密码UTF-16BE ‖ "Mighty Aphrodite" ‖ 前文全部字节)
 *
 * 私钥保护算法（encryptedData = salt(20) ‖ Y ‖ SHA1(pw‖P)(20)）：
 *   Y = P ⊕ K，密钥流 K 按 20 字节块生成：
 *     d0 = SHA1(pw ‖ salt)，di = SHA1(pw ‖ d(i-1))，逐块拼接（末块截断）
 *   P 为 PKCS#8 DER；解出后以 SHA1(pw ‖ P) 与尾部 20 字节比对校验密码。
 */
object JksKeystoreReader {

    /** JKS 容器魔数（大端）。 */
    private const val MAGIC = 0xFEEDFEEDL

    /** JCEKS 容器魔数（区分报错用）。 */
    private const val JCEKS_MAGIC = 0xCECECECEL

    /** Sun 专有私钥保护算法 OID（EncryptedPrivateKeyInfo 内）。 */
    private const val KEY_PROTECTOR_OID = "1.3.6.1.4.1.42.2.17.1.1"

    /** 容器完整性摘要的白化串（JavaKeyStore.getPreKeyedHash）。 */
    private val DIGEST_WHITENER = "Mighty Aphrodite".toByteArray(Charsets.UTF_8)

    private const val SALT_LEN = 20
    private const val SHA1_LEN = 20

    /** PKCS#8 私钥算法 OID → KeyFactory 算法名。 */
    private val KEY_ALG_OIDS = mapOf(
        "1.2.840.113549.1.1.1" to "RSA", // rsaEncryption
        "1.2.840.10045.2.1" to "EC",     // id-ecPublicKey
        "1.2.840.10040.4.1" to "DSA",    // id-dsa
    )

    /** 解析出的私钥条目（证书链为 X.509 DER，叶子在前）。 */
    class KeyEntry(
        val alias: String,
        val protectedKey: ByteArray,            // EncryptedPrivateKeyInfo DER
        val certificateChain: List<ByteArray>,
    )

    /** 加载结果：命中的别名 + 私钥 + 证书链。 */
    class KeyMaterial(
        val alias: String,
        val privateKey: PrivateKey,
        val certificateChain: List<X509Certificate>,
    )

    /** 是否 JKS 容器（魔数判定）。 */
    fun isJks(data: ByteArray): Boolean =
        data.size >= 4 && readU32be(data, 0) == MAGIC

    /** 是否 JCEKS 容器（魔数判定，用于明确报错提示转换）。 */
    fun isJceks(data: ByteArray): Boolean =
        data.size >= 4 && readU32be(data, 0) == JCEKS_MAGIC

    /**
     * 解析容器并校验存储级完整性。
     *
     * @return 私钥条目（受信证书条目不参与签名，跳过）
     * @throws IllegalStateException 密钥库密码错误 / 格式损坏
     */
    fun parseKeyEntries(data: ByteArray, storePassword: String): List<KeyEntry> {
        val c = Cursor(data)
        val magic = c.u32()
        if (magic != MAGIC) throw IllegalStateException("不是 JKS 密钥库（magic=0x${magic.toString(16)}）")
        val version = c.u32()
        if (version != 1L && version != 2L) throw IllegalStateException("JKS 版本不支持: $version")
        val count = c.u32().toInt()
        if (count < 0) throw IllegalStateException("JKS 条目数异常: $count")

        val keyEntries = ArrayList<KeyEntry>(minOf(count, 16))
        repeat(count) {
            val tag = c.u32().toInt()
            val alias = c.utf()
            c.skip(8) // 条目创建时间
            when (tag) {
                1 -> {
                    val protectedKey = c.blob()
                    val certCount = c.u32().toInt()
                    if (certCount < 0) throw IllegalStateException("JKS 证书链数量异常")
                    val chain = (0 until certCount).map {
                        if (version == 2L) c.utf() // certType（实际恒为 X.509）
                        c.blob()
                    }
                    keyEntries += KeyEntry(alias, protectedKey, chain)
                }
                2 -> {
                    if (version == 2L) c.utf()
                    c.blob()
                }
                else -> throw IllegalStateException("JKS 条目类型异常: $tag")
            }
        }

        // 存储级完整性：SHA1(密码 ‖ 白化串 ‖ 条目区全部字节) 对比尾部 20 字节
        val entriesEnd = c.pos
        val md = sha1()
        md.update(passwordBytes(storePassword))
        md.update(DIGEST_WHITENER)
        md.update(data, 0, entriesEnd)
        if (!MessageDigest.isEqual(md.digest(), c.bytes(SHA1_LEN))) {
            throw IllegalStateException("JKS 完整性校验失败：密钥库密码错误或文件损坏")
        }
        return keyEntries
    }

    /**
     * 解密私钥条目（Sun 专有保护算法），返回 PKCS#8 DER。
     *
     * @throws IllegalStateException 密钥密码错误 / 格式损坏
     */
    fun decryptPrivateKey(protectedKey: ByteArray, keyPassword: String): ByteArray {
        val blob = unwrapEncryptedPrivateKeyInfo(protectedKey)
        if (blob.size <= SALT_LEN + SHA1_LEN) {
            throw IllegalStateException("JKS 私钥数据过短（文件损坏）")
        }
        val encrLen = blob.size - SALT_LEN - SHA1_LEN
        val pw = passwordBytes(keyPassword)
        val md = sha1()

        // 密钥流 XOR 解密：d0=SHA1(pw‖salt)，di=SHA1(pw‖d(i-1))
        val plain = ByteArray(encrLen)
        var digest = blob.copyOfRange(0, SALT_LEN)
        var filled = 0
        while (filled < encrLen) {
            md.update(pw)
            md.update(digest)
            digest = md.digest()
            val n = minOf(SHA1_LEN, encrLen - filled)
            for (i in 0 until n) {
                plain[filled + i] =
                    (blob[SALT_LEN + filled + i].toInt() xor digest[i].toInt()).toByte()
            }
            filled += n
        }

        // 密码校验：SHA1(pw ‖ P) 与尾部 20 字节比对
        md.update(pw)
        md.update(plain)
        if (!MessageDigest.isEqual(md.digest(), blob.copyOfRange(blob.size - SHA1_LEN, blob.size))) {
            throw IllegalStateException("JKS 密钥密码错误（私钥解密校验失败）")
        }
        return plain
    }

    /**
     * 从 JKS 数据加载签名密钥。
     *
     * @param alias 目标别名（精确 → 忽略大小写；JKS 常规存储为小写）；
     *              空 = 依次尝试私钥条目，首个密钥密码匹配者
     * @throws IllegalStateException 无私钥条目 / 别名不存在 / 密码错误 / 格式损坏
     */
    fun loadPrivateKey(
        data: ByteArray,
        storePassword: String,
        alias: String,
        keyPassword: String,
    ): KeyMaterial {
        val entries = parseKeyEntries(data, storePassword)
        if (entries.isEmpty()) throw IllegalStateException("JKS 中没有私钥条目")

        val candidates: List<KeyEntry> = if (alias.isBlank()) {
            entries
        } else {
            listOf(
                entries.firstOrNull { it.alias == alias }
                    ?: entries.firstOrNull { it.alias.equals(alias, ignoreCase = true) }
                    ?: throw IllegalStateException(
                        "JKS 中未找到别名 \"$alias\"（可用: ${entries.joinToString { it.alias }}）"
                    )
            )
        }

        var lastError: IllegalStateException? = null
        for (entry in candidates) {
            val pkcs8 = try {
                decryptPrivateKey(entry.protectedKey, keyPassword)
            } catch (e: IllegalStateException) {
                lastError = e
                null
            } ?: continue

            if (entry.certificateChain.isEmpty()) {
                throw IllegalStateException("别名 \"${entry.alias}\" 证书链为空")
            }
            val chain = entry.certificateChain.map(::parseCertificate)
            return KeyMaterial(entry.alias, privateKeyFromPkcs8(pkcs8), chain)
        }
        throw lastError ?: IllegalStateException("JKS 私钥加载失败")
    }

    // ==================================================================
    // 私钥构造
    // ==================================================================

    /** 拆 EncryptedPrivateKeyInfo：校验 Sun 专有 OID，返回 encryptedData。 */
    private fun unwrapEncryptedPrivateKeyInfo(der: ByteArray): ByteArray {
        val outer = derElement(der, 0)
        if (outer.tag != 0x30) {
            throw IllegalStateException("JKS 私钥数据不是 EncryptedPrivateKeyInfo（DER SEQUENCE）")
        }
        val algId = derElement(der, outer.contentStart)
        if (algId.tag != 0x30) throw IllegalStateException("JKS 私钥算法标识损坏")
        val oidEl = derElement(der, algId.contentStart)
        if (oidEl.tag != 0x06) throw IllegalStateException("JKS 私钥算法 OID 缺失")
        val oid = oidToString(der, oidEl)
        if (oid != KEY_PROTECTOR_OID) {
            throw IllegalStateException(
                "JKS 私钥保护算法不支持（$oid；标准 JKS 应为 Sun 专有算法，" +
                    "疑似 JCEKS/其他格式，请转换为 PKCS12）"
            )
        }
        val octet = derElement(der, algId.contentEnd)
        if (octet.tag != 0x04) throw IllegalStateException("JKS 私钥密文（OCTET STRING）缺失")
        return der.copyOfRange(octet.contentStart, octet.contentEnd)
    }

    /** PKCS#8 DER → PrivateKey（按 PrivateKeyInfo 算法 OID 选择 KeyFactory）。 */
    private fun privateKeyFromPkcs8(pkcs8: ByteArray): PrivateKey {
        val oid = try {
            val outer = derElement(pkcs8, 0)
            if (outer.tag != 0x30) throw IllegalStateException("顶层不是 SEQUENCE")
            val version = derElement(pkcs8, outer.contentStart)
            if (version.tag != 0x02) throw IllegalStateException("version 缺失")
            val algId = derElement(pkcs8, version.contentEnd)
            if (algId.tag != 0x30) throw IllegalStateException("算法标识缺失")
            val oidEl = derElement(pkcs8, algId.contentStart)
            if (oidEl.tag != 0x06) throw IllegalStateException("算法 OID 缺失")
            oidToString(pkcs8, oidEl)
        } catch (e: IllegalStateException) {
            throw IllegalStateException("JKS 私钥不是有效 PKCS#8: ${e.message}", e)
        }
        val alg = KEY_ALG_OIDS[oid]
            ?: throw IllegalStateException("不支持的私钥算法 OID $oid（支持 RSA/EC/DSA）")
        return try {
            KeyFactory.getInstance(alg).generatePrivate(PKCS8EncodedKeySpec(pkcs8))
        } catch (e: Exception) {
            throw IllegalStateException("JKS 私钥构造失败（$alg）: ${e.message}", e)
        }
    }

    private fun parseCertificate(der: ByteArray): X509Certificate = try {
        ByteArrayInputStream(der).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
    } catch (e: Exception) {
        throw IllegalStateException("JKS 证书解析失败: ${e.message}", e)
    }

    // ==================================================================
    // 基础编解码
    // ==================================================================

    /** 密码 → 字节：逐 UTF-16 单元取高低字节（与 Sun convertToBytes 一致）。 */
    private fun passwordBytes(password: String): ByteArray {
        val out = ByteArray(password.length * 2)
        var j = 0
        for (c in password) {
            out[j++] = (c.code shr 8).toByte()
            out[j++] = c.code.toByte()
        }
        return out
    }

    private fun sha1(): MessageDigest = MessageDigest.getInstance("SHA-1")

    private fun readU32be(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xff) shl 24) or
            ((b[off + 1].toLong() and 0xff) shl 16) or
            ((b[off + 2].toLong() and 0xff) shl 8) or
            (b[off + 3].toLong() and 0xff)

    /** 大端游标读取器（DataInputStream 语义子集，越界即抛格式异常）。 */
    private class Cursor(private val data: ByteArray) {
        var pos = 0
            private set

        fun u32(): Long {
            requireRemaining(4)
            val v = readU32be(data, pos)
            pos += 4
            return v
        }

        fun utf(): String {
            requireRemaining(2)
            val len = ((data[pos].toInt() and 0xff) shl 8) or (data[pos + 1].toInt() and 0xff)
            pos += 2
            val raw = bytes(len)
            return decodeModifiedUtf8(raw)
        }

        fun skip(n: Int) {
            requireRemaining(n)
            pos += n
        }

        fun bytes(n: Int): ByteArray {
            requireRemaining(n)
            val out = data.copyOfRange(pos, pos + n)
            pos += n
            return out
        }

        /** blob：u32 长度 + 原始字节。 */
        fun blob(): ByteArray = bytes(u32().toInt())

        private fun requireRemaining(n: Int) {
            if (n < 0 || data.size - pos < n) {
                throw IllegalStateException("JKS 数据越界（pos=$pos 需要 $n，剩余 ${data.size - pos}）")
            }
        }
    }

    /** DataInputStream.readUTF 语义的 modified UTF-8 解码。 */
    private fun decodeModifiedUtf8(b: ByteArray): String {
        val sb = StringBuilder(b.size)
        var i = 0
        while (i < b.size) {
            val c = b[i].toInt() and 0xff
            when {
                c < 0x80 -> {
                    sb.append(c.toChar())
                    i += 1
                }
                c shr 5 == 0b110 -> {
                    if (i + 1 >= b.size || (b[i + 1].toInt() and 0xc0) != 0x80) badUtf8()
                    sb.append((((c and 0x1f) shl 6) or (b[i + 1].toInt() and 0x3f)).toChar())
                    i += 2
                }
                c shr 4 == 0b1110 -> {
                    if (i + 2 >= b.size ||
                        (b[i + 1].toInt() and 0xc0) != 0x80 ||
                        (b[i + 2].toInt() and 0xc0) != 0x80
                    ) badUtf8()
                    sb.append(
                        (((c and 0x0f) shl 12) or
                            ((b[i + 1].toInt() and 0x3f) shl 6) or
                            (b[i + 2].toInt() and 0x3f)).toChar()
                    )
                    i += 3
                }
                else -> badUtf8()
            }
        }
        return sb.toString()
    }

    private fun badUtf8(): Nothing = throw IllegalStateException("JKS 别名 modified UTF-8 损坏")

    // ==================================================================
    // 最小 DER 解析（EncryptedPrivateKeyInfo / PKCS#8 所需子集）
    // ==================================================================

    /** DER 单个 TLV 元素（tag + 长度 + 内容）的位置描述。 */
    private class DerElement(val tag: Int, val contentStart: Int, val contentEnd: Int)

    /** 读取 [off] 处的 DER 元素（不支持高 tag 号与不定长，X.509/PKCS#8 不会出现）。 */
    private fun derElement(buf: ByteArray, off: Int): DerElement {
        if (off >= buf.size) throw IllegalStateException("DER 越界: $off")
        val tag = buf[off].toInt() and 0xff
        if (tag and 0x1f == 0x1f) throw IllegalStateException("DER 高 tag 号不支持")
        var pos = off + 1
        if (pos >= buf.size) throw IllegalStateException("DER 长度字段缺失")
        var len = buf[pos].toInt() and 0xff
        pos++
        if (len == 0x80) throw IllegalStateException("DER 不定长不支持")
        if (len >= 0x80) {
            val lenBytes = len and 0x7f
            if (lenBytes !in 1..4 || pos + lenBytes > buf.size) {
                throw IllegalStateException("DER 长度字段异常")
            }
            len = 0
            repeat(lenBytes) {
                len = (len shl 8) or (buf[pos].toInt() and 0xff)
                pos++
            }
        }
        if (pos + len > buf.size) throw IllegalStateException("DER 内容长度越界")
        return DerElement(tag, pos, pos + len)
    }

    /** DER OID 内容字节 → 点分十进制。 */
    private fun oidToString(buf: ByteArray, el: DerElement): String {
        if (el.contentStart >= el.contentEnd) throw IllegalStateException("DER OID 内容为空")
        val sb = StringBuilder()
        val first = buf[el.contentStart].toInt() and 0xff
        sb.append(first / 40).append('.').append(first % 40)
        var value = 0L
        for (i in el.contentStart + 1 until el.contentEnd) {
            val b = buf[i].toInt() and 0xff
            value = (value shl 7) or (b and 0x7f).toLong()
            if (b and 0x80 == 0) {
                sb.append('.').append(value)
                value = 0
            }
        }
        return sb.toString()
    }
}
