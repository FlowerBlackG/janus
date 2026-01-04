// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.miniprograms

import io.github.flowerblackg.janus.logging.Logger


fun usage(error: String? = null) {
    if (error != null) {
        Logger.error(error)
    }
    Logger.info("Usage: janus [options]")
    Logger.info("")

    Logger.info("Modes:")
    Logger.info("  --server               Run in server mode")
    Logger.info("  --client               Run in client mode")
    Logger.info("")

    Logger.info("Connection Settings:")
    Logger.info("  --ip, --host [value]   Server IP address (Required for client)")
    Logger.info("  --port [value]         Port to connect to (client) or listen on (server)")
    Logger.info("  --config [file]        Path to config.json")
    Logger.info("")

    Logger.info("Workspace & Security:")
    Logger.info("  --workspace [value]    Select workspace name to sync")
    Logger.info("  --path [value]         Temporary workspace path")
    Logger.info("  --secret [value]       Secret for authentication")
    Logger.info("  --ssl-cert [path]      Path to SSL certificate")
    Logger.info("  --ssl-key [path]       Path to SSL private key")
    Logger.info("")

    Logger.info("Client Specific:")
    Logger.info("  --dangling [mode]      Dangling file policy: remove, keep, or panic")
    Logger.info("")

    Logger.info("Subprograms:")
    Logger.info("  --version              Show Janus version and exit")
    Logger.info("  --help, --usage        Show this help message")
    Logger.info("  --generate-ssl-keys    Generate ECP384 keys and save to SSL paths")
    Logger.info("")

    Logger.info("Read more at https://github.com/FlowerBlackG/janus")
}
