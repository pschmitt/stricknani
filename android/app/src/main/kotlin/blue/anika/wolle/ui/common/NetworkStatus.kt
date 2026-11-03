package blue.anika.wolle.ui.common

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Tracks whether the device has a working (validated) internet connection, so a failed request can
 * tell "you're offline" apart from "the server didn't answer". Battery saver, Data Saver or a
 * metered network don't count as offline.
 */
object NetworkStatus {
    @Volatile private var validated: Boolean? = null

    /** True or false once [start] ran; null while unknown (e.g. in unit tests). */
    val hasValidatedInternet: Boolean?
        get() = validated

    fun start(context: Context) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
        validated = connectivity.activeNetwork.isValidated(connectivity)
        connectivity.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    validated = capabilities.isValidatedInternet()
                }

                override fun onLost(network: Network) {
                    validated = false
                }
            }
        )
    }

    private fun Network?.isValidated(connectivity: ConnectivityManager): Boolean =
        this != null && connectivity.getNetworkCapabilities(this)?.isValidatedInternet() == true

    private fun NetworkCapabilities.isValidatedInternet(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

/**
 * A network-level failure means "offline" unless the device demonstrably has working internet; then
 * the server (or the way to it) is the problem, which is reported as an error instead.
 */
fun ioFailureIsOffline(hasValidatedInternet: Boolean?): Boolean = hasValidatedInternet != true
