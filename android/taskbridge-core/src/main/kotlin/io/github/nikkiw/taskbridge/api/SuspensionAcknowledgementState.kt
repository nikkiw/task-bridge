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
package io.github.nikkiw.taskbridge.api

import io.github.nikkiw.taskbridge.checkpoint.TaskBridgeCheckpointStore
import io.github.nikkiw.taskbridge.checkpoint.buildCheckpointKey
import io.github.nikkiw.taskbridge.checkpoint.normalizeTaskBridgeBaseUrl
import java.util.concurrent.ConcurrentHashMap

internal class SuspensionAcknowledgementState(
    private val checkpointStore: TaskBridgeCheckpointStore,
    private val baseUrl: String,
    private val namespace: String?,
) {
    private val knownSuspendIdsByTask = ConcurrentHashMap<String, MutableSet<String>>()
    private val normalizedBaseUrl = normalizeTaskBridgeBaseUrl(baseUrl)

    suspend fun remember(
        taskId: String,
        suspendId: String,
        clientActionId: String,
    ) {
        checkpointStore.save(key(taskId, suspendId), clientActionId)
        knownSuspendIdsByTask
            .computeIfAbsent(taskId) { ConcurrentHashMap.newKeySet() }
            .add(suspendId)
    }

    suspend fun isAcknowledged(
        taskId: String,
        suspendId: String,
    ): Boolean {
        val acknowledged = checkpointStore.load(key(taskId, suspendId)) != null
        if (acknowledged) {
            knownSuspendIdsByTask
                .computeIfAbsent(taskId) { ConcurrentHashMap.newKeySet() }
                .add(suspendId)
        }
        return acknowledged
    }

    suspend fun clear(
        taskId: String,
        suspendId: String,
    ) {
        checkpointStore.clear(key(taskId, suspendId))
        knownSuspendIdsByTask[taskId]?.remove(suspendId)
        if (knownSuspendIdsByTask[taskId].isNullOrEmpty()) {
            knownSuspendIdsByTask.remove(taskId)
        }
    }

    suspend fun clearTask(taskId: String) {
        val suspendIds = knownSuspendIdsByTask.remove(taskId).orEmpty()
        for (suspendId in suspendIds) {
            checkpointStore.clear(key(taskId, suspendId))
        }
    }

    private fun key(
        taskId: String,
        suspendId: String,
    ): String = buildCheckpointKey(normalizedBaseUrl, "$taskId|suspend-ack|$suspendId", namespace)
}
