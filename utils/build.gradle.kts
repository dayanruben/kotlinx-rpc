/*
 * Copyright 2023-2025 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

plugins {
    alias(libs.plugins.conventions.kmp)
    alias(libs.plugins.atomicfu)
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.coroutines.core)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
