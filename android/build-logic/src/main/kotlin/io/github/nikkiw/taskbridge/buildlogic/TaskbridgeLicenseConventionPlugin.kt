package io.github.nikkiw.taskbridge.buildlogic

import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.github.jk1.license.LicenseReportExtension
import com.github.jk1.license.filter.LicenseBundleNormalizer
import com.github.jk1.license.render.InventoryHtmlReportRenderer
import com.github.jk1.license.render.JsonReportRenderer
import com.github.jk1.license.render.ReportRenderer
import com.github.jk1.license.render.TextReportRenderer
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register

abstract class BundleLegalFilesTask : DefaultTask() {
    @get:InputFile
    @get:Optional
    abstract val licenseFile: RegularFileProperty

    @get:InputFile
    @get:Optional
    abstract val noticeFile: RegularFileProperty

    @get:InputFile
    @get:Optional
    abstract val thirdPartyLicensesFile: RegularFileProperty

    @get:org.gradle.api.tasks.Input
    abstract val thirdPartyLicensesFileName: org.gradle.api.provider.Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun bundle() {
        val metaInf = outputDir.get().asFile.resolve("META-INF")
        metaInf.mkdirs()

        licenseFile.orNull?.asFile?.let { if (it.exists()) it.copyTo(metaInf.resolve("LICENSE"), overwrite = true) }
        noticeFile.orNull?.asFile?.let { if (it.exists()) it.copyTo(metaInf.resolve("NOTICE"), overwrite = true) }
        thirdPartyLicensesFile.orNull?.asFile?.let { if (it.exists()) it.copyTo(metaInf.resolve(thirdPartyLicensesFileName.get()), overwrite = true) }
    }
}

class TaskbridgeLicenseConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val includeLicenses = target.providers.gradleProperty("taskbridge.includeLicenses")
            .getOrElse("false").toBoolean()

        target.pluginManager.apply("com.github.jk1.dependency-license-report")

        target.extensions.configure<LicenseReportExtension> {
            outputDir = target.layout.buildDirectory
                .dir("reports/dependency-license")
                .get()
                .asFile
                .absolutePath

            // Focus on release dependencies for the library
            configurations = arrayOf("releaseRuntimeClasspath")

            excludeOwnGroup = true
            excludeBoms = true

            renderers = arrayOf<ReportRenderer>(
                TextReportRenderer("THIRD_PARTY_LICENSES.txt"),
                JsonReportRenderer("third-party-licenses.json"),
                InventoryHtmlReportRenderer(
                    "third-party-licenses.html",
                    "TaskBridge Android third-party licenses"
                )
            )

            filters = arrayOf(
                LicenseBundleNormalizer()
            )

            allowedLicensesFile = target.rootProject.layout.projectDirectory
                .file("config/license/allowed-licenses.json")
                .asFile
        }

        // 1. Task to copy generated report to project root for GitHub visibility
        // We use a custom task instead of Copy to avoid Gradle's directory overlap tracking errors
        // when multiple modules write to the same root directory.
        target.tasks.register("syncRootLicenseReport") {
            val reportFile = target.layout.buildDirectory.file("reports/dependency-license/THIRD_PARTY_LICENSES.txt")
            val targetFile = target.rootProject.layout.projectDirectory.file("THIRD_PARTY_LICENSES_${target.name}.txt")

            inputs.file(reportFile)
            outputs.file(targetFile)

            doLast {
                reportFile.get().asFile.copyTo(targetFile.asFile, overwrite = true)
            }
            dependsOn("generateLicenseReport")
        }

        // 2. Ensure AAR includes legal files in META-INF
        target.pluginManager.withPlugin("com.android.library") {
            val androidComponents =
                target.extensions.getByType(LibraryAndroidComponentsExtension::class.java)

            androidComponents.onVariants { variant ->
                // Only bundle licenses for release builds or when explicitly requested
                // This prevents generateLicenseReport (which is CC-incompatible) from running on debug builds
                if (variant.name.contains("release", ignoreCase = true) || includeLicenses) {
                    val bundleLegalFiles = target.tasks.register<BundleLegalFilesTask>("bundleLegalFiles${variant.name.replaceFirstChar { it.uppercase() }}") {
                        licenseFile.set(target.rootProject.layout.projectDirectory.file("LICENSE"))
                        noticeFile.set(target.rootProject.layout.projectDirectory.file("NOTICE"))
                        thirdPartyLicensesFile.set(target.layout.buildDirectory.file("reports/dependency-license/THIRD_PARTY_LICENSES.txt"))
                        thirdPartyLicensesFileName.set("THIRD_PARTY_LICENSES_${target.name}.txt")
                        outputDir.set(target.layout.buildDirectory.dir("intermediates/legal-files/${variant.name}"))
                        dependsOn("generateLicenseReport")
                    }

                    variant.sources.resources?.addGeneratedSourceDirectory(
                        bundleLegalFiles,
                        BundleLegalFilesTask::outputDir
                    )
                }
            }

            target.extensions.configure<LibraryExtension> {
                packaging {
                    resources {
                        pickFirsts.add("META-INF/LICENSE")
                        pickFirsts.add("META-INF/NOTICE")
                    }
                }
            }
        }
    }
}
