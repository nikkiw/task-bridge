package io.github.nikkiw.taskbridge.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.get
import org.gradle.plugins.signing.SigningExtension

private fun Project.stringProperty(vararg names: String): String? =
    names
        .asSequence()
        .mapNotNull { name -> findProperty(name)?.toString()?.takeIf(String::isNotBlank) }
        .firstOrNull()

class TaskbridgePublishingConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply("maven-publish")
        target.pluginManager.apply("signing")

        target.group = "io.github.nikkiw.taskbridge"
        target.version = target.findProperty("VERSION_NAME") ?: "0.1.0-SNAPSHOT"

        target.extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("release") {
                    artifactId = target.name

                    pom {
                        name.set(target.name)
                        description.set("Reliable AI Task Streaming library for Android")
                        url.set("https://github.com/nikkiw/task-bridge")
                        licenses {
                            license {
                                name.set("Apache License, Version 2.0")
                                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            }
                        }
                        developers {
                            developer {
                                id.set("nikkiw")
                                name.set("Nikolay Vlasov")
                                email.set("nikolayv_dev@outlook.com")
                                url.set("https://github.com/nikkiw")
                            }
                        }
                        scm {
                            connection.set("scm:git:https://github.com/nikkiw/task-bridge.git")
                            developerConnection.set("scm:git:ssh://github.com/nikkiw/task-bridge.git")
                            url.set("https://github.com/nikkiw/task-bridge")
                        }
                    }
                }
            }

            repositories {
                maven {
                    name = "OSSRH"
                    // Use the Central Portal OSSRH compatibility API so built-in maven-publish
                    // continues to work without depending on a third-party Gradle plugin.
                    val releaseRepoUrl = "https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/"
                    val snapshotsRepoUrl = "https://central.sonatype.com/repository/maven-snapshots/"
                    url = target.uri(if (target.version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releaseRepoUrl)

                    credentials {
                        username = target.stringProperty("ossrhUsername")
                        password = target.stringProperty("ossrhPassword")
                    }
                }
            }
        }

        target.extensions.configure<SigningExtension> {
            val signingKey = target.stringProperty("signingKey")
            val signingPassword = target.stringProperty("signingPassword")
            if (signingKey != null && signingPassword != null) {
                @Suppress("UnstableApiUsage")
                useInMemoryPgpKeys(signingKey, signingPassword)
                sign(target.extensions.findByType<PublishingExtension>()?.publications?.get("release"))
            }
        }

        target.afterEvaluate {
            val component = target.components.findByName("release") ?: return@afterEvaluate
            target.extensions.findByType<PublishingExtension>()?.publications?.named("release", MavenPublication::class.java)?.configure {
                from(component)
            }
        }
    }
}
