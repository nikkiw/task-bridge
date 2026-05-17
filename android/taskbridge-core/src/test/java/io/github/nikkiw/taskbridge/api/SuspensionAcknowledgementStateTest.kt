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

import io.github.nikkiw.taskbridge.checkpoint.InMemoryTaskBridgeCheckpointStore
import io.github.nikkiw.taskbridge.checkpoint.buildCheckpointKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuspensionAcknowledgementStateTest {
    private val baseUrl = "https://api.example.com"
    private val namespace = "test-ns"

    @Test
    fun `remember saves to checkpoint store`() =
        runTest {
            val store = InMemoryTaskBridgeCheckpointStore()
            val state = SuspensionAcknowledgementState(store, baseUrl, namespace)
            val taskId = "task-1"
            val suspendId = "suspend-1"
            val clientActionId = "action-1"

            state.remember(taskId, suspendId, clientActionId)

            val key = buildCheckpointKey(baseUrl, "$taskId|suspend-ack|$suspendId", namespace)
            assertEquals(clientActionId, store.load(key))
        }

    @Test
    fun `isAcknowledged returns true if present in store`() =
        runTest {
            val store = InMemoryTaskBridgeCheckpointStore()
            val state = SuspensionAcknowledgementState(store, baseUrl, namespace)
            val taskId = "task-1"
            val suspendId = "suspend-1"
            val key = buildCheckpointKey(baseUrl, "$taskId|suspend-ack|$suspendId", namespace)

            assertFalse(state.isAcknowledged(taskId, suspendId))

            store.save(key, "action-1")
            assertTrue(state.isAcknowledged(taskId, suspendId))
        }

    @Test
    fun `clear removes from store and in-memory tracking`() =
        runTest {
            val store = InMemoryTaskBridgeCheckpointStore()
            val state = SuspensionAcknowledgementState(store, baseUrl, namespace)
            val taskId = "task-1"
            val suspendId = "suspend-1"
            val key = buildCheckpointKey(baseUrl, "$taskId|suspend-ack|$suspendId", namespace)

            state.remember(taskId, suspendId, "action-1")
            assertNotNull(store.load(key))

            state.clear(taskId, suspendId)
            assertNull(store.load(key))
            assertFalse(state.isAcknowledged(taskId, suspendId))
        }

    @Test
    fun `clearTask removes all task acknowledgements from store`() =
        runTest {
            val store = InMemoryTaskBridgeCheckpointStore()
            val state = SuspensionAcknowledgementState(store, baseUrl, namespace)
            val taskId = "task-1"
            val s1 = "suspend-1"
            val s2 = "suspend-2"
            val k1 = buildCheckpointKey(baseUrl, "$taskId|suspend-ack|$s1", namespace)
            val k2 = buildCheckpointKey(baseUrl, "$taskId|suspend-ack|$s2", namespace)

            // We need to use remember so they are in the in-memory map for clearTask to find them
            state.remember(taskId, s1, "a1")
            state.remember(taskId, s2, "a2")

            assertNotNull(store.load(k1))
            assertNotNull(store.load(k2))

            state.clearTask(taskId)

            assertNull(store.load(k1))
            assertNull(store.load(k2))
        }

    @Test
    fun `isAcknowledged populates in-memory tracking for later clearTask`() =
        runTest {
            val store = InMemoryTaskBridgeCheckpointStore()
            val state = SuspensionAcknowledgementState(store, baseUrl, namespace)
            val taskId = "task-1"
            val suspendId = "suspend-1"
            val key = buildCheckpointKey(baseUrl, "$taskId|suspend-ack|$suspendId", namespace)

            store.save(key, "action-1")

            // This should add suspendId to the in-memory map for taskId
            assertTrue(state.isAcknowledged(taskId, suspendId))

            state.clearTask(taskId)
            assertNull(store.load(key))
        }

    @Test
    fun `key construction handles url normalization and namespace`() =
        runTest {
            val store = InMemoryTaskBridgeCheckpointStore()
            // base URL without trailing slash
            val state = SuspensionAcknowledgementState(store, "https://api.example.com", "ns")
            val taskId = "t"
            val sId = "s"

            state.remember(taskId, sId, "a")

            // Expected key should use normalized URL (with trailing slash)
            val expectedKey = buildCheckpointKey("https://api.example.com/", "$taskId|suspend-ack|$sId", "ns")
            assertEquals("a", store.load(expectedKey))
        }
}
