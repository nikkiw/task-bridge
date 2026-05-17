package io.github.nikkiw.taskbridge.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create

class TaskbridgePublishingConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply("maven-publish")

        target.extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("release") {
                    artifactId = target.name
                    // This will be configured further when publication is needed
                    // For now, we set up the metadata

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
        }

        target.afterEvaluate {
            val component = target.components.findByName("release") ?: return@afterEvaluate
            target.extensions.findByType<PublishingExtension>()?.publications?.named("release", MavenPublication::class.java)?.configure {
                from(component)
            }
        }
    }
}
