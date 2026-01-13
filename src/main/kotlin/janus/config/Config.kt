// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.config

import io.github.flowerblackg.janus.crypto.AesHelper
import io.github.flowerblackg.janus.filesystem.toPath
import io.github.flowerblackg.janus.network.netty.toSslClientContext
import io.github.flowerblackg.janus.network.netty.tryToSslServerContext
import io.netty.handler.ssl.SslContext
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.nio.file.Path
import kotlin.collections.set
import kotlin.io.path.Path


data class Config(
    var runMode: ConnectionMode = ConnectionMode.SERVER,
    /** Nonnull for server mode. */
    var port: Int? = null,
    /** Nonnull for server mode. */
    var host: InetAddress? = null,
    var ssl: SslConfig = SslConfig(),
    /** Workspace name to WorkspaceConfig. Only 1 workspace in this collection is ensured for client mode. */
    val workspaces: MutableMap<String, WorkspaceConfig> = HashMap()
) {
    companion object {
        fun load(rawConfig: RawConfig): LoadConfigResult {
            return loadConfig(rawConfig)
        }
    }

    data class WorkspaceConfig(
        var name: String = "",
        var remoteName: String = "",
        var crypto: CryptoConfig = CryptoConfig(),
        var mode: ConnectionMode = ConnectionMode.SERVER,
        var path: Path = Path.of(""),
        var filter: FilterConfig = FilterConfig(),
        /** Nonnull for client mode. */
        var host: InetAddress? = null,
        /** Nonnull for client mode. */
        var port: Int? = null,
        var ssl: SslConfig = SslConfig()
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
    ) {
        companion object {
            fun from(cert: Path?, privKey: Path?): SslConfig {
                if (cert == null)
                    return SslConfig()

                val res = SslConfig()
                res.clientContext = cert.toSslClientContext()
                res.serverContext = cert.tryToSslServerContext(privKey)
                return res
            }
        }

        fun isReadyFor(mode: ConnectionMode): Boolean {
            return when (mode) {
                ConnectionMode.SERVER -> serverContext != null
                ConnectionMode.CLIENT -> clientContext != null
            }
        }

        fun isNotReadyFor(mode: ConnectionMode) = !isReadyFor(mode)

        fun isEmpty() = serverContext == null && clientContext == null
    }
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
) {
    fun addError(message: String) {
        messages.add(LoadConfigMessage(message, LoadConfigMessage.Level.ERROR))
    }

    fun addWarn(message: String) {
        messages.add(LoadConfigMessage(message, LoadConfigMessage.Level.WARN))
    }
}


private fun loadAppConfigFromJson(rawConfig: RawConfig): AppConfig? {
    val configPath = rawConfig.values["--config"] ?: return null
    val configJson = JSONObject(File(configPath).readText())
    return AppConfig.parse(configJson)
}


/**
 * @return If null, means some critical error occurred. You should stop parsing config.
 */
private fun loadRunMode(rawConfig: RawConfig, appConfig: AppConfig?, result: LoadConfigResult) {
    val serverMode = rawConfig.flags.contains("--server")
    val clientMode = rawConfig.flags.contains("--client")

    if (serverMode && clientMode) {
        result.addError("only one of --server and --client can be specified.")
        throw Exception()
    }

    if (serverMode || clientMode) {
        result.config.runMode = if (serverMode) ConnectionMode.SERVER else ConnectionMode.CLIENT
        return
    }

    if (appConfig != null) {
        result.config.runMode = appConfig.mode
        return
    }

    result.addError("--server or --client are required")
    throw Exception()
}


/**
 * @return If null, means some critical error occurred. You should stop parsing config.
 */
private fun loadGlobalSslConfig(rawConfig: RawConfig, appConfig: AppConfig?, result: LoadConfigResult) {
    val certPath = rawConfig.values["--ssl-cert"] ?: appConfig?.ssl?.cert
    val keyPath = rawConfig.values["--ssl-key"] ?: appConfig?.ssl?.key

    if (certPath == null && keyPath == null) {
        result.addWarn("no ssl provided. data transmission will be insecure.")
        return
    }

    when (appConfig!!.mode) {
        ConnectionMode.SERVER -> {
            if (certPath == null || keyPath == null) {
                result.addError("ssl cert and key are required for server mode")
                throw Exception()
            }
        }
        ConnectionMode.CLIENT -> {
            if (certPath == null) {
                result.addError("ssl cert is required for client mode")
                throw Exception()
            }
        }
    }

    result.config.ssl = Config.SslConfig.from(certPath.toPath(), keyPath?.toPath())

    if (result.config.ssl.isNotReadyFor(result.config.runMode))
        result.addWarn("Failed to load ssl config.")
}



/**
 * @param result Must have [LoadConfigResult.config] field `runMode` set.
 * @return If null, means some critical error occurred. You should stop parsing config.
 */
