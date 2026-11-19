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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
    val preferences = remember {
        context.getSharedPreferences("big_irl_v2", Context.MODE_PRIVATE)
    }

    var endpoint by remember {
        mutableStateOf(
            preferences.getString(
                "endpoint",
                "srt://192.168.1.27:10000/live",
            ) ?: "srt://192.168.1.27:10000/live",
        )
    }
    var selectedProfile by remember {
        val saved = preferences.getString("profile", StreamProfile.MAXIMUM_1080P60.name)
        mutableStateOf(
            runCatching { StreamProfile.valueOf(saved.orEmpty()) }
                .getOrDefault(StreamProfile.MAXIMUM_1080P60),
        )
    }
    var profileMenuOpen by remember { mutableStateOf(false) }
    var permissionsGranted by remember { mutableStateOf(hasRequiredPermissions(context)) }
    var streamState by remember {
        mutableStateOf(service?.currentState() ?: StreamingService.StreamState(profile = selectedProfile))
    }
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
            service.prepareAndAttachPreview(preview, selectedProfile)
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
                        containerColor = when {
                            streamState.isStreaming -> Color(0xFF123A2D)
                            streamState.isReconnecting -> Color(0xFF3A2D12)
                            else -> Color(0xFF101D2B)
                        },
                    ),
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(streamState.message)
                        Text(
                            when {
                                streamState.isStreaming -> "LIVE • ${streamState.bitrateKbps} Kbps"
                                streamState.isReconnecting -> "RECONNECTING • 0 Kbps"
                                else -> "IDLE • 0 Kbps"
                            },
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
                                        service?.prepareAndAttachPreview(view, selectedProfile)
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
                        Button(onClick = { permissionLauncher.launch(requiredPermissions()) }) {
                            Text("Allow camera and microphone")
                        }
                    }
                }

                Box {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !streamState.wantsStreaming,
                        onClick = { profileMenuOpen = true },
                    ) {
                        Text(selectedProfile.label)
                    }
                    DropdownMenu(
                        expanded = profileMenuOpen,
                        onDismissRequest = { profileMenuOpen = false },
                    ) {
                        StreamProfile.entries.forEach { profile ->
                            DropdownMenuItem(
                                text = { Text(profile.label) },
                                onClick = {
                                    profileMenuOpen = false
                                    selectedProfile = profile
                                    preferences.edit().putString("profile", profile.name).apply()
                                    service?.selectProfile(profile)
                                },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = endpoint,
                    onValueChange = {
                        endpoint = it
                        preferences.edit().putString("endpoint", it).apply()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("SRT destination") },
                    supportingText = { Text("Required format: srt://ip:port/streamid") },
                    singleLine = true,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = permissionsGranted && service != null && streamState.isPrepared && !streamState.wantsStreaming,
                        onClick = { service?.startSrt(endpoint) },
                    ) { Text("START SRT") }

                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = streamState.wantsStreaming,
                        onClick = { service?.stopStream() },
                    ) { Text("STOP") }
                }

                Text("Active profile: ${streamState.profile.label}")
                Text(
                    "Streaming and automatic reconnect belong to the foreground service. Preview reattachment no longer overwrites live status.",
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
