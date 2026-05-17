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

import kotlin.random.Random

/**
 * Policy for calculating the delay before the next retry attempt.
 */
fun interface TaskBridgeRetryPolicy {
    /** Returns the delay in milliseconds for the given [attempt] number (0-based). */
    fun nextDelayMs(attempt: Int): Long
}

/**
 * A retry policy using exponential backoff with jitter.
 *
 * @param random Random generator for jitter.
 */
class ExponentialBackoffTaskBridgeRetryPolicy(
    private val random: Random = Random.Default,
) : TaskBridgeRetryPolicy {
    private companion object {
        const val MAX_EXPONENT = 4
        const val BASE_DELAY_MS = 500L
        const val MAX_DELAY_MS = 5_000L
        const val JITTER_FACTOR = 0.2
    }

    override fun nextDelayMs(attempt: Int): Long {
        val cappedAttempt = attempt.coerceIn(0, MAX_EXPONENT)
        val baseDelay = minOf(BASE_DELAY_MS * (1L shl cappedAttempt), MAX_DELAY_MS)
        val jitter = (baseDelay * JITTER_FACTOR * random.nextDouble()).toLong()
        return baseDelay + jitter
    }
}
