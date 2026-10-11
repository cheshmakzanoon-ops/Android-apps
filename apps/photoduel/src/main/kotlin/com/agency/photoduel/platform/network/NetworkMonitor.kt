package com.agency.photoduel.platform.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

/**
 * Whether the device's default network can reach the internet, for the offline banner. Uses only
 * ACCESS_NETWORK_STATE.
 *
 * The signal is the INTERNET capability of the default network, not the system's VALIDATED probe: on
 * networks that block Google's connectivity check the probe stays false although the app works, and a
 * banner that wrongly says "offline" is worse than one that is late. A captive portal therefore still
 * reads as online; requests then fail with NETWORK errors that the UI already handles.
 *
 * The OS callback is registered only while someone collects [isOnline] (plus a short grace period),
 * and one callback serves every collector. If monitoring is impossible the value stays true, so the app
 * never claims to be offline without evidence.
 */
class NetworkMonitor(context: Context, scope: CoroutineScope) {
    private val connectivity: ConnectivityManager? =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    val isOnline: StateFlow<Boolean> = observe().stateIn(
        scope,
        SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        currentlyOnline(),
    )

    private fun currentlyOnline(): Boolean {
        val manager = connectivity ?: return true
        return try {
            val network = manager.activeNetwork ?: return false
            manager.getNetworkCapabilities(network)?.hasInternet() ?: false
        } catch (e: RuntimeException) {
            true
        }
    }

    private fun observe(): Flow<Boolean> = callbackFlow {
        val manager = connectivity
        if (manager == null) {
            trySend(true)
            awaitClose { }
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                trySend(networkCapabilities.hasInternet())
            }

            override fun onLost(network: Network) {
                trySend(false)
            }

            override fun onUnavailable() {
                trySend(false)
            }
        }
        var registered = false
        try {
            trySend(currentlyOnline())
            manager.registerDefaultNetworkCallback(callback)
            registered = true
        } catch (e: RuntimeException) {
            Log.w(TAG, "network callback unavailable (${e.javaClass.simpleName}); offline banner disabled")
            trySend(true)
        }
        awaitClose {
            if (registered) {
                try {
                    manager.unregisterNetworkCallback(callback)
                } catch (e: IllegalArgumentException) {
                    // Already unregistered.
                }
            }
        }
    }.distinctUntilChanged()

    private fun NetworkCapabilities.hasInternet(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

    private companion object {
        const val TAG = "PhotoDuelNetwork"
    }
}
