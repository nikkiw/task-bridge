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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Test
import java.nio.file.Files

class DataStoreTaskBridgeCheckpointStoreTest {
    @Test
    fun `datastore checkpoint store persists values through a file`() =
        runTest {
            val file = Files.createTempFile("taskbridge-checkpoints", ".preferences_pb").toFile()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val store = DataStoreTaskBridgeCheckpointStore(file, scope)
            val key = buildCheckpointKey(baseUrl = "https://api.example.com", taskId = "task-1")

            store.save(key, "3-0")
            Assert.assertEquals("3-0", store.load(key))
            store.clear(key)
            Assert.assertEquals(null, store.load(key))
        }

    @Test
    fun `datastore checkpoint store isolates values by base url`() =
        runTest {
            val file = Files.createTempFile("taskbridge-checkpoints", ".preferences_pb").toFile()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val store = DataStoreTaskBridgeCheckpointStore(file, scope)
            val prod = buildCheckpointKey(baseUrl = "https://prod.example.com", taskId = "task-1")
            val stage = buildCheckpointKey(baseUrl = "https://stage.example.com", taskId = "task-1")

            store.save(prod, "3-0")

            Assert.assertEquals("3-0", store.load(prod))
            Assert.assertEquals(null, store.load(stage))
        }
}
