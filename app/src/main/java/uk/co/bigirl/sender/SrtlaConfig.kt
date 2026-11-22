package uk.co.bigirl.sender

import android.net.Uri
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal data class SrtlaConfig(
    val host: String,
    val port: Int,
    val streamId: String,
) {
    fun localSrtEndpoint(localPort: Int): String =
        Uri.Builder()
            .scheme("srt")
            .authority("127.0.0.1:$localPort")
            .appendQueryParameter("streamid", streamId)
            .build()
            .toString()

    companion object {
        fun parseEndpoint(endpoint: String): SrtlaConfig? =
            runCatching {
                val uri = URI(endpoint.trim())
                if (!uri.scheme.equals("srtla", ignoreCase = true)) return null
                val host = uri.host?.removeSurrounding("[", "]")
                    ?.takeIf(String::isNotBlank)
                    ?: return null
                val port = uri.port.takeIf { it in 1..65535 } ?: return null
                if (uri.rawUserInfo != null || uri.rawFragment != null || !uri.rawPath.isNullOrEmpty()) {
                    return null
                }
                val parameters = uri.rawQuery?.split('&') ?: return null
                if (parameters.any { !it.startsWith("streamid=") }) return null
                val streamId = parameters
                    .singleOrNull()
                    ?.substringAfter('=')
                    ?.let { URLDecoder.decode(it.replace("+", "%2B"), StandardCharsets.UTF_8.name()) }
                    ?.takeIf { it.isNotBlank() && it.length <= 512 && it.none(Char::isISOControl) }
                    ?: return null
                SrtlaConfig(host, port, streamId)
            }.getOrNull()
    }
}
