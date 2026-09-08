package com.tdm.app.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.tdm.app.data.db.NetworkPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NetKind { WIFI, MOBILE, NONE }

/**
 * Network Monitor (spec §10): Any / Wi-Fi only / Mobile data only — mobile data is a first-class
 * citizen here, never an afterthought.
 */
class NetworkMonitor(private val context: Context) {

    private val _current = MutableStateFlow(NetKind.NONE)
    val current: StateFlow<NetKind> = _current.asStateFlow()

    fun refresh() { _current.value = detect() }

    private fun detect(): NetKind {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetKind.NONE
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return NetKind.NONE
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetKind.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetKind.MOBILE
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetKind.WIFI
            else -> NetKind.NONE
        }
    }

    fun satisfied(policy: NetworkPolicy): Boolean {
        refresh()
        return when (policy) {
            NetworkPolicy.ANY -> _current.value != NetKind.NONE
            NetworkPolicy.WIFI_ONLY -> _current.value == NetKind.WIFI
            NetworkPolicy.MOBILE_ONLY -> _current.value == NetKind.MOBILE
        }
    }
}
