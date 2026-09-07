package com.ai.fler.core.service

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * JKS 解析验证。
 *
 * 夹具为 keytool（-storetype JKS）生成的真实密钥库（src/test/resources/keystores/），
 * 与宿主 JVM 的 KeyStore("JKS") 官方实现交叉验证（Android 平台无 JKS 实现，
 * JVM 有——测试即以官方实现为基准保证字节级正确性）：
 *
 * 1. 容器解析：条目列表、别名小写化、受信证书跳过、完整性摘要；
 * 2. Sun 专有保护算法解密：RSA/EC、独立密钥密码、错误密码报错；
 * 3. 解出的密钥可直接走 v1/v2/v3 签名链路（v1Preflight + IssuerFixedCertificate）。
 *
 * test_sign.jks 布局（文件内条目顺序固定）：
 *   trustedcert（受信证书）→ altkey（密钥密码独立）→ mixedcase → eckey → signkey
 * 密码：storePass=flerstorepass，altkey 的密钥密码=altkeysecret。
 */
class JksKeystoreReaderTest {

    private val storePass = "flerstorepass"

    @get:Rule
    val tmp = TemporaryFolder()

    private fun jks(name: String): ByteArray =
        File("src/test/resources/keystores/$name").readBytes()

    /** 宿主 JVM 官方 JKS 实现作为交叉验证基准。 */
    private fun jvmLoad(alias: String, keyPass: String = storePass):
        Pair<PrivateKey, List<X509Certificate>> {
        val ks = KeyStore.getInstance("JKS")
        ByteArrayInputStream(jks("test_sign.jks")).use { ks.load(it, storePass.toCharArray()) }
        val entry = ks.getEntry(alias, KeyStore.PasswordProtection(keyPass.toCharArray()))
            as KeyStore.PrivateKeyEntry
        return entry.privateKey to entry.certificateChain.map { it as X509Certificate }
    }

    // ===== 容器解析 =====

    @Test
    fun `解析出全部私钥条目并跳过受信证书`() {
        val entries = JksKeystoreReader.parseKeyEntries(jks("test_sign.jks"), storePass)
        assertEquals(listOf("altkey", "mixedcase", "eckey", "signkey"), entries.map { it.alias })
        entries.forEach { assertTrue("证书链不应为空: ${it.alias}", it.certificateChain.isNotEmpty()) }
    }

    @Test
    fun `魔数识别 JKS 与非 JKS 容器`() {
        assertTrue(JksKeystoreReader.isJks(jks("test_sign.jks")))
        val pkcs12 = File("src/main/assets/debug.keystore").readBytes()
        assertFalse(JksKeystoreReader.isJks(pkcs12))
        assertFalse(JksKeystoreReader.isJceks(jks("test_sign.jks")))
    }

    @Test
    fun `错误密钥库密码在完整性校验处报错`() {
        try {
            JksKeystoreReader.parseKeyEntries(jks("test_sign.jks"), "wrongstorepass")
            fail("预期完整性校验失败")
        } catch (e: IllegalStateException) {
            assertTrue("实际消息: ${e.message}", e.message!!.contains("密钥库密码错误"))
        }
    }

    @Test
    fun `仅含受信证书的 JKS 无私钥条目`() {
        try {
            JksKeystoreReader.loadPrivateKey(jks("trusted_only.jks"), storePass, "", storePass)
            fail("预期无私钥条目错误")
        } catch (e: IllegalStateException) {
            assertTrue("实际消息: ${e.message}", e.message!!.contains("没有私钥条目"))
        }
    }

    // ===== 私钥解密（与 JVM 官方实现交叉验证） =====

    @Test
    fun `RSA 私钥与 JVM KeyStore 解析结果逐字节一致`() {
        val mine = JksKeystoreReader.loadPrivateKey(jks("test_sign.jks"), storePass, "signkey", storePass)
        val (jvmKey, jvmCerts) = jvmLoad("signkey")

        assertEquals("signkey", mine.alias)
        assertArrayEquals("私钥 PKCS#8 应逐字节一致", jvmKey.encoded, mine.privateKey.encoded)
        assertEquals(jvmCerts.size, mine.certificateChain.size)
        jvmCerts.zip(mine.certificateChain).forEach { (a, b) ->
            assertArrayEquals("证书 DER 应逐字节一致", a.encoded, b.encoded)
        }
    }

    @Test
    fun `EC 私钥与 JVM KeyStore 解析结果一致`() {
        val mine = JksKeystoreReader.loadPrivateKey(jks("test_sign.jks"), storePass, "eckey", storePass)
        val (jvmKey, _) = jvmLoad("eckey")
        assertEquals("EC", mine.privateKey.algorithm)
        assertArrayEquals(jvmKey.encoded, mine.privateKey.encoded)
    }

    @Test
    fun `别名大小写不敏感（JKS 常规存储为小写）`() {
        val mine = JksKeystoreReader.loadPrivateKey(jks("test_sign.jks"), storePass, "MixedCase", storePass)
        assertEquals("mixedcase", mine.alias)
        val (jvmKey, _) = jvmLoad("mixedcase")
        assertArrayEquals(jvmKey.encoded, mine.privateKey.encoded)
    }

