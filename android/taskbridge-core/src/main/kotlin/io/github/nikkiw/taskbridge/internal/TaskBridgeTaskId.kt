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
package io.github.nikkiw.taskbridge.internal

import java.nio.charset.StandardCharsets

private val TASK_BRIDGE_TASK_ID_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9._@-]{0,254}$")

private const val MAX_TASK_ID_UTF8_BYTES = 512

/**
 * Ensures [taskId] is safe for URL path segments and matches expected server identifiers.
 *
 * @throws IllegalArgumentException when blank, too long, or contains forbidden characters.
 */
fun requireValidTaskBridgeTaskId(taskId: String) {
    require(taskId.isNotBlank()) { "taskId must not be blank" }
    require(taskId.toByteArray(StandardCharsets.UTF_8).size <= MAX_TASK_ID_UTF8_BYTES) {
        "taskId UTF-8 encoding exceeds $MAX_TASK_ID_UTF8_BYTES bytes"
    }
    require(TASK_BRIDGE_TASK_ID_REGEX.matches(taskId)) {
        "taskId must match $TASK_BRIDGE_TASK_ID_REGEX"
    }
    require(!taskId.contains("/")) { "taskId must not contain '/'" }
    require(".." !in taskId) { "taskId must not contain '..'" }
}
