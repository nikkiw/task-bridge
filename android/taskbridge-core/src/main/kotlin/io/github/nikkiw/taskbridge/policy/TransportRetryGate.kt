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

/**
 * Strategy for suspending retry attempts until an external condition is met.
 *
 * This can be used to delay retries while offline, during rate limiting,
 * or to coordinate with application lifecycle/state.
 */
interface TransportRetryGate {
    /**
     * Suspends the current coroutine until retry attempts are allowed.
     */
    suspend fun awaitRetryAllowed()
}

/**
 * A default implementation of [TransportRetryGate] that allows retries immediately.
 */
object NoOpTransportRetryGate : TransportRetryGate {
    override suspend fun awaitRetryAllowed() = Unit
}