    @Test
    fun `密钥密码可独立于密钥库密码`() {
        val mine = JksKeystoreReader.loadPrivateKey(jks("test_sign.jks"), storePass, "altkey", "altkeysecret")
        val (jvmKey, _) = jvmLoad("altkey", "altkeysecret")
        assertArrayEquals(jvmKey.encoded, mine.privateKey.encoded)

        // 密钥库密码正确但密钥密码错误 → 解密校验失败
        try {
            JksKeystoreReader.loadPrivateKey(jks("test_sign.jks"), storePass, "altkey", storePass)
            fail("altkey 的密钥密码独立，storePass 应解不开")
        } catch (e: IllegalStateException) {
            assertTrue("实际消息: ${e.message}", e.message!!.contains("密钥密码错误"))
        }
    }

    @Test
    fun `错误密钥密码报错`() {
        try {
            JksKeystoreReader.loadPrivateKey(jks("test_sign.jks"), storePass, "signkey", "wrongkeypass")
            fail("预期密钥密码错误")
        } catch (e: IllegalStateException) {
            assertTrue("实际消息: ${e.message}", e.message!!.contains("密钥密码错误"))
        }
    }

    // ===== 别名选择 =====

    @Test
    fun `空别名自动选择首个密钥密码匹配的条目`() {
        // 文件内私钥顺序：altkey（独立密钥密码，storePass 解不开）→ mixedcase
        val mine = JksKeystoreReader.loadPrivateKey(jks("test_sign.jks"), storePass, "", storePass)
        assertEquals("mixedcase", mine.alias)
        val (jvmKey, _) = jvmLoad("mixedcase")
        assertArrayEquals(jvmKey.encoded, mine.privateKey.encoded)
    }

    @Test
    fun `空别名用独立密钥密码命中对应条目`() {
        val mine = JksKeystoreReader.loadPrivateKey(jks("test_sign.jks"), storePass, "", "altkeysecret")
        assertEquals("altkey", mine.alias)
    }

    @Test
    fun `未知别名报错并列出可用别名`() {
        try {
            JksKeystoreReader.loadPrivateKey(jks("test_sign.jks"), storePass, "nope", storePass)
            fail("预期别名不存在")
        } catch (e: IllegalStateException) {
            val msg = e.message!!
            assertTrue("实际消息: $msg", msg.contains("nope"))
            assertTrue("应列出可用别名: $msg", msg.contains("signkey") && msg.contains("eckey"))
        }
    }

    // ===== 签名链路（v1 兼容机制对 JKS 密钥同样成立） =====

    @Test
    fun `JKS 密钥可通过 v1 预检并完成 v1v2v3 签名`() {
        val mine = JksKeystoreReader.loadPrivateKey(jks("test_sign.jks"), storePass, "signkey", storePass)
        assertNull("v1 预检应通过", v1Preflight(mine.certificateChain))

        val fixed = mine.certificateChain.map(::IssuerFixedCertificate)
        val apk = makeApk()
        val out = File(tmp.root, "jks_signed.apk")
        sign(apk, out, mine.privateKey, fixed, v1 = true, v2 = true, v3 = true)

        val result = ApkVerifier.Builder(out)
            // API<24 平台强制走 v1 校验路径（N+ 优先 v2/v3 会跳过 v1 检查，
            // 导致 isVerifiedUsingV1Scheme 恒为 false）；v2/v3 在任何级别都会验证
            .setMinCheckedPlatformVersion(23)
            .build()
            .verify()
        assertTrue("签名未通过校验: ${result.allErrors.joinToString()}", result.isVerified)
        assertTrue("v1 未通过校验", result.isVerifiedUsingV1Scheme)
        assertTrue("v2 未通过校验", result.isVerifiedUsingV2Scheme)
        assertTrue("v3 未通过校验", result.isVerifiedUsingV3Scheme)
    }

    private fun makeApk(): File {
        val apk = File(tmp.root, "test.apk")
        java.util.zip.ZipOutputStream(apk.outputStream()).use { zos ->
            put(zos, "AndroidManifest.xml", byteArrayOf(0x03, 0x00, 0x08))
            put(zos, "classes.dex", byteArrayOf(0x64, 0x65, 0x78, 0x0a))
            put(zos, "lib/arm64-v8a/libapp.so", ByteArray(2048))
        }
        return apk
    }

    private fun put(zos: java.util.zip.ZipOutputStream, name: String, data: ByteArray) {
        val ze = java.util.zip.ZipEntry(name)
        val crc = java.util.zip.CRC32().apply { update(data) }
        ze.crc = crc.value
        ze.size = data.size.toLong()
        if (name.endsWith(".so")) ze.method = java.util.zip.ZipEntry.STORED
        zos.putNextEntry(ze)
        zos.write(data)
        zos.closeEntry()
    }

    private fun sign(
        apk: File,
        out: File,
        key: PrivateKey,
        certs: List<X509Certificate>,
        v1: Boolean,
        v2: Boolean,
        v3: Boolean,
    ) {
        val cfg = ApkSigner.SignerConfig.Builder("CERT", key, certs).build()
        ApkSigner.Builder(listOf(cfg))
            .setInputApk(apk)
            .setOutputApk(out)
            .setV1SigningEnabled(v1)
            .setV2SigningEnabled(v2)
            .setV3SigningEnabled(v3)
            .setMinSdkVersion(24)
            .build()
            .sign()
    }
}
