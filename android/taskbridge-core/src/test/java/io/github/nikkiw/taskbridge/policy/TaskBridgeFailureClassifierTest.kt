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
package io.github.nikkiw.taskbridge.policy

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

class TaskBridgeFailureClassifierTest {
    private val classifier = DefaultTaskBridgeFailureClassifier()

    @Test
    fun `HTTP status 429 is retryable`() {
        assertTrue(classifier.isRetryable(TaskBridgeHttpStatusException(429)))
    }

    @Test
    fun `HTTP status 500 and above are retryable`() {
        assertTrue(classifier.isRetryable(TaskBridgeHttpStatusException(500)))
        assertTrue(classifier.isRetryable(TaskBridgeHttpStatusException(502)))
        assertTrue(classifier.isRetryable(TaskBridgeHttpStatusException(503)))
        assertTrue(classifier.isRetryable(TaskBridgeHttpStatusException(504)))
    }

    @Test
    fun `HTTP status below 500 (except 429) are not retryable`() {
        assertFalse(classifier.isRetryable(TaskBridgeHttpStatusException(400)))
        assertFalse(classifier.isRetryable(TaskBridgeHttpStatusException(401)))
        assertFalse(classifier.isRetryable(TaskBridgeHttpStatusException(403)))
        assertFalse(classifier.isRetryable(TaskBridgeHttpStatusException(404)))
    }

    @Test
    fun `IOExceptions are retryable`() {
        assertTrue(classifier.isRetryable(IOException("generic io")))
        assertTrue(classifier.isRetryable(SocketTimeoutException("timeout")))
        assertTrue(classifier.isRetryable(ConnectException("connection refused")))
    }

    @Test
    fun `SerializationException is not retryable`() {
        assertFalse(classifier.isRetryable(SerializationException("invalid json")))
    }

    @Test
    fun `Other exceptions are not retryable`() {
        assertFalse(classifier.isRetryable(RuntimeException("runtime error")))
        assertFalse(classifier.isRetryable(NullPointerException("npe")))
        assertFalse(classifier.isRetryable(IllegalArgumentException("bad arg")))
    }

    @Test
    fun `TaskBridgeHttpStatusException message format`() {
        val ex = TaskBridgeHttpStatusException(404)
        assertEquals("HTTP 404", ex.message)
    }
}
