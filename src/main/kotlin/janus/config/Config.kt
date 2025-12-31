// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.config

import io.github.flowerblackg.janus.crypto.AesHelper
import io.github.flowerblackg.janus.logging.Logger
import io.github.flowerblackg.janus.network.netty.NettySslUtils
import io.netty.handler.ssl.SslContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.net.InetAddress
import java.nio.file.Path
import kotlin.io.path.Path


data class Config(
    var runMode: ConnectionMode = ConnectionMode.SERVER,
    /** Nonnull for server mode. */
    var port: Int? = null,
    /** Nonnull for server mode. */
    var host: InetAddress? = null,
    var ssl: SslConfig = SslConfig(),
    /** Only 1 workspace in this collection is ensured for client mode. */
    val workspaces: MutableMap<Pair<ConnectionMode, String>, WorkspaceConfig> = HashMap()
) {
    companion object {
        fun load(rawConfig: RawConfig): LoadConfigResult {
            return loadConfig(rawConfig)
        }
    }

    data class WorkspaceConfig(
        var name: String = "",
        var crypto: CryptoConfig = CryptoConfig(),
        var mode: ConnectionMode = ConnectionMode.SERVER,
        var path: Path = Path.of(""),
        var filter: FilterConfig = FilterConfig(),
        /** Nonnull for client mode. */
        var host: InetAddress? = null,
        /** Nonnull for client mode. */
        var port: Int? = null,
    )

    data class CryptoConfig(
        var aes: AesHelper? = null,
    )

    data class FilterConfig(
        var ignore: MutableList<String> = mutableListOf(),
        var protect: MutableList<String> = mutableListOf(),
    )


    data class SslConfig(
        var serverContext: SslContext? = null,
        var clientContext: SslContext? = null
    )
}


data class LoadConfigMessage(
    val content: String,
    val level: Level
) {
    enum class Level {
        WARN,
        ERROR
    }
}


data class LoadConfigResult(
    val config: Config,
    val messages: MutableList<LoadConfigMessage>
)


private fun loadAppConfigFromJson(rawConfig: RawConfig): AppConfig? {
    val configJson = when (rawConfig.values.containsKey("--config")) {
        true -> {
            val configPath = rawConfig.values["--config"]!!
            try {
                val configJsonStr = File(configPath).readText()
                JSONObject(configJsonStr)
            } catch (e: FileNotFoundException) {
                Logger.error("Config file $configPath not found")
                JSONObject()
            } catch (e: JSONException) {
                Logger.error("Failed to parse config file $configPath: ${e.message}")
                JSONObject()
            }
        }
        false -> {
            JSONObject()
        }
    }

    return try {
        AppConfig.parse(configJson)
    } catch (e: Exception) {
        Logger.warn("Failed to parse config file: ${e.message}")
        null
    }
}


/**
 * @return If null, means some critical error occurred. You should stop parsing config.
 */
private fun loadRunMode(rawConfig: RawConfig, appConfig: AppConfig?, result: LoadConfigResult): Unit? {
    var serverMode = rawConfig.flags.contains("--server")
    var clientMode = rawConfig.flags.contains("--client")

    if (serverMode && clientMode) {
        result.messages.add(LoadConfigMessage(
            "only one of --server and --client can be specified ",
            LoadConfigMessage.Level.WARN)
        )
        return null
    }

    if (serverMode || clientMode) {
        result.config.runMode = if (serverMode) ConnectionMode.SERVER else ConnectionMode.CLIENT
        return Unit
    }

    if (appConfig != null) {
        result.config.runMode = appConfig.mode
        return Unit
    }

    result.messages.add(LoadConfigMessage("--server or --client are required", LoadConfigMessage.Level.ERROR))
    return null
}


/**
 * @return If null, means some critical error occurred. You should stop parsing config.
 */
private fun loadSslConfig(rawConfig: RawConfig, appConfig: AppConfig?, result: LoadConfigResult): Unit? {
    val certPath = rawConfig.values["--ssl-cert"] ?: appConfig?.ssl?.cert
    val keyPath = rawConfig.values["--ssl-key"] ?: appConfig?.ssl?.key

    if (certPath == null && keyPath == null) {
        result.messages += LoadConfigMessage(
            "no ssl provided. data transmission will be insecure.",
            LoadConfigMessage.Level.WARN
        )
        return Unit
    }

    when (appConfig!!.mode) {
        ConnectionMode.SERVER -> {
            if (certPath == null || keyPath == null) {
                result.messages.add(LoadConfigMessage(
                    "ssl cert and key are required for server mode",
                    LoadConfigMessage.Level.ERROR
                ))
                return null
            }
        }
        ConnectionMode.CLIENT -> {
            if (certPath == null) {
                result.messages.add(LoadConfigMessage(
                    "ssl cert is required for client mode",
                    LoadConfigMessage.Level.ERROR
                ))
                return null
            }
        }
    }

    val certFile = File(certPath)
    val keyFile = keyPath?.let { File(it) }

    result.config.ssl.clientContext = NettySslUtils.createClientContext(certFile)
    if (keyFile != null) {
        result.config.ssl.serverContext = NettySslUtils.createServerContext(certFile, keyFile)
    }

    return Unit
}



/**
 * @param result Must have [LoadConfigResult.config] field `runMode` set.
 * @return If null, means some critical error occurred. You should stop parsing config.
 */
