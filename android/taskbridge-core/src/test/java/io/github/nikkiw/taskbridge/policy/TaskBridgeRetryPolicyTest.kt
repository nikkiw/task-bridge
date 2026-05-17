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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TaskBridgeRetryPolicyTest {
    @Test
    fun `delay follows exponential growth and caps at 5 seconds`() {
        // Use a Random that always returns 0 to test the base delay logic
        val mockRandom =
            object : Random() {
                override fun nextBits(bitCount: Int): Int = 0

                override fun nextDouble(): Double = 0.0
            }
        val policy = ExponentialBackoffTaskBridgeRetryPolicy(mockRandom)

        assertEquals(500L, policy.nextDelayMs(0)) // 500 * 2^0
        assertEquals(1000L, policy.nextDelayMs(1)) // 500 * 2^1
        assertEquals(2000L, policy.nextDelayMs(2)) // 500 * 2^2
        assertEquals(4000L, policy.nextDelayMs(3)) // 500 * 2^3
        assertEquals(5000L, policy.nextDelayMs(4)) // min(500 * 2^4, 5000)
        assertEquals(5000L, policy.nextDelayMs(5)) // capped at exponent 4
        assertEquals(5000L, policy.nextDelayMs(100))
    }

    @Test
    fun `delay includes up to 20 percent jitter`() {
        // Use a Random that always returns 0.999 to test the upper bound of jitter
        val mockRandom =
            object : Random() {
                override fun nextBits(bitCount: Int): Int = 0

                override fun nextDouble(): Double = 0.999999
            }
        val policy = ExponentialBackoffTaskBridgeRetryPolicy(mockRandom)

        // At attempt 0: base 500, max jitter ~100. Total ~600
        val d0 = policy.nextDelayMs(0)
        assertTrue("d0=$d0 should be slightly less than 600", d0 in 599..600)

        // At attempt 4: base 5000, max jitter ~1000. Total ~6000
        val d4 = policy.nextDelayMs(4)
        assertTrue("d4=$d4 should be slightly less than 6000", d4 in 5999..6000)
    }

    @Test
    fun `jitter is random`() {
        val policy = ExponentialBackoffTaskBridgeRetryPolicy()
        val results = (1..100).map { policy.nextDelayMs(1) }.toSet()
        // Base is 1000, Jitter is 0-200.
        assertTrue("Should have many different values, got ${results.size}", results.size > 10)
    }

    @Test
    fun `negative attempts are treated as zero`() {
        val mockRandom =
            object : Random() {
                override fun nextBits(bitCount: Int): Int = 0

                override fun nextDouble(): Double = 0.0
            }
        val policy = ExponentialBackoffTaskBridgeRetryPolicy(mockRandom)

        assertEquals(500L, policy.nextDelayMs(-1))
        assertEquals(500L, policy.nextDelayMs(-100))
    }
}
