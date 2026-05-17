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
package io.github.nikkiw.taskbridge.checkpoint

import java.util.concurrent.ConcurrentHashMap

/**
 * Interface for persisting task progress (last processed event ID).
 *
 * Implementing this allows the client to resume task observation from the exact point it stopped,
 * even after app process death or manual restart.
 */
interface TaskBridgeCheckpointStore {
    /**
     * Loads the last event ID for the given key.
     */
    suspend fun load(key: String): String?

    /**
     * Persists the [lastEventId] for the given key.
     */
    suspend fun save(
        key: String,
        lastEventId: String,
    )

    /**
     * Removes the checkpoint associated with the given key.
     * Called when a task reaches a terminal state.
     */
    suspend fun clear(key: String)
}

/**
 * A simple in-memory implementation of [TaskBridgeCheckpointStore].
 *
 * Data is lost when the app process is terminated. Use this for short-lived tasks
 * or when persistence is managed elsewhere.
 */
class InMemoryTaskBridgeCheckpointStore : TaskBridgeCheckpointStore {
    private val values = ConcurrentHashMap<String, String>()

    override suspend fun load(key: String): String? = values[key]

    override suspend fun save(
        key: String,
        lastEventId: String,
    ) {
        values[key] = lastEventId
    }

    override suspend fun clear(key: String) {
        values.remove(key)
    }
}

internal fun normalizeTaskBridgeBaseUrl(baseUrl: String): String = baseUrl.trim().trimEnd('/')

/**
 * Constructs a unique key for storing task checkpoints, incorporating the base URL,
 * task ID, and an optional namespace to prevent collisions across different API instances
 * or environments.
 *
 * @param baseUrl The base URL of the API.
 * @param taskId The unique identifier of the task.
 * @param namespace An optional namespace to further qualify the key.
 * @return A string representing the unique checkpoint key.
 */
fun buildCheckpointKey(
    baseUrl: String,
    taskId: String,
    namespace: String? = null,
): String = listOf(namespace.orEmpty(), normalizeTaskBridgeBaseUrl(baseUrl), taskId).joinToString("|")
