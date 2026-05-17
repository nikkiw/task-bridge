package io.github.nikkiw.taskbridge.buildlogic

import com.diffplug.gradle.spotless.SpotlessExtension
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure

class TaskbridgeQualityConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply("io.gitlab.arturbosch.detekt")
        target.pluginManager.apply("com.diffplug.spotless")

        val rootDir = target.rootProject.projectDir
        val detektConfig = rootDir.resolve("config/detekt/detekt.yml")
        val catalogs = target.extensions.getByType(VersionCatalogsExtension::class.java)
        val libs = catalogs.named("libs")
        val ktlintVersion = libs.findVersion("ktlint").get().requiredVersion

        target.extensions.configure<DetektExtension> {
            buildUponDefaultConfig = true
            allRules = false
            config.setFrom(target.files(detektConfig))
            reports {
                html.required.set(true)
                xml.required.set(true)
                sarif.required.set(true)
            }
        }

        target.extensions.configure<SpotlessExtension> {
            kotlin {
                target("src/**/*.kt")
                val ktlint = ktlint(ktlintVersion)
                if (target.path == ":sample") {
                    ktlint.editorConfigOverride(
                        mapOf(
                            "ktlint_standard_function-naming" to "disabled",
                        ),
                    )
                }
                licenseHeaderFile(target.rootProject.file("config/spotless/copyright.kt"))
            }
            kotlinGradle {
                target("*.gradle.kts")
                ktlint(ktlintVersion)
                licenseHeaderFile(
                    target.rootProject.file("config/spotless/copyright.kt"),
                    "(pluginManagement|plugins|import|package|dependencyResolutionManagement|rootProject)",
                )
            }
        }
    }
}
