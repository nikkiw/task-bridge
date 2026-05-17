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
package io.github.nikkiw.taskbridge.transport

import io.github.nikkiw.taskbridge.model.CancelTaskBody
import io.github.nikkiw.taskbridge.model.CancelTaskResponse
import io.github.nikkiw.taskbridge.model.PollEventsResponse
import io.github.nikkiw.taskbridge.model.SubmitActionResponse
import io.github.nikkiw.taskbridge.model.TaskActionRequest
import io.github.nikkiw.taskbridge.model.TaskBridgeMultipartAttachment
import io.github.nikkiw.taskbridge.model.TaskCreateJsonRequest
import io.github.nikkiw.taskbridge.model.TaskCreatedResponse

internal class FakeTaskBridgeHttpApi<Ctx>(
    private val pollHandler: (suspend (url: String, afterEventId: String?) -> PollEventsResponse)? = null,
    private val createHandler: (suspend (url: String, body: TaskCreateJsonRequest) -> TaskCreatedResponse)? = null,
    private val cancelHandler: (suspend (url: String, taskId: String) -> CancelTaskResponse)? = null,
) : TaskBridgeHttpApi<Ctx> {
    override suspend fun createTaskJson(
        context: Ctx,
        url: String,
        body: TaskCreateJsonRequest,
    ): TaskCreatedResponse = createHandler?.invoke(url, body) ?: error("createTaskJson not implemented in Fake")

    override suspend fun createTaskMultipart(
        context: Ctx,
        url: String,
        clientRequestId: String,
        taskType: String,
        inputJson: String?,
        metadataJson: String?,
        attachments: List<TaskBridgeMultipartAttachment>,
    ): TaskCreatedResponse {
        error("createTaskMultipart not implemented in Fake")
    }

    override suspend fun pollEvents(
        context: Ctx,
        url: String,
        afterEventId: String?,
        waitTimeoutMs: Int,
        maxEvents: Int,
    ): PollEventsResponse = pollHandler?.invoke(url, afterEventId) ?: error("pollEvents not implemented in Fake")

    override suspend fun cancelTask(
        context: Ctx,
        url: String,
        body: CancelTaskBody?,
    ): CancelTaskResponse {
        val taskId = url.split("/").lastOrNull { it.isNotBlank() && it != "cancel" } ?: "unknown"
        return cancelHandler?.invoke(url, taskId) ?: error("cancelTask not implemented in Fake")
    }

    override suspend fun submitAction(
        context: Ctx,
        url: String,
        body: TaskActionRequest,
    ): SubmitActionResponse {
        error("submitAction not implemented in Fake")
    }
}
