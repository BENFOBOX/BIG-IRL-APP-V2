package uk.co.bigirl.sender

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class StreamBoxTelemetry(
    val online: Boolean = false,
    val connected: Boolean = false,
    val receivedKbps: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Double = 0.0,
    val nanoTemperatureC: Double = 0.0,
)

data class PhoneNetworkState(
    val wifiAvailable: Boolean = false,
    val cellularAvailable: Boolean = false,
    val activeTransport: String = "none",
)

suspend fun fetchStreamBoxTelemetry(): StreamBoxTelemetry = withContext(Dispatchers.IO) {
    val endpoints = listOf(
        "http://192.168.1.27:8082/status",
        "http://100.68.249.56:8082/status",
    )
    for (endpoint in endpoints) {
        try {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                connectTimeout = 700
                readTimeout = 700
                requestMethod = "GET"
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            return@withContext StreamBoxTelemetry(
                online = true,
                connected = json.optBoolean("connected", false),
                receivedKbps = json.optLong("received_kbps", 0),
                width = json.optInt("width", 0),
                height = json.optInt("height", 0),
                fps = json.optDouble("fps", 0.0),
                nanoTemperatureC = json.optDouble("temperature_c", 0.0),
            )
        } catch (_: Throwable) {
            // Try the next safe management route.
        }
    }
    StreamBoxTelemetry()
}

fun readPhoneNetworkState(context: Context): PhoneNetworkState {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    var wifi = false
    var cellular = false

    manager.allNetworks.forEach { network ->
        manager.getNetworkCapabilities(network)?.let { capabilities ->
            wifi = wifi || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            cellular = cellular || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        }
    }

    val activeCapabilities = manager.activeNetwork?.let(manager::getNetworkCapabilities)
    val activeTransport = when {
        activeCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
        activeCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
        else -> "none"
    }

    return PhoneNetworkState(
        wifiAvailable = wifi,
        cellularAvailable = cellular,
        activeTransport = activeTransport,
    )
}
