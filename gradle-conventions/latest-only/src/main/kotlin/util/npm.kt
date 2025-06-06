/*
 * Copyright 2023-2025 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package util

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.kotlin.dsl.*
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnLockMismatchReport
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootEnvSpec
import java.io.File

fun Project.configureNpm() {
    val kotlinMasterBuild by optionalProperty()

    val executeNpmLogin by tasks.registering {
        if (!useProxyRepositories) {
            return@registering
        }

        val registryUrl = "https://packages.jetbrains.team/npm/p/krpc/build-deps/"

        // To prevent leaking of credentials in VCS on dev machine use the build directory config file
        val buildYarnConfigFile = File(project.rootDir, "build/js/.yarnrc")
        val buildNpmConfigFile = File(project.rootDir, "build/js/.npmrc")

        val spacePassword: String? = spacePassword

        doLast {
            val outputYarnText = """
                registry: "$registryUrl"
            """.trimIndent()

            var outputNpmText = """
                registry="$registryUrl"
            """.trimIndent()

            if (spacePassword != null) {
                if (spacePassword.split(".").size != 3) {
                    throw GradleException("Unexpected Space Token format")
                }

                outputNpmText += System.lineSeparator() + """
                    always-auth=true
                    save-exact=true
                    ${registryUrl.removePrefix("https:")}:_authToken=$spacePassword
                """.trimIndent()
            }

            buildYarnConfigFile.createNewFile()
            buildYarnConfigFile.writeText(outputYarnText)
            buildNpmConfigFile.createNewFile()
            buildNpmConfigFile.writeText(outputNpmText)
        }

        outputs.file(buildYarnConfigFile).withPropertyName("buildOutputYarnFile")
        outputs.file(buildNpmConfigFile).withPropertyName("buildOutputNpmFile")
    }

    val useProxy = useProxyRepositories

    plugins.withType(NodeJsRootPlugin::class.java).configureEach {
        rootProject.extensions.configure<NodeJsEnvSpec> {
            download = true
            if (useProxy) {
                downloadBaseUrl = "https://packages.jetbrains.team/files/p/krpc/build-deps/"
            }
        }

        tasks.named("kotlinNpmInstall").configure {
            dependsOn(executeNpmLogin)
        }
    }

    // necessary for CI js tests
    rootProject.plugins.withType<YarnPlugin> {
        rootProject.extensions.configure<YarnRootEnvSpec> {
            ignoreScripts = false
            download = true

            yarnLockMismatchReport = if (useProxy && !kotlinMasterBuild) {
                YarnLockMismatchReport.FAIL
            } else {
                YarnLockMismatchReport.WARNING
            }

            if (useProxy) {
                downloadBaseUrl = "https://packages.jetbrains.team/files/p/krpc/build-deps/"
            }
        }
    }
}
