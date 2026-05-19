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

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first

/**
 * An implementation of [TransportRetryGate] that suspends until active internet connection is available.
 *
 * It uses Android's [ConnectivityManager] callbacks inside a callback flow to wait for the system
 * to transition to a state where internet is accessible.
 *
 * @param context Android context used to retrieve the connectivity service.
 * @param requireValidatedInternet If true, additionally checks for [NetworkCapabilities.NET_CAPABILITY_VALIDATED]
 * which guarantees that the network has actual, verified access to the internet.
 */
class AndroidConnectivityRetryGate(
    context: Context,
    private val requireValidatedInternet: Boolean = true,
) : TransportRetryGate {

    private val appContext = context.applicationContext

    private val connectivityManager: ConnectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override suspend fun awaitRetryAllowed() {
        if (isOnline()) return

        callbackFlow {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (isOnline()) {
                        trySend(Unit)
                    }
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    if (networkCapabilities.hasUsableInternet()) {
                        trySend(Unit)
                    }
                }
            }

            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            connectivityManager.registerNetworkCallback(request, callback)

            // Re-check in case the network state changed during registration
            if (isOnline()) {
                trySend(Unit)
            }

            awaitClose {
                runCatching {
                    connectivityManager.unregisterNetworkCallback(callback)
                }
            }
        }.first()
    }

    private fun isOnline(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasUsableInternet()
    }

    private fun NetworkCapabilities.hasUsableInternet(): Boolean {
        val hasInternet = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isValidated = !requireValidatedInternet || hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return hasInternet && isValidated
    }
}
