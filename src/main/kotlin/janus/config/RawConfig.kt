// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janus.config

data class RawConfig(
    val env: MutableMap<String, String>,
    val flags: MutableSet<String>,
    val values: MutableMap<String, String>,
    val paimons: MutableList<String>
)

private fun parseCliVars(args: Array<String>, container: RawConfig) {
    container.flags.clear()
    container.values.clear()
    container.paimons.clear()

    var i = 0
    while (i < args.size) {
        val key = args[i]
        if (!key.startsWith("--")) {
            container.paimons.add(key)
            i += 1
            continue
        }

        if (i + 1 >= args.size|| args[i + 1].startsWith("--")) {
            container.flags.add(key)
            i += 1
            continue
        }

        container.values[key] = args[i + 1]
        i += 2
    }
}


private fun parseEnvVars(container: RawConfig) {
    container.env.clear()
    for (kv in System.getenv()) {
        container.env[kv.key] = kv.value
    }
}


fun loadRawConfig(args: Array<String>): RawConfig {
    val config = RawConfig(
        env = HashMap(),
        flags = HashSet(),
        values = HashMap(),
        paimons = ArrayList()
    )

    parseCliVars(args, config)
    parseEnvVars(config)

    return config
}
