package io.github.nikkiw.taskbridge.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

/**
 * AGP 9: built-in Kotlin ([android.builtInKotlin]); do not apply `org.jetbrains.kotlin.android`.
 */
class TaskbridgeAndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target.pluginManager) {
            apply("com.android.application")
            apply("org.jetbrains.kotlin.plugin.compose")
        }

        target.extensions.configure<ApplicationExtension> {
            compileSdk = 36
            defaultConfig.apply {
                minSdk = 24
                targetSdk = 36
                versionCode = 1
                versionName = "1.0"
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions.apply {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            buildFeatures {
                compose = true
            }
            packaging {
                resources {
                    excludes += "/META-INF/{AL2.0,LGPL2.1}"
                    pickFirsts.add("META-INF/LICENSE")
                    pickFirsts.add("META-INF/NOTICE")
                }
            }
            lint {
                abortOnError = true
                warningsAsErrors = false
            }
        }

        target.tasks.withType<KotlinJvmCompile>().configureEach {
            compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}