private fun loadHostAndPort(rawConfig: RawConfig, appConfig: AppConfig?, result: LoadConfigResult) {
    val config = result.config
    config.port = rawConfig.values["--port"]?.toInt() ?: appConfig?.port

    if (config.port == null && config.runMode == ConnectionMode.SERVER) {
        result.addError("Port is not specified")
        throw Exception()
    }


    val hostStr = rawConfig.values["--host"] ?: rawConfig.values["--ip"] ?: appConfig?.host

    if (hostStr == null && config.runMode == ConnectionMode.SERVER) {
        result.addError("Host is not specified")
        throw Exception()
    }

    if (hostStr != null) {
        config.host = try {
            InetAddress.getByName(hostStr)
        } catch (e: Exception) {
            result.addError("Host is not valid")
            throw Exception()
        }
    }
}


private fun loadCommandlineWorkspace(
    globalFilterConfig: Config.FilterConfig,
    rawConfig: RawConfig,
    result: LoadConfigResult,
): Config.WorkspaceConfig? {
    val config = result.config

    val rawCfgWorkspace = rawConfig.values["--workspace"] ?: return null
    val rawCfgPath = rawConfig.values["--path"]?.let { Path(it) }
    val rawCfgSecret = rawConfig.values["--secret"]
    val rawCfgMode = if (rawConfig.flags.contains("--server")) ConnectionMode.SERVER else ConnectionMode.CLIENT

    if (rawCfgWorkspace in result.config.workspaces)
        return config.workspaces[rawCfgWorkspace]

    rawCfgPath ?: return null
    rawCfgSecret ?: run {
        result.addWarn("Secret is not set for workspace '${rawCfgWorkspace}'.")
    }


    val aesHelper = rawCfgSecret?.toByteArray()?.let { AesHelper(keyBytes = it) }

    val ws = Config.WorkspaceConfig(
        name = rawCfgWorkspace,
        remoteName = rawCfgWorkspace,
        path = rawCfgPath,
        mode = config.runMode,
        crypto = Config.CryptoConfig(aes = aesHelper),
        filter = globalFilterConfig,
        port = config.port,
        host = config.host,
        ssl = config.ssl,
    )

    if (ws.mode == ConnectionMode.SERVER && (ws.port == null || ws.host == null)) {
        result.addError("Host and port must be set for server mode.")
        return null
    }

    return ws
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

    appConfig?.workspaces?.filter { it.role == config.runMode } ?.forEach { appConfigWorkspace ->
        val workspace = Config.WorkspaceConfig(
            name = appConfigWorkspace.name,
            remoteName = appConfigWorkspace.remoteName,
            path = Path(appConfigWorkspace.path),
            mode = appConfigWorkspace.role,
            crypto = appConfigWorkspace.secret?.toCryptoConfig() ?: globalCryptoConfig,
            port = appConfigWorkspace.port ?: config.port,
            host = runCatching { InetAddress.getByName(appConfigWorkspace.host) }.getOrNull() ?: config.host
        )

        if (workspace.mode == ConnectionMode.CLIENT && (workspace.host == null || workspace.port == null)) {
            result.addWarn("Failed to load workspace ${workspace.name} ${workspace.mode}. Host or port not set.")
            return@forEach
        }

        if (workspace.crypto.aes == null) {
            result.addWarn("Workspace '${workspace.name}' has no secret")
        }

        // load ignore config
        appConfigWorkspace.filter?.ignore?.let { workspace.filter.ignore.addAll(it) }
        appConfigWorkspace.filter?.protect?.let { workspace.filter.protect.addAll(it) }

        if (appConfigWorkspace.filter?.override != true) {
            workspace.filter.ignore.addAll(globalFilterConfig.ignore)
            workspace.filter.protect.addAll(globalFilterConfig.protect)
        }


        // load ssl config
        val cert = appConfigWorkspace.ssl?.cert ?: appConfig.ssl?.cert
        val privKey = appConfigWorkspace.ssl?.key ?: appConfig.ssl?.key
        var sslConfig = Config.SslConfig.from(cert?.toPath(), privKey?.toPath())
        if (sslConfig.isEmpty())
            sslConfig = config.ssl

        workspace.ssl = sslConfig

        config.workspaces[workspace.name] = workspace
    }


    // try load workspace from commandline

    val cliWs = loadCommandlineWorkspace(globalFilterConfig, rawConfig, result)
    if (cliWs != null) {
        config.workspaces.clear()
        config.workspaces[cliWs.name] = cliWs
    }
}


fun loadConfig(rawConfig: RawConfig): LoadConfigResult {
    val result = LoadConfigResult(
        config = Config(),
        messages = ArrayList()
    )

    val appConfig: AppConfig? = runCatching { loadAppConfigFromJson(rawConfig) }.onFailure {
        result.addWarn("Failed to parse app config (json).")
    }.getOrNull()

    runCatching { loadRunMode(rawConfig, appConfig, result) }.getOrNull() ?: return result
    runCatching { loadGlobalSslConfig(rawConfig, appConfig, result) }.getOrNull() ?: return result
    runCatching { loadHostAndPort(rawConfig, appConfig, result) }.getOrNull() ?: return result
    runCatching { loadWorkspaces(rawConfig, appConfig, result) }.getOrNull() ?: return result

    val nWorkspaces = result.config.workspaces.size
    if (result.config.runMode == ConnectionMode.CLIENT && nWorkspaces != 1) {
        result.addError("Client mode requires exactly one workspace.")
    }

    return result
}
