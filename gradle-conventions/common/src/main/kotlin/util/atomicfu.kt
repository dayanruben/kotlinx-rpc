/*
 * Copyright 2023-2024 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package util

import org.gradle.api.Project

fun Project.applyAtomicfuPlugin() {
    plugins.apply(libs.plugins.atomicfu.get().pluginId)
}
