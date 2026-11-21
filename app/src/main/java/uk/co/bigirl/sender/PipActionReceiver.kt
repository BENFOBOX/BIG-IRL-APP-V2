package uk.co.bigirl.sender

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PipActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_STOP_STREAM = "uk.co.bigirl.sender.PIP_STOP_STREAM"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_STOP_STREAM) return
        context.startService(
            Intent(context, StreamingService::class.java).apply {
                action = StreamingService.ACTION_KILL_STREAM
            },
        )
    }
}
