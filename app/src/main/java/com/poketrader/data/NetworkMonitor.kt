package com.poketrader.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Counts how often the phone (re)gained a network connection. Images that failed while the
 * connection was down watch this and reload as soon as it changes.
 */
class NetworkMonitor(context: Context) {
    private val _reconnects = MutableStateFlow(0)
    val reconnects: StateFlow<Int> = _reconnects
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    /** On Wi-Fi (or another connection that isn't charged by the megabyte). */
    fun onUnmeteredNetwork(): Boolean = runCatching {
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(false)

    init {
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)
                ?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        _reconnects.value++
                    }
                })
        }
    }
}