private fun loadHostAndPort(rawConfig: RawConfig, appConfig: AppConfig?, result: LoadConfigResult): Unit? {
    val config = result.config
    config.port = rawConfig.values["--port"]?.toInt() ?: appConfig?.port

    if (config.port == null && config.runMode == ConnectionMode.SERVER) {
        result.messages.add(LoadConfigMessage("Port is not specified", LoadConfigMessage.Level.ERROR))
        return null
    }


    val hostStr = rawConfig.values["--host"] ?: rawConfig.values["--ip"] ?: appConfig?.host

    if (hostStr == null && config.runMode == ConnectionMode.SERVER) {
        result.messages.add(LoadConfigMessage("Host is not specified", LoadConfigMessage.Level.ERROR))
        return null
    }

    if (hostStr != null) {
        config.host = try {
            InetAddress.getByName(hostStr)
        } catch (e: Exception) {
            result.messages.add(LoadConfigMessage("Host is not valid", LoadConfigMessage.Level.ERROR))
            null
        }
    }

    return Unit
}


/**
 *
 *
 * @param result [LoadConfigResult.config] must have it `runMode` property set before calling this function.
 * @return Workspaces that with same runMode as you specified (in config).
 */
private fun loadWorkspaces(rawConfig: RawConfig, appConfig: AppConfig?, result: LoadConfigResult) {
    val config = result.config

    val globalFilterConfig = Config.FilterConfig()
    appConfig?.filter?.ignore?.let { globalFilterConfig.ignore.addAll(it) }
    appConfig?.filter?.protect?.let { globalFilterConfig.protect.addAll(it) }

    val globalCryptoConfig = appConfig?.secret?.toCryptoConfig() ?: Config.CryptoConfig(aes = null)

    appConfig?.workspaces?.forEach { appConfigWorkspace ->
        if (appConfigWorkspace.role != config.runMode)
            return@forEach

        val workspace = Config.WorkspaceConfig(
            name = appConfigWorkspace.name,
            path = Path(appConfigWorkspace.path),
            mode = appConfigWorkspace.role,
            crypto = appConfigWorkspace.secret?.toCryptoConfig() ?: globalCryptoConfig,
            port = appConfigWorkspace.port ?: config.port,
        )

        if (workspace.mode == ConnectionMode.CLIENT) {
            workspace.host = try {
                InetAddress.getByName(appConfigWorkspace.host)
            } catch (e: Exception) {
                result.messages.add(LoadConfigMessage(
                    "Host is not valid",
                    LoadConfigMessage.Level.WARN
                ))
                null
            } ?: config.host

            if (workspace.host == null || workspace.port == null) {
                return@forEach
            }
        }

        if (workspace.crypto.aes == null) {
            result.messages.add(LoadConfigMessage(
                "Workspace '${workspace.name}' has no secret", LoadConfigMessage.Level.WARN
            ))
        }

        // load ignore config
        appConfigWorkspace.filter?.ignore?.let { workspace.filter.ignore.addAll(it) }
        appConfigWorkspace.filter?.protect?.let { workspace.filter.protect.addAll(it) }

        if (appConfigWorkspace.filter?.override != true) {
            workspace.filter.ignore.addAll(globalFilterConfig.ignore)
            workspace.filter.protect.addAll(globalFilterConfig.protect)
        }

        config.workspaces[Pair(workspace.mode, workspace.name)] = workspace
    }


    // try load workspace from commandline
    run {
        val rawCfgWorkspace = rawConfig.values["--workspace"] ?: return@run
        val rawCfgPath = rawConfig.values["--path"]?.let { Path(it) }
        val rawCfgSecret = rawConfig.values["--secret"]
        val rawCfgMode = if (rawConfig.flags.contains("--server")) ConnectionMode.SERVER else ConnectionMode.CLIENT

        val cfgWsKey = Pair(rawCfgMode, rawCfgWorkspace)

        if (config.workspaces.contains(cfgWsKey)) {
            val ws = config.workspaces[cfgWsKey]!!
            config.workspaces.clear()
            config.workspaces[cfgWsKey] = ws
            return@run
        }

        rawCfgPath ?: return@run
        rawCfgSecret ?: run {
            result.messages.add(LoadConfigMessage("Secret is not set for workspace '${rawCfgWorkspace}'.", LoadConfigMessage.Level.WARN))
        }


        val aesHelper = rawCfgSecret?.toByteArray()?.let { AesHelper(keyBytes = it) }

        val ws = Config.WorkspaceConfig(
            name = rawCfgWorkspace,
            path = rawCfgPath,
            mode = config.runMode,
            crypto = Config.CryptoConfig(aes = aesHelper),
            filter = globalFilterConfig,
            port = config.port,
            host = config.host,
        )

        if (ws.mode == ConnectionMode.SERVER && (ws.port == null || ws.host == null)) {
            result.messages.add(LoadConfigMessage(
                "Host and port must be set for server mode.", LoadConfigMessage.Level.ERROR
            ))
            return@run
        }
        config.workspaces.clear()
        config.workspaces[cfgWsKey] = ws
    }
}


fun loadConfig(rawConfig: RawConfig): LoadConfigResult {
    val result = LoadConfigResult(
        config = Config(),
        messages = ArrayList()
    )

    val appConfig: AppConfig? = loadAppConfigFromJson(rawConfig)

    loadRunMode(rawConfig, appConfig, result) ?: return result
    loadSslConfig(rawConfig, appConfig, result) ?: return result
    loadHostAndPort(rawConfig, appConfig, result) ?: return result
    loadWorkspaces(rawConfig, appConfig, result)

    val nWorkspaces = result.config.workspaces.size
    if (result.config.runMode == ConnectionMode.CLIENT && nWorkspaces != 1) {
        result.messages.add(LoadConfigMessage(
            "Client mode requires exactly one workspace.", LoadConfigMessage.Level.ERROR
        ))
    }

    return result
}
