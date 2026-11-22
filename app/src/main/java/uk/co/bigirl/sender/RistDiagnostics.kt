package uk.co.bigirl.sender

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object RistDiagnostics {
    private const val PREFS = "big_irl_v2_rist_diagnostics"
    private const val KEY = "trace"
    private const val MAX_CHARS = 24_000
    private val lock = Any()
    private val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun append(context: Context, message: String) = synchronized(lock) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val line = "${clock.format(Date())} | ${message.replace('\n', ' ').take(900)}"
        val next = (prefs.getString(KEY, "").orEmpty() + line + "\n").takeLast(MAX_CHARS)
        prefs.edit().putString(KEY, next).apply()
    }

    fun report(context: Context, state: StreamingService.StreamState): String = synchronized(lock) {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "").orEmpty()
        buildString {
            appendLine("BIG IRL APP V2 - RIST diagnostic")
            appendLine("Generated: ${clock.format(Date())}")
            appendLine("Mode: ${state.transportMode}")
            appendLine("Endpoint: ${state.endpoint}")
            appendLine("Prepared: ${state.isPrepared}")
            appendLine("Wants streaming: ${state.wantsStreaming}")
            appendLine("Streaming: ${state.isStreaming}")
            appendLine("Reconnecting: ${state.isReconnecting}")
            appendLine("RIST links: ${state.ristLinksConnected}")
            appendLine("Status: ${state.message}")
            appendLine()
            appendLine("Native trace:")
            appendLine(RistNative.diagnosticNative().orEmpty().ifBlank { "No native RIST trace recorded." })
            appendLine("App trace:")
            append(stored.ifBlank { "No app RIST trace recorded.\n" })
        }
    }
}
