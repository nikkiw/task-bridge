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

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskBridgeTransportSourceTest {
    @Test
    fun `transport source constants have correct values`() {
        assertEquals(0, TaskBridgeTransportSource.WebSocket.value)
        assertEquals(1, TaskBridgeTransportSource.Sse.value)
        assertEquals(2, TaskBridgeTransportSource.Polling.value)
        assertEquals(-1, TaskBridgeTransportSource.Unknown.value)
    }

    @Test
    fun `toString returns correct string for known sources`() {
        assertEquals("ws", TaskBridgeTransportSource.WebSocket.toString())
        assertEquals("sse", TaskBridgeTransportSource.Sse.toString())
        assertEquals("poll", TaskBridgeTransportSource.Polling.toString())
        assertEquals("unknown(-1)", TaskBridgeTransportSource.Unknown.toString())
    }

    @Test
    fun `toString returns unknown for custom values`() {
        assertEquals("unknown(99)", TaskBridgeTransportSource(99).toString())
        assertEquals("unknown(-42)", TaskBridgeTransportSource(-42).toString())
    }
}
