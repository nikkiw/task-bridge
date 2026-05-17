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

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskBridgeCheckpointStoreTest {
    @Test
    fun `normalizeTaskBridgeBaseUrl removes trailing slashes and whitespace`() {
        assertEquals("https://api.example.com", normalizeTaskBridgeBaseUrl("https://api.example.com"))
        assertEquals("https://api.example.com", normalizeTaskBridgeBaseUrl("https://api.example.com/"))
        assertEquals("https://api.example.com", normalizeTaskBridgeBaseUrl("https://api.example.com///"))
        assertEquals("https://api.example.com", normalizeTaskBridgeBaseUrl("  https://api.example.com  "))
        assertEquals("https://api.example.com", normalizeTaskBridgeBaseUrl("  https://api.example.com/  "))
    }

    @Test
    fun `buildCheckpointKey creates correct key with and without namespace`() {
        val baseUrl = "https://api.example.com/"
        val taskId = "task-1"

        // Without namespace
        assertEquals("|https://api.example.com|task-1", buildCheckpointKey(baseUrl, taskId))

        // With namespace
        assertEquals("user-123|https://api.example.com|task-1", buildCheckpointKey(baseUrl, taskId, "user-123"))
    }

    @Test
    fun `InMemoryTaskBridgeCheckpointStore basic operations`() =
        runTest {
            val store = InMemoryTaskBridgeCheckpointStore()
            val key = "test-key"

            assertNull(store.load(key))

            store.save(key, "1-0")
            assertEquals("1-0", store.load(key))

            store.save(key, "2-0")
            assertEquals("2-0", store.load(key))

            store.clear(key)
            assertNull(store.load(key))
        }

    @Test
    fun `InMemoryTaskBridgeCheckpointStore multiple keys`() =
        runTest {
            val store = InMemoryTaskBridgeCheckpointStore()
            val key1 = "key-1"
            val key2 = "key-2"

            store.save(key1, "v1")
            store.save(key2, "v2")

            assertEquals("v1", store.load(key1))
            assertEquals("v2", store.load(key2))

            store.clear(key1)
            assertNull(store.load(key1))
            assertEquals("v2", store.load(key2))
        }

    @Test
    fun `InMemoryTaskBridgeCheckpointStore clear non-existent key`() =
        runTest {
            val store = InMemoryTaskBridgeCheckpointStore()
            store.clear("non-existent") // Should not throw
            assertNull(store.load("non-existent"))
        }
}
