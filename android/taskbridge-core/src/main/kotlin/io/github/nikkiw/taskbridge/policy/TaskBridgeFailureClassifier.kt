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
import java.io.IOException

/**
 * Strategy for determining if a failure should trigger a retry attempt.
 */
fun interface TaskBridgeFailureClassifier {
    /** Returns true if the given error is considered transient and retryable. */
    fun isRetryable(throwable: Throwable): Boolean
}

/**
 * Exception thrown when an HTTP request fails with a non-2xx status code.
 *
 * @property statusCode The HTTP status code received from the server.
 */
class TaskBridgeHttpStatusException(
    val statusCode: Int,
    cause: Throwable? = null,
) : IOException("HTTP $statusCode", cause)

/**
 * Default implementation of [TaskBridgeFailureClassifier].
 *
 * Retries on network IO errors, HTTP 429 (Too Many Requests), and HTTP 5xx (Server Errors).
 *
 * Note: HTTP 401 (Unauthorized) is NOT classified as retryable here by default.
 * The transport layer performs its own limited retries
 * for 401 to allow for transient token refresh issues, but will eventually
 * treat it as a fatal error.
 */
class DefaultTaskBridgeFailureClassifier : TaskBridgeFailureClassifier {
    private companion object {
        const val TOO_MANY_REQUESTS = 429
        const val SERVER_ERROR_START = 500
    }

    override fun isRetryable(throwable: Throwable): Boolean =
        when (throwable) {
            is TaskBridgeHttpStatusException ->
                throwable.statusCode == TOO_MANY_REQUESTS ||
                    throwable.statusCode >= SERVER_ERROR_START
            is IOException -> true
            is SerializationException -> false
            else -> false
        }
}
