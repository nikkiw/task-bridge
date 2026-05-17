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
package io.github.nikkiw.taskbridge.api

/**
 * Produces URL paths for all TaskBridge HTTP and WebSocket endpoints.
 *
 * Paths are resolved relative to [TaskBridgeConfig.baseUrl].
 * They must be path-only values: no scheme, authority, query string, or fragment.
 * Consumers implement this interface to plug their own routing scheme.
 */
interface TaskBridgeRouteResolver<Ctx> {
    /** Path to start a task with JSON input. */
    fun createTaskPath(context: Ctx): String

    /** Path to start a task with multipart input. Defaults to [createTaskPath]. */
    fun createTaskMultipartPath(context: Ctx): String = createTaskPath(context)

    /** Path to poll for task events. */
    fun pollEventsPath(
        context: Ctx,
        taskId: String,
    ): String

    /** Path to cancel a task. */
    fun cancelTaskPath(
        context: Ctx,
        taskId: String,
    ): String

    /** Path to submit a user action for a suspended task. */
    fun submitActionPath(
        context: Ctx,
        taskId: String,
    ): String

    /** Path to the WebSocket endpoint for event streaming. */
    fun webSocketPath(context: Ctx): String

    /** Path to the SSE endpoint for event streaming. */
    fun streamEventsPath(
        context: Ctx,
        taskId: String,
    ): String
}

/**
 * Default implementation of [TaskBridgeRouteResolver] using standard `api/v1/tasks` paths.
 */
class DefaultTaskBridgeRouteResolver<Ctx> : TaskBridgeRouteResolver<Ctx> {
    override fun createTaskPath(context: Ctx): String = "api/v1/tasks"

    override fun pollEventsPath(
        context: Ctx,
        taskId: String,
    ): String = "api/v1/tasks/$taskId/events"

    override fun cancelTaskPath(
        context: Ctx,
        taskId: String,
    ): String = "api/v1/tasks/$taskId/cancel"

    override fun submitActionPath(
        context: Ctx,
        taskId: String,
    ): String = "api/v1/tasks/$taskId/actions"

    override fun webSocketPath(context: Ctx): String = "api/v1/tasks/ws"

    override fun streamEventsPath(
        context: Ctx,
        taskId: String,
    ): String = "api/v1/tasks/$taskId/events/stream"
}
