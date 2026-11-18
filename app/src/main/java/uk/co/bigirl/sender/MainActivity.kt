package uk.co.bigirl.sender

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private var service by mutableStateOf<StreamingService?>(null)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as StreamingService.LocalBinder).service()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BigIrlV2(service) }
    }

    fun startAndBindStreamingService() {
        val intent = Intent(this, StreamingService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        if (service != null) runCatching { unbindService(connection) }
        super.onDestroy()
    }
}

@Composable
private fun BigIrlV2(service: StreamingService?) {
    val context = LocalContext.current
    val activity = context as MainActivity

    var endpoint by remember { mutableStateOf("srt://192.168.1.27:10000") }
    var permissionsGranted by remember { mutableStateOf(hasRequiredPermissions(context)) }
    var streamState by remember { mutableStateOf(service?.currentState() ?: StreamingService.StreamState()) }
    var surfaceReady by remember { mutableStateOf<SurfaceView?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionsGranted = hasRequiredPermissions(context)
        if (permissionsGranted) activity.startAndBindStreamingService()
    }

    DisposableEffect(service) {
        val listener = object : StreamingService.Listener {
            override fun onStreamState(state: StreamingService.StreamState) {
                streamState = state
            }
        }
        service?.addListener(listener)
        onDispose { service?.removeListener(listener) }
    }

    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted && service == null) activity.startAndBindStreamingService()
    }

    LaunchedEffect(service, surfaceReady) {
        val preview = surfaceReady
        if (service != null && preview != null && preview.holder.surface.isValid) {
            service.prepareAndAttachPreview(preview)
        }
    }

    val colours = darkColorScheme(
        primary = Color(0xFF51E1A6),
        onPrimary = Color(0xFF002116),
        background = Color(0xFF071019),
        onBackground = Color.White,
        surface = Color(0xFF101D2B),
        onSurface = Color.White,
        surfaceVariant = Color(0xFF17283A),
        onSurfaceVariant = Color(0xFFD5E1ED),
    )

    MaterialTheme(colorScheme = colours) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("BIG IRL APP V2", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "SRT contribution sender • foreground-safe alpha",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (streamState.isStreaming) Color(0xFF123A2D) else Color(0xFF101D2B),
                    ),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(streamState.message)
                        Text(
                            "${if (streamState.isStreaming) "LIVE" else "IDLE"} • ${streamState.bitrateKbps} Kbps",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(Color.Black, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { currentContext ->
                            SurfaceView(currentContext).also { view ->
                                view.holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) {
                                        surfaceReady = view
                                    }

                                    override fun surfaceChanged(
                                        holder: SurfaceHolder,
                                        format: Int,
                                        width: Int,
                                        height: Int,
                                    ) {
                                        surfaceReady = view
                                        service?.updatePreviewSize(width, height)
                                        service?.prepareAndAttachPreview(view)
                                    }

                                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                                        service?.detachPreview()
                                        if (surfaceReady === view) surfaceReady = null
                                    }
                                })
                            }
                        },
                    )

                    if (!permissionsGranted) {
                        Button(onClick = {
                            permissionLauncher.launch(requiredPermissions())
                        }) {
                            Text("Allow camera and microphone")
                        }
                    }
                }

                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("SRT destination") },
                    supportingText = { Text("StreamBox LAN test: srt://192.168.1.27:10000") },
                    singleLine = true,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = permissionsGranted && service != null && streamState.isPrepared && !streamState.isStreaming,
                        onClick = { service?.startSrt(endpoint) },
                    ) { Text("START SRT") }

                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = streamState.isStreaming,
                        onClick = { service?.stopStream() },
                    ) { Text("STOP") }
                }

                Text("Test profile: H.264 720p30 at 3 Mbps • AAC 48 kHz stereo at 128 Kbps")
                Text(
                    "The stream belongs to a foreground service. Switching apps or replacing the preview surface must not end the broadcast.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

private fun hasRequiredPermissions(context: Context): Boolean {
    return arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
    ).all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}
