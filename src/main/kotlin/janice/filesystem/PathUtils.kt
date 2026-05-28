// SPDX-License-Identifier: MulanPSL-2.0

package io.github.flowerblackg.janice.filesystem

import kotlin.io.path.Path


fun String.toPath() = Path(this)
