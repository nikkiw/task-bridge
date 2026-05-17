plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.detekt.gradlePlugin)
    compileOnly(libs.licenseReport.gradlePlugin)
    compileOnly(libs.spotless.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidLibrary") {
            id = "taskbridge.android.library"
            implementationClass =
                "io.github.nikkiw.taskbridge.buildlogic.TaskbridgeAndroidLibraryConventionPlugin"
        }
        register("androidApplication") {
            id = "taskbridge.android.application"
            implementationClass =
                "io.github.nikkiw.taskbridge.buildlogic.TaskbridgeAndroidApplicationConventionPlugin"
        }
        register("quality") {
            id = "taskbridge.quality"
            implementationClass =
                "io.github.nikkiw.taskbridge.buildlogic.TaskbridgeQualityConventionPlugin"
        }
        register("license") {
            id = "taskbridge.license"
            implementationClass =
                "io.github.nikkiw.taskbridge.buildlogic.TaskbridgeLicenseConventionPlugin"
        }
        register("publish") {
            id = "taskbridge.publish"
            implementationClass =
                "io.github.nikkiw.taskbridge.buildlogic.TaskbridgePublishingConventionPlugin"
        }
    }
}
