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
 * Exception thrown when the transport layer fails to authenticate the request,
 * even after attempting to refresh the token.
 *
 * This is a fatal error indicating that the client needs to re-authenticate
 * the user or obtain new credentials before retrying.
 */
class TaskBridgeAuthException(
    message: String = "Tokens expired or invalid, re-authorization required",
    cause: Throwable? = null,
) : RuntimeException(message, cause)
