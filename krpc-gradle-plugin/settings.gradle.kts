/*
 * Copyright 2023-2024 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

rootProject.name = "krpc-gradle-plugin"

pluginManagement {
    includeBuild("../gradle-conventions")
    includeBuild("../gradle-settings-conventions")
}

plugins {
    id("settings-conventions")
}

include(":krpc-gradle-plugin-api")
include(":krpc-gradle-plugin-all")
include(":krpc-gradle-plugin-platform")