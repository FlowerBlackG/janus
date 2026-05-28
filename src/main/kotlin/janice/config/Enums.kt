// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janice.config

import io.github.flowerblackg.janice.config.AppConfig.CaseInsensitiveEnumSerializer
import kotlinx.serialization.Serializable


// Connection Mode (Server/Client)
@Serializable(with = ConnectionModeSerializer::class)
enum class ConnectionMode {
    SERVER, CLIENT
}

object ConnectionModeSerializer :
    CaseInsensitiveEnumSerializer<ConnectionMode>(ConnectionMode::class.java, ConnectionMode.values())

