package cc.cicare.sdkcall.utils

import android.content.Context
import android.net.*
import android.util.Log
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket

class NetworkObserver(
    private val context: Context,
    private val listener: (Boolean) -> Unit
) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch {
                delay(500)
                /*val hasInternet = hasInternetConnection()
                withContext(Dispatchers.Main) {
                    listener(hasInternet)
                }*/
            }
        }

        override fun onLost(network: Network) {
            scope.launch {
                /*
                for (i in 1..10) {
                    delay(1000)
                    if (hasInternetConnection()) {
                        hasInternet = true
                        break
                    }
                }
                */
                delay(5000) // delay 1.5 detik agar sempat pindah jaringan
                val hasInternet = hasInternetConnection()
                withContext(Dispatchers.Main) {
                    listener(hasInternet) // false jika benar-benar offline
                }
            }
        }

        override fun onUnavailable() {
            Log.i("NetworkObserver", "onUnavail")
            listener(false)
        }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, networkCallback)
        Log.d("NetworkObserver", "Started observing network state")
    }

    fun stop() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            Log.d("NetworkObserver", "Stopped observing network state")
        } catch (e: Exception) {
            Log.e("NetworkObserver", "Error unregistering network callback: ${e.message}")
        }
    }

    private fun hasInternetConnection(): Boolean {
        return try {
            Socket().use {
                it.connect(InetSocketAddress("8.8.8.8", 53), 1500)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
