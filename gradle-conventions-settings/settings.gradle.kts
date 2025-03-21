/*
 * Copyright 2023-2025 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

rootProject.name = "gradle-conventions-settings"

// Code below is a hack because a chicken-egg problem, I can't use myself as a settings-plugin
apply(from = "src/main/kotlin/conventions-repositories.settings.gradle.kts")
apply(from = "src/main/kotlin/conventions-version-resolution.settings.gradle.kts")

include(":develocity")
