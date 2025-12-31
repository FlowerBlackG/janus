// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.network.netty

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
            .sslProvider(SslProvider.OPENSSL)
            .build()
    }

    fun createClientContext(cert: File): SslContext {
        return SslContextBuilder.forClient()
            .sslProvider(SslProvider.OPENSSL)
            .trustManager(cert)
            .endpointIdentificationAlgorithm(null)
            .build()
    }

}
