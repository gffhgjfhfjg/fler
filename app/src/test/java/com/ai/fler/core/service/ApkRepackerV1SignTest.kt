package com.ai.fler.core.service

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateEncodingException
import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

/**
 * v1 签名兼容性修复（IssuerFixedCertificate）验证：
 *
 * 1. 模拟设备缺陷：PKCS12 证书的 getIssuerX500Principal() 抛
 *    CertificateEncodingException → 复现 "Failed to sign using signer CERT
 *    ← Failed to encode signature block"；
 * 2. IssuerFixedCertificate 包装后 v1 各组合签名成功，且 ApkVerifier 校验通过；
 * 3. v3 可单独启用（v2 关闭时不再被强制联动）。
 */
class ApkRepackerV1SignTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 模拟 Android 平台 BC 证书实现缺陷：getIssuerX500Principal() 抛异常。 */
    private class BrokenIssuerCertificate(cert: X509Certificate) :
        DelegatingX509Certificate(cert) {

        override fun getIssuerX500Principal(): X500Principal =
            throw CertificateEncodingException("模拟 Android BC 证书实现缺陷")
    }

    // ===== 密钥加载 =====

    private fun loadKey(): Pair<PrivateKey, List<X509Certificate>> {
        val ks = KeyStore.getInstance("PKCS12")
        File("src/main/assets/debug.keystore").inputStream().use {
            ks.load(it, "android".toCharArray())
        }
        val entry = ks.getEntry(
            "androiddebugkey", KeyStore.PasswordProtection("android".toCharArray())
        ) as KeyStore.PrivateKeyEntry
        val certs = entry.certificateChain.map { it as X509Certificate }
        return entry.privateKey to certs
    }

    private fun makeApk(dir: File): File {
        val apk = File(dir, "test.apk")
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

    private fun root(t: Throwable, depth: Int = 0): String =
        if (t.cause != null && depth < 8) root(t.cause!!, depth + 1) else t.toString()

    // ===== 测试 =====

    @Test
    fun `extractIssuerDer 与 JDK 解析的 issuer DER 完全一致`() {
        val (_, certs) = loadKey()
        val cert = certs.first()
        assertArrayEquals(
            "提取的 issuer DER 应与 JDK X500Principal 编码逐字节一致",
            cert.issuerX500Principal.encoded,
            extractIssuerDer(cert.encoded),
        )
    }

    @Test
    fun `模拟设备缺陷可复现 Failed to encode signature block`() {
        val (key, certs) = loadKey()
        val apk = makeApk(tmp.root)
        val out = File(tmp.root, "out.apk")
        val broken = certs.map(::BrokenIssuerCertificate)
        try {
            sign(apk, out, key, broken, v1 = true, v2 = false, v3 = false)
            throw AssertionError("预期 v1 签名失败（缺陷模拟未生效）")
        } catch (e: Exception) {
            // 复现用户报错的最内层异常
            assertTrue(
                "实际异常: ${root(e)}",
                root(e).contains("Failed to encode signature block"),
            )
        }
    }

    @Test
    fun `IssuerFixedCertificate 修复 v1 签名且产出可校验`() {
        val (key, certs) = loadKey()
        val apk = makeApk(tmp.root)
        val out = File(tmp.root, "fixed.apk")
        val fixed = certs.map { BrokenIssuerCertificate(it) }
            .map { IssuerFixedCertificate(it) }
        sign(apk, out, key, fixed, v1 = true, v2 = false, v3 = false)

        // 不只看签名不抛异常：用 ApkVerifier 校验产物确实是有效 v1 签名
        val result = ApkVerifier.Builder(out)
            .setMinCheckedPlatformVersion(24)
            .build()
            .verify()
        assertTrue("v1 签名未通过校验: ${result.allErrors.joinToString()}", result.isVerified)
        assertTrue("v1 方案未通过校验", result.isVerifiedUsingV1Scheme)
    }

    @Test
    fun `v1+v2+v3 全组合可用修复后的证书签名`() {
        val (key, certs) = loadKey()
        val apk = makeApk(tmp.root)
        val fixed = certs.map { IssuerFixedCertificate(it) }
        sign(apk, File(tmp.root, "all.apk"), key, fixed, v1 = true, v2 = true, v3 = true)
    }

    @Test
    fun `v3 可以单独签名（不强制联动 v2）`() {
        val (key, certs) = loadKey()
        val apk = makeApk(tmp.root)
        val out = File(tmp.root, "v3only.apk")
        val fixed = certs.map { IssuerFixedCertificate(it) }
        sign(apk, out, key, fixed, v1 = false, v2 = false, v3 = true)

        val result = ApkVerifier.Builder(out)
            .setMinCheckedPlatformVersion(28)
            .build()
            .verify()
        assertTrue(
            "v3-only 签名未通过校验: ${result.allErrors.joinToString()}",
            result.isVerifiedUsingV3Scheme,
        )
    }

    @Test
    fun `v2+v3（无 v1）正常`() {
        val (key, certs) = loadKey()
        val apk = makeApk(tmp.root)
        val fixed = certs.map { IssuerFixedCertificate(it) }
        sign(apk, File(tmp.root, "v23.apk"), key, fixed, v1 = false, v2 = true, v3 = true)
    }

    @Test
    fun `包装后证书的 issuer 与原始证书语义一致`() {
        val (_, certs) = loadKey()
        val cert = certs.first()
        val wrapped = IssuerFixedCertificate(cert)
        assertEquals(cert.issuerX500Principal, wrapped.issuerX500Principal)
        assertArrayEquals(cert.encoded, wrapped.encoded)
        assertEquals(cert.serialNumber, wrapped.serialNumber)
    }

    // ===== v1 签名预检 =====

    @Test
    fun `v1 预检暴露被 apksig 吞掉的真实异常`() {
        val (_, certs) = loadKey()
        val broken = certs.map(::BrokenIssuerCertificate)
        val detail = v1Preflight(broken)
        assertNotNull("损坏证书的预检应返回失败详情", detail)
        assertTrue(
            "应包含真实异常类名（而非被 apksig 吞掉的笼统信息）: $detail",
            detail!!.contains("CertificateEncodingException")
        )
        assertTrue("应包含失败步骤: $detail", detail.contains("②"))
        assertTrue("应包含证书实现类便于定位: $detail", detail.contains("certImpl="))
    }

    @Test
    fun `v1 预检对修复后证书与原始证书通过`() {
        val (_, certs) = loadKey()
        assertNull("修复后证书应通过预检", v1Preflight(certs.map(::IssuerFixedCertificate)))
        assertNull("原始证书应通过预检", v1Preflight(certs))
    }

    @Test
    fun `v1 预检对空证书链返回失败`() {
        assertEquals("v1 预检失败：证书链为空", v1Preflight(emptyList()))
    }
}
