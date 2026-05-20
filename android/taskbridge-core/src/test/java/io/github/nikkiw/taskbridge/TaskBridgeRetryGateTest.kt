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
package io.github.nikkiw.taskbridge

import io.github.nikkiw.taskbridge.checkpoint.InMemoryTaskBridgeCheckpointStore
import io.github.nikkiw.taskbridge.policy.DefaultTaskBridgeFailureClassifier
import io.github.nikkiw.taskbridge.policy.ExponentialBackoffTaskBridgeRetryPolicy
import io.github.nikkiw.taskbridge.policy.NoOpTransportRetryGate
import io.github.nikkiw.taskbridge.policy.TaskBridgeRetryPolicy
import io.github.nikkiw.taskbridge.policy.TransportRetryGate
import io.github.nikkiw.taskbridge.transport.ObservationLoopVars
import io.github.nikkiw.taskbridge.transport.TransportBackoffContext
import io.github.nikkiw.taskbridge.transport.applyTransportBackoff
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class TaskBridgeRetryGateTest {

    @Test
    fun testRetryPolicyDoesNotBlock() {
        val policy = ExponentialBackoffTaskBridgeRetryPolicy()
        val delay = policy.nextDelayMs(0)
        assertTrue(delay >= 500L)
    }

    @Test
    fun testApplyTransportBackoffCallsRetryGate() = runTest {
        val fakeGate = FakeRetryGate()
        val context = TransportBackoffContext(
            checkpointStore = InMemoryTaskBridgeCheckpointStore(),
            failureClassifier = DefaultTaskBridgeFailureClassifier(),
            retryPolicy = TaskBridgeRetryPolicy { 0L },
            retryGate = fakeGate
        )
        val loopVars = ObservationLoopVars(attempt = 0, watermark = null)

        applyTransportBackoff(
            ctx = context,
            checkpointKey = "key",
            loop = loopVars,
            e = IOException("network failure")
        )

        assertTrue(fakeGate.called)
        assertEquals(1, loopVars.attempt)
    }

    @Test
    fun testCancellationDuringRetryGate() = runTest {
        val suspendingGate = SuspendingRetryGate()
        val context = TransportBackoffContext(
            checkpointStore = InMemoryTaskBridgeCheckpointStore(),
            failureClassifier = DefaultTaskBridgeFailureClassifier(),
            retryPolicy = TaskBridgeRetryPolicy { 10_000L },
            retryGate = suspendingGate
        )
        val loopVars = ObservationLoopVars(attempt = 0, watermark = null)

        val job = async {
            applyTransportBackoff(
                ctx = context,
                checkpointKey = "key",
                loop = loopVars,
                e = IOException("network failure")
            )
        }

        // Wait a bit to ensure it is suspended inside the gate
        delay(100)
        assertFalse(job.isCompleted)

        // Cancel the job
        job.cancelAndJoin()

        // It should complete with cancellation and not throw raw Exception or hang
        assertTrue(job.isCancelled)
    }

    @Test
    fun testNonRetryableFailureDoesNotCallRetryGate() = runTest {
        val fakeGate = FakeRetryGate()
        val context = TransportBackoffContext(
            checkpointStore = InMemoryTaskBridgeCheckpointStore(),
            failureClassifier = DefaultTaskBridgeFailureClassifier(),
            retryPolicy = TaskBridgeRetryPolicy { 0L },
            retryGate = fakeGate
        )
        val loopVars = ObservationLoopVars(attempt = 0, watermark = null)

        try {
            applyTransportBackoff(
                ctx = context,
                checkpointKey = "key",
                loop = loopVars,
                e = IllegalArgumentException("non-retryable error")
            )
            fail("Expected IllegalArgumentException to be rethrown")
        } catch (e: IllegalArgumentException) {
            // Success
        }

        assertFalse(fakeGate.called)
        assertEquals(0, loopVars.attempt)
    }

    @Test
    fun testCheckpointReloadAfterBackoff() = runTest {
        val store = InMemoryTaskBridgeCheckpointStore()
        store.save("key", "watermark-from-store")

        val context = TransportBackoffContext(
            checkpointStore = store,
            failureClassifier = DefaultTaskBridgeFailureClassifier(),
            retryPolicy = TaskBridgeRetryPolicy { 0L },
            retryGate = NoOpTransportRetryGate
        )
        val loopVars = ObservationLoopVars(attempt = 0, watermark = "old-watermark")

        applyTransportBackoff(
            ctx = context,
            checkpointKey = "key",
            loop = loopVars,
            e = IOException("network failure")
        )

        assertEquals("watermark-from-store", loopVars.watermark)
    }

    private class FakeRetryGate : TransportRetryGate {
        var called = false

        override suspend fun awaitRetryAllowed() {
            called = true
        }
    }

    private class SuspendingRetryGate : TransportRetryGate {
        override suspend fun awaitRetryAllowed() {
            delay(1_000_000L) // Suspend indefinitely
        }
    }
}
