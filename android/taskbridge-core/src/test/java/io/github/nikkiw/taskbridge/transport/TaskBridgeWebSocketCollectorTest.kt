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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskBridgeWebSocketCollectorTest {
    @Test
    fun `listener marks transport opened before subscribe side effects`() {
        val opened = CompletableDeferred<Unit>()
        val incoming = Channel<WsInbound>(capacity = 1)
        var openedVisibleInsideSubscribe = false

        val listener =
            createListener(
                incoming = incoming,
                opened = opened,
                signalStreamEnd = {},
                subscribeJson = {
                    openedVisibleInsideSubscribe = opened.isCompleted
                },
                onClosingSocket = {},
            )

        listener.onOpen(
            object : TaskBridgeWebSocketSession {
                override fun send(text: String): Boolean = true

                override fun close(
                    code: Int,
                    reason: String?,
                ): Boolean = true

                override fun cancel() = Unit
            },
        )

        assertTrue("subscribe callback should observe opened state", openedVisibleInsideSubscribe)
        assertTrue("opened should complete during onOpen", opened.isCompleted)
    }

    @Suppress("UNCHECKED_CAST")
    private fun createListener(
        incoming: Channel<WsInbound>,
        opened: CompletableDeferred<Unit>,
        signalStreamEnd: (Throwable?) -> Unit,
        subscribeJson: (TaskBridgeWebSocketSession) -> Unit,
        onClosingSocket: (TaskBridgeWebSocketSession) -> Unit,
    ): TaskBridgeWebSocketListener {
        val clazz = Class.forName("io.github.nikkiw.taskbridge.transport.TaskBridgeWebSocketCollectorKt")
        val method =
            clazz.getDeclaredMethod(
                "taskBridgeWebSocketListener",
                Channel::class.java,
                CompletableDeferred::class.java,
                kotlin.jvm.functions.Function1::class.java,
                kotlin.jvm.functions.Function1::class.java,
                kotlin.jvm.functions.Function1::class.java,
            )
        method.isAccessible = true
        return method.invoke(clazz, incoming, opened, signalStreamEnd, subscribeJson, onClosingSocket) as
            TaskBridgeWebSocketListener
    }
}
