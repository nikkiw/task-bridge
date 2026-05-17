package io.github.nikkiw.taskbridge.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

/**
 * AGP 9: built-in Kotlin ([android.builtInKotlin]); do not apply `org.jetbrains.kotlin.android`.
 */
class TaskbridgeAndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target.pluginManager) {
            apply("com.android.library")
            apply("org.jetbrains.kotlin.plugin.serialization")
        }

        target.extensions.configure<LibraryExtension> {
            compileSdk = 36
            defaultConfig.apply {
                minSdk = 24
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                consumerProguardFiles("consumer-rules.pro")
            }
            compileOptions.apply {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            testOptions.unitTests {
                isIncludeAndroidResources = true
                isReturnDefaultValues = true
            }
            lint {
                abortOnError = true
                warningsAsErrors = false
            }
        }

        target.tasks.withType<KotlinCompilationTask<*>>().configureEach {
            val options = compilerOptions
            if (options is KotlinJvmCompilerOptions) {
                options.jvmTarget.set(JvmTarget.JVM_17)
            }
            if (name.contains("Test", ignoreCase = true)) {
                options.freeCompilerArgs.add("-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi")
            }
        }
    }
}
