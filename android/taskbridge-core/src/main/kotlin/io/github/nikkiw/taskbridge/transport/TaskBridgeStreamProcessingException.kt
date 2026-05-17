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
 * Signals that an error occurred during event processing (e.g. checkpoint saving, flow emission),
 * not due to a transport/network failure.
 *
 * This exception is used to terminate the observation loop immediately without falling back
 * to other transports.
 */
internal class TaskBridgeStreamProcessingException(
    override val cause: Throwable,
) : RuntimeException("TaskBridge stream processing failed (not a transport error)", cause)
