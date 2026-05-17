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
package io.github.nikkiw.taskbridge.model

import io.github.nikkiw.taskbridge.transport.taskBridgeJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WiringModelsTest {
    private val json = taskBridgeJson()

    @Test
    fun `toTaskEvent maps all known types correctly`() {
        val taskId = "task-1"
        val eventId = "evt-1"
        val createdAt = "2026-01-01T00:00:00Z"

        val types =
            mapOf(
                TaskEventType.TASK_STARTED.name to TaskStartedEvent::class,
                TaskEventType.TASK_PROGRESS.name to TaskProgressEvent::class,
                TaskEventType.TASK_MESSAGE.name to TaskMessageEvent::class,
                TaskEventType.TASK_COMPLETED.name to TaskCompletedEvent::class,
                TaskEventType.TASK_FAILED.name to TaskFailedEvent::class,
                TaskEventType.TASK_CANCELLED.name to TaskCancelledEvent::class,
            )

        types.forEach { (typeName, expectedClass) ->
            val envelope = RawTaskEventEnvelope(typeName, taskId, eventId, createdAt)
            val event = envelope.toTaskEvent()
            assertTrue(
                "Expected ${expectedClass.simpleName} for type $typeName but got ${event::class.simpleName}",
                expectedClass.isInstance(event),
            )
            assertEquals(taskId, event.taskId)
            assertEquals(eventId, event.eventId)
            assertEquals(createdAt, event.createdAt)
        }
    }

    @Test
    fun `toTaskEvent maps TASK_SUSPENDED correctly`() {
        val payload =
            JsonObject(
                mapOf(
                    "suspendId" to JsonPrimitive("susp-1"),
                    "kind" to JsonPrimitive("USER_ACTION_REQUIRED"),
                    "reasonCode" to JsonPrimitive("need_input"),
                    "allowedActions" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("approve"))),
                    "schemaVersion" to JsonPrimitive(1),
                ),
            )
        val envelope = RawTaskEventEnvelope(TaskEventType.TASK_SUSPENDED.name, "t1", "e1", "now", payload)
        val event = envelope.toTaskEvent() as TaskSuspendedEvent

        assertEquals("susp-1", event.suspension.suspendId)
        assertEquals(TaskSuspensionKind.USER_ACTION_REQUIRED, event.suspension.kind)
        assertEquals(1, event.suspension.schemaVersion)
    }

    @Test
    fun `toTaskEvent maps TASK_ACTION_ACCEPTED correctly`() {
        val payload =
            JsonObject(
                mapOf(
                    "suspendId" to JsonPrimitive("susp-1"),
                    "clientActionId" to JsonPrimitive("act-1"),
                    "actionType" to JsonPrimitive("approve"),
                    "acceptedAt" to JsonPrimitive("now"),
                ),
            )
        val envelope = RawTaskEventEnvelope(TaskEventType.TASK_ACTION_ACCEPTED.name, "t1", "e1", "now", payload)
        val event = envelope.toTaskEvent() as TaskActionAcceptedEvent

        assertEquals("susp-1", event.accepted.suspendId)
        assertEquals("act-1", event.accepted.clientActionId)
        assertEquals("approve", event.accepted.actionType)
    }

    @Test
    fun `toTaskEvent maps unknown types to UnknownTaskEvent`() {
        val envelope = RawTaskEventEnvelope("SOME_NEW_TYPE", "t1", "e1", "now")
        val event = envelope.toTaskEvent()
        assertTrue(event is UnknownTaskEvent)
        assertEquals("SOME_NEW_TYPE", event.wireType)
    }

    @Test
    fun `isTerminal returns true only for terminal events`() {
        val taskId = "t"
        val eventId = "e"
        val now = "n"

        assertTrue(TaskCompletedEvent(taskId, eventId, now).isTerminal())
        assertTrue(TaskFailedEvent(taskId, eventId, now).isTerminal())
        assertTrue(TaskCancelledEvent(taskId, eventId, now).isTerminal())

        assertFalse(TaskStartedEvent(taskId, eventId, now).isTerminal())
        assertFalse(TaskProgressEvent(taskId, eventId, now).isTerminal())
        assertFalse(TaskMessageEvent(taskId, eventId, now).isTerminal())
        assertFalse(UnknownTaskEvent("WIRE", taskId, eventId, now).isTerminal())
    }

    @Test
    fun `TaskCreateJsonRequest serialization`() {
        val request = TaskCreateJsonRequest("req-1", "type-a", JsonObject(mapOf("key" to JsonPrimitive("val"))))
        val encoded = json.encodeToString(request)
        assertTrue(encoded.contains("\"clientRequestId\":\"req-1\""))
        assertTrue(encoded.contains("\"taskType\":\"type-a\""))

        val decoded = json.decodeFromString<TaskCreateJsonRequest>(encoded)
        assertEquals(request, decoded)
    }

    @Test
    fun `TaskCreatedResponse serialization`() {
        val response = TaskCreatedResponse("task-1", TaskStatus.ACCEPTED, "req-1", true)
        val encoded = json.encodeToString(response)
        val decoded = json.decodeFromString<TaskCreatedResponse>(encoded)
        assertEquals(response, decoded)
    }

    @Test
    fun `PollEventsResponse serialization`() {
        val events =
            listOf(
                RawTaskEventEnvelope("TASK_STARTED", "t1", "e1", "now"),
                RawTaskEventEnvelope("TASK_COMPLETED", "t1", "e2", "later"),
            )
        val response = PollEventsResponse("t1", events, "e2", false)
        val encoded = json.encodeToString(response)
        val decoded = json.decodeFromString<PollEventsResponse>(encoded)
        assertEquals(response, decoded)
    }

    @Test
    fun `CancelTaskResponse serialization`() {
        val response = CancelTaskResponse("t1", CancelTaskStatus.CANCELLATION_REQUESTED)
        val encoded = json.encodeToString(response)
        val decoded = json.decodeFromString<CancelTaskResponse>(encoded)
        assertEquals(response, decoded)
    }

    @Test
    fun `TaskActionRequest serialization`() {
        val request = TaskActionRequest("act-1", "susp-1", "approve")
        val encoded = json.encodeToString(request)
        val decoded = json.decodeFromString<TaskActionRequest>(encoded)
        assertEquals(request, decoded)
    }

    @Test
    fun `PollEventsResponse serialization ignores rawJson`() {
        val events =
            listOf(
                RawTaskEventEnvelope("TASK_STARTED", "t1", "e1", "now", buildJsonObject {}),
            )
        val response = PollEventsResponse("t1", events, "e2", false)
        response.rawJson = "SHOULD_BE_IGNORED"

        val encoded = json.encodeToString(response)

        // Ensure rawJson is NOT in the encoded string
        assertFalse(encoded.contains("rawJson"))
        assertFalse(encoded.contains("SHOULD_BE_IGNORED"))

        val decoded = json.decodeFromString<PollEventsResponse>(encoded)
        assertEquals(null, decoded.rawJson)
        assertEquals("t1", decoded.taskId)
    }
}
