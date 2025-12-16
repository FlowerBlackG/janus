// SPDX-License-Identifier: MulanPSL-2.0

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

        fun info(msg: String, delim: String = "\n", trace: Throwable? = null) {
            instance?.info(msg, delim, trace)
        }

        fun error(msg: String, delim: String = "\n", trace: Throwable? = null) {
            instance?.error(msg, delim, trace)
        }

        fun warn(msg: String, delim: String = "\n", trace: Throwable? = null) {
            instance?.warn(msg, delim, trace)
        }

        fun success(msg: String, delim: String = "\n", trace: Throwable? = null) {
            instance?.success(msg, delim, trace)
        }

        fun debug(msg: String, delim: String = "\n", trace: Throwable? = null) {
            instance?.debug(msg, delim, trace)
        }
    }


    fun log(msg: String, delim: String = "\n", trace: Throwable? = null) {
        print("${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))} $msg$delim")
        trace?.printStackTrace()
    }

    fun info(msg: String, delim: String = "\n", trace: Throwable? = null) {
        this.log("[INFO ] ${msg}", delim, trace)
    }

    fun error(msg: String, delim: String = "\n", trace: Throwable? = null) {
        this.log("\u001B[31m[ERROR]\u001B[0m ${msg}", delim, trace)
    }

    fun warn(msg: String, delim: String = "\n", trace: Throwable? = null) {
        this.log("\u001B[33m[WARN ]\u001B[0m ${msg}", delim, trace)
    }

    fun success(msg: String, delim: String = "\n", trace: Throwable? = null) {
        this.log("\u001B[32m[SUCCESS]\u001B[0m ${msg}", delim, trace)
    }

    fun debug(msg: String, delim: String = "\n", trace: Throwable? = null) {
        this.log("\u001B[34m[DEBUG]\u001B[0m ${msg}", delim, trace)
    }
}
