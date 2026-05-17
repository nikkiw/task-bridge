/*
 * Copyright 2026 Nikolay Vlasov (https://github.com/nikkiw)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.nikkiw.taskbridge.samples

import io.github.nikkiw.taskbridge.api.TaskBridgeClient
import io.github.nikkiw.taskbridge.api.TaskBridgeConfig
import io.github.nikkiw.taskbridge.api.observeTaskEvents
import io.github.nikkiw.taskbridge.api.startTaskJson
import io.github.nikkiw.taskbridge.model.TaskActionRequest
import io.github.nikkiw.taskbridge.model.TaskCompletedEvent
import io.github.nikkiw.taskbridge.model.TaskCreateJsonRequest
import io.github.nikkiw.taskbridge.model.TaskProgressEvent
import io.github.nikkiw.taskbridge.model.TaskSuspendedEvent
import io.github.nikkiw.taskbridge.okhttp.OkHttpTaskBridgeTransportConfig
import io.github.nikkiw.taskbridge.okhttp.OkHttpTaskBridgeTransportFactory
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/**
 * Samples for KDoc.
 */
@Suppress("unused", "MagicNumber")
internal class TaskBridgeClientSamples {
    fun createClientExample() {
        val client =
            TaskBridgeClient.create(
                TaskBridgeConfig(
                    baseUrl = "https://api.example.com",
                    transportFactory =
                        OkHttpTaskBridgeTransportFactory<Unit>(
                            OkHttpTaskBridgeTransportConfig(okHttpClient = OkHttpClient()),
                        ),
                    authHeaderProvider = { _, _ -> "Bearer your-token" },
                ),
            )
    }

    fun startTaskExample(client: TaskBridgeClient<Unit>) =
        runBlocking {
            val response =
                client.startTaskJson(
                    TaskCreateJsonRequest(
                        clientRequestId = "unique-req-123",
                        taskType = "document.review",
                        input =
                            buildJsonObject {
                                put("documentId", "doc-123")
                            },
                    ),
                )
            println("Started task: ${response.taskId}")
        }

    fun observeEventsExample(
        client: TaskBridgeClient<Unit>,
        taskId: String,
    ) = runBlocking {
        client.observeTaskEvents(taskId).collect { event ->
            when (event) {
                is TaskProgressEvent -> {
                    println("Progress: ${event.payload["progress"]}%")
                }
                is TaskCompletedEvent -> {
                    println("Task finished successfully!")
                }
                else -> {
                    println("Other event: $event")
                }
            }
        }
    }

    fun submitActionExample(
        client: TaskBridgeClient<Unit>,
        taskId: String,
        suspendEvent: TaskSuspendedEvent,
    ) = runBlocking {
        client.submitAction(
            context = Unit,
            taskId = taskId,
            action =
                TaskActionRequest(
                    clientActionId = "act-xyz",
                    suspendId = suspendEvent.suspension.suspendId,
                    actionType = "approve",
                    payload = buildJsonObject { put("comment", "Looks good") },
                ),
        )
    }
}
