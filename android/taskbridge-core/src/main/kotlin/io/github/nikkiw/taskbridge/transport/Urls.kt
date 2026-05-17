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

import java.net.URI

private const val STANDARD_WS_PORT = 80
private const val STANDARD_WSS_PORT = 443

internal fun requireTaskBridgeRelativePath(path: String): String {
    val trimmed = path.trim()
    require(trimmed.isNotEmpty()) { "TaskBridge route path must not be blank" }

    val parsed = URI(trimmed)
    require(!parsed.isAbsolute) { "TaskBridge route path must be relative, got absolute URL: $trimmed" }
    require(parsed.rawAuthority == null) { "TaskBridge route path must not include an authority: $trimmed" }
    require(parsed.rawQuery == null) { "TaskBridge route path must not include a query string: $trimmed" }
    require(parsed.rawFragment == null) { "TaskBridge route path must not include a fragment: $trimmed" }

    return trimmed.trimStart('/')
}

/**
 * Builds a WebSocket URL by combining [httpBase] with [path].
 *
 * OkHttp `HttpUrl.Builder` only allows http/https schemes,
 * so this returns a full `ws://` / `wss://` string suitable for `okhttp3.Request.Builder.url`.
 */
fun httpBaseToWebSocketUrl(
    httpBase: String,
    path: String,
): String {
    val normalizedBase = normalizeHttpBase(httpBase)
    val base = URI(normalizedBase)
    val wsScheme = webSocketScheme(base)
    val portPart = webSocketPortPart(base, wsScheme)
    val userInfo = webSocketUserInfo(base)
    val hostForAuthority = webSocketAuthorityHost(base)
    val normalizedPath = requireTaskBridgeRelativePath(path)
    return "$wsScheme://$userInfo$hostForAuthority$portPart/$normalizedPath"
}

/**
 * Builds an absolute HTTP(S) URL by combining [httpBase] with relative [path].
 */
fun httpBaseToHttpUrl(
    httpBase: String,
    path: String,
): String {
    val base = URI(normalizeHttpBase(httpBase))
    val normalizedPath = requireTaskBridgeRelativePath(path)
    return base.resolve(normalizedPath).toASCIIString()
}

private fun normalizeHttpBase(httpBase: String): String {
    val trimmed = httpBase.trim()
    require(trimmed.isNotEmpty()) { "TaskBridge base URL must not be blank" }
    return if (trimmed.endsWith('/')) trimmed else "$trimmed/"
}

private fun webSocketScheme(base: URI): String =
    when (base.scheme.lowercase()) {
        "http" -> "ws"
        "https" -> "wss"
        else -> error("Unsupported URL scheme ${base.scheme} (expected http/https)")
    }

private fun webSocketPortPart(
    base: URI,
    wsScheme: String,
): String {
    if (base.port == -1) {
        return ""
    }
    val defaultPort =
        when (wsScheme) {
            "ws" -> STANDARD_WS_PORT
            "wss" -> STANDARD_WSS_PORT
            else -> error("unexpected wsScheme")
        }
    return if (base.port != defaultPort) ":${base.port}" else ""
}

private fun webSocketUserInfo(base: URI): String =
    if (base.rawUserInfo.isNullOrEmpty()) {
        ""
    } else {
        "${base.rawUserInfo}@"
    }

private fun webSocketAuthorityHost(base: URI): String {
    val host = base.host ?: error("HTTP base URL must include host")
    return if (':' in host && '[' !in host) {
        "[$host]"
    } else {
        host
    }
}
