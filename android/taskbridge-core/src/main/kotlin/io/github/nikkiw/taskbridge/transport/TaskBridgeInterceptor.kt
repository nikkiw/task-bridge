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

/**
 * An interceptor that can observe or modify the transport components created by a [TaskBridgeTransportFactory].
 */
fun interface TaskBridgeInterceptor<Ctx> {
    /**
     * Intercepts and potentially modifies or wraps the transport components.
     *
     * @param bundle The original bundle of transport components (HTTP, WebSocket, SSE).
     * @return The intercepted (modified or wrapped) bundle of transport components.
     */
    fun intercept(bundle: TaskBridgeTransportBundle<Ctx>): TaskBridgeTransportBundle<Ctx>
}

/**
 * Wraps this factory with an interceptor.
 */
fun <Ctx> TaskBridgeTransportFactory<Ctx>.withInterceptor(interceptor: TaskBridgeInterceptor<Ctx>): TaskBridgeTransportFactory<Ctx> =
    TaskBridgeTransportFactory { config ->
        interceptor.intercept(this@withInterceptor.create(config))
    }
