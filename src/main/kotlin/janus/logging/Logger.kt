package io.github.flowerblackg.janus.logging

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter


/**
 * Logger for janus.
 *
 * Logging by simply calling like: Logger.info("message")
 */
class Logger private constructor() {
    companion object {
        var instance: Logger? = null
            get() {
                if (field == null)
                    field = Logger()
                return field
            }
            set(value) {
                throw UnsupportedOperationException("Logger is a singleton")
            }

        fun info(msg: String, delim: String = "\n") {
            instance?.info(msg, delim)
        }

        fun error(msg: String, delim: String = "\n") {
            instance?.error(msg, delim)
        }

        fun warn(msg: String, delim: String = "\n") {
            instance?.warn(msg, delim)
        }

        fun success(msg: String, delim: String = "\n") {
            instance?.success(msg, delim)
        }

        fun debug(msg: String, delim: String = "\n") {
            instance?.debug(msg, delim)
        }
    }


    fun log(msg: String, delim: String = "\n") {
        print("${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))} $msg$delim")
    }

    fun info(msg: String, delim: String = "\n") {
        this.log("[INFO ] ${msg}", delim)
    }

    fun error(msg: String, delim: String = "\n") {
        this.log("\u001B[31m[ERROR]\u001B[0m ${msg}", delim)
    }

    fun warn(msg: String, delim: String = "\n") {
        this.log("\u001B[33m[WARN ]\u001B[0m ${msg}", delim)
    }

    fun success(msg: String, delim: String = "\n") {
        this.log("\u001B[32m[SUCCESS]\u001B[0m ${msg}", delim)
    }

    fun debug(msg: String, delim: String = "\n") {
        this.log("\u001B[34m[DEBUG]\u001B[0m ${msg}", delim)
    }
}
