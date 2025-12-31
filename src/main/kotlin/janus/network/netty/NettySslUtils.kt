// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.network.netty

import io.github.flowerblackg.janus.logging.Logger
import io.netty.handler.ssl.OpenSsl
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslProvider
import io.netty.pkitesting.CertificateBuilder
import io.netty.pkitesting.X509Bundle
import java.io.File
import java.nio.file.Path
import java.time.temporal.ChronoUnit
import kotlin.io.path.writeText


object NettySslUtils {
    private var _sslProvider: SslProvider? = null
    private val sslProvider: SslProvider
        get() {
            if (_sslProvider != null)
                return _sslProvider!!

            _sslProvider = if (OpenSsl.isAvailable()) {
                Logger.info("Using native OpenSSL for ultimate performance :D")
                SslProvider.OPENSSL
            }
            else {
                Logger.warn("Failed to load OpenSSL. Fallback to JDK's SSL. Performance will suffer :(")
                SslProvider.JDK
            }

            return _sslProvider!!
        }


    fun generateSelfSignedCert(
        crtPath: Path? = null,
        keyPath: Path? = null,
        subject: String = "CN=JanusSync",
        validYears: Long = 1000,
        algorithm: CertificateBuilder.Algorithm = CertificateBuilder.Algorithm.ed25519
    ): X509Bundle {
        // reference: https://netty.io/4.2/api/io/netty/pkitesting/CertificateBuilder.html

        val now = java.time.Instant.now()
        val template = CertificateBuilder()
            .notBefore(now.minus(1, ChronoUnit.DAYS))
            .notAfter(now.plus(validYears * 365, ChronoUnit.DAYS))
            .algorithm(algorithm)
        val issuer = template.copy()
            .subject(subject)
            .setKeyUsage(true, CertificateBuilder.KeyUsage.digitalSignature, CertificateBuilder.KeyUsage.keyCertSign)
            .setIsCertificateAuthority(true)
            .buildSelfSigned()
        val leaf = template.copy()
            .subject(subject)
            .setKeyUsage(true, CertificateBuilder.KeyUsage.digitalSignature)
            .addExtendedKeyUsage(CertificateBuilder.ExtendedKeyUsage.PKIX_KP_SERVER_AUTH)
            .addExtendedKeyUsage(CertificateBuilder.ExtendedKeyUsage.PKIX_KP_CLIENT_AUTH)
            .buildIssuedBy(issuer)

        crtPath?.writeText(leaf.certificatePEM)
        keyPath?.writeText(leaf.privateKeyPEM)

        return leaf
    }


    fun createServerContext(cert: File, privateKey: File): SslContext {
        return SslContextBuilder.forServer(cert, privateKey)
            .sslProvider(sslProvider)
            .build()
    }

    fun createClientContext(cert: File): SslContext {
        return SslContextBuilder.forClient()
            .sslProvider(sslProvider)
            .trustManager(cert)
            .endpointIdentificationAlgorithm(null)
            .build()
    }

}
