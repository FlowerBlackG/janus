// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus


import io.github.flowerblackg.janus.client.runClient
import io.github.flowerblackg.janus.config.*
import io.github.flowerblackg.janus.coroutine.GlobalCoroutineScopes
import io.github.flowerblackg.janus.logging.Logger
import io.github.flowerblackg.janus.miniprograms.generateSslKeys
import io.github.flowerblackg.janus.miniprograms.usage
import io.github.flowerblackg.janus.server.runServer
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.system.exitProcess


private fun isoTimeToHumanReadable(isoTime: String): String {
    val dateTime = OffsetDateTime.parse(isoTime)
    val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm:ss", Locale.ENGLISH)
    return dateTime.format(formatter)
}


private fun version() {
    Logger.info("janus ${Version.name}")
    Logger.info("> built on ${isoTimeToHumanReadable(Version.time)}")
}




private fun printConfig(config: Config) {
    Logger.info("Mode: ${config.runMode}")
    Logger.info("Total of ${config.workspaces.size} workspace(s) loaded.")
    config.workspaces.forEach { (mode, _), ws ->
        Logger.info("  $mode - ${ws.name}")
        Logger.info("    ${ws.path}")
        Logger.info("    ${ws.host}:${ws.port}")
        Logger.info("    SSL: ${if (ws.ssl.isReadyFor(ws.mode)) "ON" else "OFF"}")
    }
}


fun main(args: Array<String>) {
    val rawConfig = loadRawConfig(args)
    version()


    if ("--version" in rawConfig.flags || "-v" in rawConfig.flags)
        return

    if ("--help" in rawConfig.flags || "--usage" in rawConfig.flags || "-h" in rawConfig.flags) {
        usage()
        return
    }

    if ("--generate-ssl-keys" in rawConfig.flags) {
        exitProcess(generateSslKeys(rawConfig))
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

    val activeJobCount = GlobalCoroutineScopes.activeJobCount()
    if (activeJobCount > 0) {
        Logger.error("$activeJobCount job(s) are still running. This is not expected!")
    }

    runBlocking {
        runCatching { GlobalCoroutineScopes.joinChildren() }
    }

    Logger.info("bye~~")

    if (retCode != 0)
        Logger.error("exit with error code $retCode")

    exitProcess(retCode)
}
