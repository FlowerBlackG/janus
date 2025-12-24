// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus


import io.github.flowerblackg.janus.client.runClient
import io.github.flowerblackg.janus.config.*
import io.github.flowerblackg.janus.coroutine.GlobalCoroutineScopes
import io.github.flowerblackg.janus.logging.Logger
import io.github.flowerblackg.janus.server.runServer
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

private fun usage(error: String? = null) {
    if (error != null) {
        Logger.error(error)
    }
    Logger.info("Usage: janus [options]")
    Logger.info("Options:")
    Logger.info("  --server: run in server mode")
    Logger.info("  --client: run in client mode")
    Logger.info("  --help: show this help message")
    Logger.info("  --version: show version information")
    Logger.info("")
    Logger.info("Read more at https://github.com/FlowerBlackG/janus")
}


private fun version() {
    Logger.info("janus ${Version.name} (${Version.code})")
}


private fun printConfig(config: Config) {
    Logger.info("Mode: ${config.runMode}")
    Logger.info("Total of ${config.workspaces.size} workspace(s) loaded.")
    config.workspaces.forEach { (mode, _), config ->
        Logger.info("\t$mode - ${config.name}")
        Logger.info("\t\t${config.path}")
    }
}


fun main(args: Array<String>) {
    val rawConfig = loadRawConfig(args)
    version()


    if (rawConfig.flags.contains("--version") || rawConfig.flags.contains("-v"))
        return

    if (rawConfig.flags.contains("--help") || rawConfig.flags.contains("--usage") || rawConfig.flags.contains("-h")) {
        usage()
        return
    }

    val loadConfigResult = loadConfig(rawConfig)
    val config = loadConfigResult.config

    var loadConfigFailed = false
    for (msg in loadConfigResult.messages) {
        when (msg.level) {
            LoadConfigMessage.Level.WARN -> Logger.warn(msg.content)
            LoadConfigMessage.Level.ERROR -> {
                Logger.error(msg.content)
                loadConfigFailed = true
            }
        }
    }


    if (loadConfigFailed) {
        Logger.error("load config failed, exit. Check usage by --help")
        exitProcess(1)
    }

    printConfig(config)

    val coroFuture = GlobalCoroutineScopes.IO.async {
        when (config.runMode) {
            ConnectionMode.SERVER -> runServer(config)
            ConnectionMode.CLIENT -> runClient(config)
        }
    }

    val retCode = runBlocking {
        try {
            coroFuture.await()
        } catch (e: Exception) {
            Logger.error("Something went wrong: ${e.message}", trace = e)
            -1
        }
    }

    if (retCode != 0)
        Logger.error("exit with error code $retCode")

    Logger.info("bye~~")
    if (retCode != 0)
        exitProcess(retCode)
}
