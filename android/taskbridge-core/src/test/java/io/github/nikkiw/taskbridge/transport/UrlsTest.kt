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
import org.junit.Assert.assertThrows
import org.junit.Test

class UrlsTest {
    @Test
    fun `httpBaseToWebSocketUrl swaps scheme correctly`() {
        assertEquals("ws://api.example.com/tasks/ws", httpBaseToWebSocketUrl("http://api.example.com", "tasks/ws"))
        assertEquals("wss://api.example.com/tasks/ws", httpBaseToWebSocketUrl("https://api.example.com", "tasks/ws"))
    }

    @Test
    fun `httpBaseToWebSocketUrl preserves port and authority`() {
        assertEquals("ws://localhost:8080/tasks/ws", httpBaseToWebSocketUrl("http://localhost:8080", "tasks/ws"))
        assertEquals("wss://user:pass@api.example.com/tasks/ws", httpBaseToWebSocketUrl("https://user:pass@api.example.com", "tasks/ws"))
    }

    @Test
    fun `httpBaseToWebSocketUrl handles IPv6`() {
        assertEquals("ws://[::1]:8080/tasks/ws", httpBaseToWebSocketUrl("http://[::1]:8080", "tasks/ws"))
    }

    @Test
    fun `httpBaseToWebSocketUrl ignores path in httpBase`() {
        val ws = httpBaseToWebSocketUrl("https://api.example.com/v1/ignored", "tasks/ws")
        assertEquals("wss://api.example.com/tasks/ws", ws)
    }

    @Test
    fun `httpBaseToHttpUrl builds correct URL`() {
        assertEquals("https://api.example.com/v1/tasks", httpBaseToHttpUrl("https://api.example.com", "v1/tasks"))
        assertEquals("https://api.example.com/v1/tasks", httpBaseToHttpUrl("https://api.example.com/", "/v1/tasks"))
        assertEquals("https://api.example.com/base/v1/tasks", httpBaseToHttpUrl("https://api.example.com/base", "v1/tasks"))
    }

    @Test
    fun `relative path validation`() {
        // Leading slash is normalized away
        assertEquals("tasks", requireTaskBridgeRelativePath("/tasks"))
        assertEquals("tasks/sub", requireTaskBridgeRelativePath("  /tasks/sub  "))

        // Absolute URLs not allowed
        assertThrows(IllegalArgumentException::class.java) {
            requireTaskBridgeRelativePath("https://api.example.com/tasks")
        }

        // Blank paths not allowed
        assertThrows(IllegalArgumentException::class.java) {
            requireTaskBridgeRelativePath("  ")
        }

        // Authority, query, fragment not allowed
        assertThrows(IllegalArgumentException::class.java) {
            requireTaskBridgeRelativePath("//authority/path")
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireTaskBridgeRelativePath("path?query=1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireTaskBridgeRelativePath("path#fragment")
        }
    }
}
