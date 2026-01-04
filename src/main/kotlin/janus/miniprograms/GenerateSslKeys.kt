// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.miniprograms

import io.github.flowerblackg.janus.config.RawConfig
import io.github.flowerblackg.janus.logging.Logger
import io.github.flowerblackg.janus.network.netty.NettySslUtils
import kotlin.io.path.Path


fun generateSslKeys(rawConfig: RawConfig): Int {
    val certPath = rawConfig.values["--ssl-cert"]?.let { Path(it) }
    val keyPath = rawConfig.values["--ssl-key"]?.let { Path(it) }

    val x509 = runCatching { NettySslUtils.generateSelfSignedCert(certPath, keyPath) }.getOrNull()

    if (x509 == null) {
        Logger.error("Failed to generate self-signed certificate.")
        return 1
    }

    if (certPath == null) {
        Logger.success("Here comes your self-signed certificate:\n${x509.certificatePEM}")
    }

    if (keyPath == null) {
        Logger.success("Here comes your self-signed key:\n${x509.privateKeyPEM}")
    }


    return 0
}
