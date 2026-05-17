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

internal class FakeTaskBridgeTransportFactory<Ctx>(
    private val http: TaskBridgeHttpApi<Ctx> = FakeTaskBridgeHttpApi(),
    private val webSocketFactory: WebSocketSessionFactory<Ctx> = WebSocketSessionFactory { _, _, _ -> error("WS not implemented in Fake") },
    private val sseSessionFactory: SseSessionFactory<Ctx> = SseSessionFactory { _, _, _, _ -> error("SSE not implemented in Fake") },
) : TaskBridgeTransportFactory<Ctx> {
    override fun create(config: TaskBridgeTransportFactoryConfig<Ctx>): TaskBridgeTransportBundle<Ctx> =
        TaskBridgeTransportBundle(
            http = http,
            webSocketFactory = webSocketFactory,
            sseSessionFactory = sseSessionFactory,
        )
}
