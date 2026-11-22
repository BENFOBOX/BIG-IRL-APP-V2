package uk.co.bigirl.sender

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.util.Rational
import android.os.IBinder
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

private const val LEGACY_LAN_ENDPOINT = "srt://192.168.1.27:10000/live"

class MainActivity : ComponentActivity() {
    private var pipMode by mutableStateOf(false)
    private var service by mutableStateOf<StreamingService?>(null)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as StreamingService.LocalBinder).service().also { it.setAppForeground(true) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BigIrlV2(service, pipMode) }
    }

    override fun onStart() {
        super.onStart()
        service?.setAppForeground(true)
    }

    override fun onStop() {
        service?.setAppForeground(false)
        super.onStop()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (service?.currentState()?.wantsStreaming == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val stopIntent = Intent(this, PipActionReceiver::class.java).apply {
                action = PipActionReceiver.ACTION_STOP_STREAM
            }
            val stopPending = PendingIntent.getBroadcast(
                this, 3001, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val stopAction = RemoteAction(
                Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                "Stop stream", "Stop stream", stopPending,
            )
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .setActions(listOf(stopAction))
                    .build(),
            )
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        pipMode = isInPictureInPictureMode
        service?.setAppForeground(!isInPictureInPictureMode)
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
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
private fun BigIrlV2(service: StreamingService?, pipMode: Boolean) {
    val context = LocalContext.current
    val activity = context as MainActivity
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val preferences = remember {
        context.getSharedPreferences("big_irl_v2", Context.MODE_PRIVATE)
    }

    var selectedTransport by remember {
        val saved = preferences.getString("transport", TransportMode.SINGLE_SRT.name)
        mutableStateOf(
            runCatching { TransportMode.valueOf(saved.orEmpty()) }
                .getOrDefault(TransportMode.SINGLE_SRT),
        )
    }
    var endpoint by remember(selectedTransport) {
        val savedEndpoint = preferences.getString("endpoint_${selectedTransport.name.lowercase()}", null)
        val legacyEndpoint = preferences.getString("endpoint", null)
        val initialEndpoint = savedEndpoint ?: when (selectedTransport) {
            TransportMode.SINGLE_SRT -> legacyEndpoint
                ?.takeUnless { it == LEGACY_LAN_ENDPOINT }
                ?: selectedTransport.defaultEndpoint
            TransportMode.RIST -> selectedTransport.defaultEndpoint
            TransportMode.BONDED_SRT -> selectedTransport.defaultEndpoint
            TransportMode.SRTLA -> selectedTransport.defaultEndpoint
            TransportMode.RIST_MULTIPATH -> selectedTransport.defaultEndpoint
        }
        mutableStateOf(initialEndpoint)
    }
    var selectedProfile by remember {
        val saved = preferences.getString("profile", StreamProfile.MAXIMUM_1080P60.name)
        mutableStateOf(
            runCatching { StreamProfile.valueOf(saved.orEmpty()) }
                .getOrDefault(StreamProfile.MAXIMUM_1080P60),
        )
    }
    var controlsOnLeft by remember {
        mutableStateOf(preferences.getBoolean("controls_on_left", false))
    }
    var profileMenuOpen by remember { mutableStateOf(false) }
    var transportMenuOpen by remember { mutableStateOf(false) }
    var permissionsGranted by remember { mutableStateOf(hasRequiredPermissions(context)) }
    var streamState by remember {
        mutableStateOf(
            service?.currentState()
                ?: StreamingService.StreamState(profile = selectedProfile),
        )
    }
    var surfaceReady by remember { mutableStateOf<SurfaceView?>(null) }
    var streamBoxTelemetry by remember { mutableStateOf(StreamBoxTelemetry()) }
    var phoneNetworkState by remember { mutableStateOf(readPhoneNetworkState(context)) }

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
        if (permissionsGranted && service == null) {
            activity.startAndBindStreamingService()
        }
    }

    LaunchedEffect(service, surfaceReady, isLandscape) {
        val preview = surfaceReady
        if (service != null && preview != null && preview.holder.surface.isValid) {
            delay(120)
            if (preview.holder.surface.isValid) {
                service.prepareAndAttachPreview(preview, selectedProfile)
            }
        }
    }

    LaunchedEffect(service, selectedTransport) {
        if (service != null && service.currentState().transportMode != selectedTransport) {
            service.selectTransport(selectedTransport)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            phoneNetworkState = readPhoneNetworkState(context)
            streamBoxTelemetry = fetchStreamBoxTelemetry()
            delay(1_000)
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
            if (pipMode) {
                PreviewPanel(
                    modifier = Modifier.fillMaxSize(),
                    service = service,
                    permissionsGranted = permissionsGranted,
                    selectedProfile = selectedProfile,
                    streamState = streamState,
                    streamBoxTelemetry = streamBoxTelemetry,
                    phoneNetworkState = phoneNetworkState,
                    onSurfaceReady = { surfaceReady = it },
                    onPermissionRequest = { permissionLauncher.launch(requiredPermissions()) },
                )
            } else if (isLandscape) {
                LandscapeStudio(
                    service = service,
                    permissionsGranted = permissionsGranted,
                    streamState = streamState,
                    selectedProfile = selectedProfile,
                    endpoint = endpoint,
                    selectedTransport = selectedTransport,
                    transportMenuOpen = transportMenuOpen,
                    profileMenuOpen = profileMenuOpen,
                    controlsOnLeft = controlsOnLeft,
                    streamBoxTelemetry = streamBoxTelemetry,
                    phoneNetworkState = phoneNetworkState,
                    onSurfaceReady = { surfaceReady = it },
                    onPermissionRequest = { permissionLauncher.launch(requiredPermissions()) },
                    onEndpointChange = {
                        endpoint = it
                        preferences.edit()
                            .putString("endpoint_${selectedTransport.name.lowercase()}", it)
                            .apply()
                    },
                    onTransportMenuChange = { transportMenuOpen = it },
                    onTransportSelected = {
                        selectedTransport = it
                        preferences.edit().putString("transport", it.name).apply()
                        service?.selectTransport(it)
                    },
                    onProfileMenuChange = { profileMenuOpen = it },
                    onProfileSelected = {
                        selectedProfile = it
                        preferences.edit().putString("profile", it.name).apply()
                        service?.selectProfile(it)
                    },
                    onControlsSideChange = {
                        controlsOnLeft = it
                        preferences.edit().putBoolean("controls_on_left", it).apply()
                    },
                )
            } else {
                PortraitStudio(
                    service = service,
                    permissionsGranted = permissionsGranted,
                    streamState = streamState,
                    selectedProfile = selectedProfile,
                    endpoint = endpoint,
                    selectedTransport = selectedTransport,
                    transportMenuOpen = transportMenuOpen,
                    profileMenuOpen = profileMenuOpen,
                    controlsOnLeft = controlsOnLeft,
                    streamBoxTelemetry = streamBoxTelemetry,
                    phoneNetworkState = phoneNetworkState,
                    onSurfaceReady = { surfaceReady = it },
                    onPermissionRequest = { permissionLauncher.launch(requiredPermissions()) },
                    onEndpointChange = {
                        endpoint = it
                        preferences.edit()
                            .putString("endpoint_${selectedTransport.name.lowercase()}", it)
                            .apply()
                    },
                    onTransportMenuChange = { transportMenuOpen = it },
                    onTransportSelected = {
                        selectedTransport = it
                        preferences.edit().putString("transport", it.name).apply()
                        service?.selectTransport(it)
                    },
                    onProfileMenuChange = { profileMenuOpen = it },
                    onProfileSelected = {
                        selectedProfile = it
                        preferences.edit().putString("profile", it.name).apply()
                        service?.selectProfile(it)
                    },
                    onControlsSideChange = {
                        controlsOnLeft = it
                        preferences.edit().putBoolean("controls_on_left", it).apply()
                    },
                )
            }
        }
    }
}

@Composable
private fun LandscapeStudio(
    service: StreamingService?,
    permissionsGranted: Boolean,
    streamState: StreamingService.StreamState,
    selectedProfile: StreamProfile,
    endpoint: String,
    selectedTransport: TransportMode,
    transportMenuOpen: Boolean,
    profileMenuOpen: Boolean,
    controlsOnLeft: Boolean,
    streamBoxTelemetry: StreamBoxTelemetry,
    phoneNetworkState: PhoneNetworkState,
    onSurfaceReady: (SurfaceView?) -> Unit,
    onPermissionRequest: () -> Unit,
    onEndpointChange: (String) -> Unit,
    onTransportMenuChange: (Boolean) -> Unit,
    onTransportSelected: (TransportMode) -> Unit,
    onProfileMenuChange: (Boolean) -> Unit,
    onProfileSelected: (StreamProfile) -> Unit,
    onControlsSideChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (controlsOnLeft) {
            ControlPanel(
                modifier = Modifier.width(330.dp).fillMaxHeight(),
                service = service,
                permissionsGranted = permissionsGranted,
                streamState = streamState,
                selectedProfile = selectedProfile,
                endpoint = endpoint,
                selectedTransport = selectedTransport,
                transportMenuOpen = transportMenuOpen,
                profileMenuOpen = profileMenuOpen,
                controlsOnLeft = controlsOnLeft,
                onEndpointChange = onEndpointChange,
                onTransportMenuChange = onTransportMenuChange,
                onTransportSelected = onTransportSelected,
                onProfileMenuChange = onProfileMenuChange,
                onProfileSelected = onProfileSelected,
                onControlsSideChange = onControlsSideChange,
                scrollable = true,
            )
        }

        PreviewPanel(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            service = service,
            permissionsGranted = permissionsGranted,
            selectedProfile = selectedProfile,
            streamState = streamState,
            streamBoxTelemetry = streamBoxTelemetry,
            phoneNetworkState = phoneNetworkState,
            onSurfaceReady = onSurfaceReady,
            onPermissionRequest = onPermissionRequest,
        )

        if (!controlsOnLeft) {
            ControlPanel(
                modifier = Modifier.width(330.dp).fillMaxHeight(),
                service = service,
                permissionsGranted = permissionsGranted,
                streamState = streamState,
                selectedProfile = selectedProfile,
                endpoint = endpoint,
                selectedTransport = selectedTransport,
                transportMenuOpen = transportMenuOpen,
                profileMenuOpen = profileMenuOpen,
                controlsOnLeft = controlsOnLeft,
                onEndpointChange = onEndpointChange,
                onTransportMenuChange = onTransportMenuChange,
                onTransportSelected = onTransportSelected,
                onProfileMenuChange = onProfileMenuChange,
                onProfileSelected = onProfileSelected,
                onControlsSideChange = onControlsSideChange,
                scrollable = true,
            )
        }
    }
}

@Composable
private fun PortraitStudio(
    service: StreamingService?,
    permissionsGranted: Boolean,
    streamState: StreamingService.StreamState,
    selectedProfile: StreamProfile,
    endpoint: String,
    selectedTransport: TransportMode,
    transportMenuOpen: Boolean,
    profileMenuOpen: Boolean,
    controlsOnLeft: Boolean,
    streamBoxTelemetry: StreamBoxTelemetry,
    phoneNetworkState: PhoneNetworkState,
    onSurfaceReady: (SurfaceView?) -> Unit,
    onPermissionRequest: () -> Unit,
    onEndpointChange: (String) -> Unit,
    onTransportMenuChange: (Boolean) -> Unit,
    onTransportSelected: (TransportMode) -> Unit,
    onProfileMenuChange: (Boolean) -> Unit,
    onProfileSelected: (StreamProfile) -> Unit,
    onControlsSideChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProfileSelector(
            selectedProfile = selectedProfile,
            profileMenuOpen = profileMenuOpen,
            enabled = !streamState.wantsStreaming,
            onProfileMenuChange = onProfileMenuChange,
            onProfileSelected = onProfileSelected,
        )

        PreviewPanel(
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            service = service,
            permissionsGranted = permissionsGranted,
            selectedProfile = selectedProfile,
            streamState = streamState,
            streamBoxTelemetry = streamBoxTelemetry,
            phoneNetworkState = phoneNetworkState,
            onSurfaceReady = onSurfaceReady,
            onPermissionRequest = onPermissionRequest,
        )

        ControlPanel(
            modifier = Modifier.fillMaxWidth(),
            service = service,
            permissionsGranted = permissionsGranted,
            streamState = streamState,
            selectedProfile = selectedProfile,
            endpoint = endpoint,
            selectedTransport = selectedTransport,
            transportMenuOpen = transportMenuOpen,
            profileMenuOpen = profileMenuOpen,
            controlsOnLeft = controlsOnLeft,
            onEndpointChange = onEndpointChange,
            onTransportMenuChange = onTransportMenuChange,
            onTransportSelected = onTransportSelected,
            onProfileMenuChange = onProfileMenuChange,
            onProfileSelected = onProfileSelected,
            onControlsSideChange = onControlsSideChange,
            scrollable = false,
        )
    }
}

@Composable
private fun PreviewPanel(
    modifier: Modifier,
    service: StreamingService?,
    permissionsGranted: Boolean,
    selectedProfile: StreamProfile,
    streamState: StreamingService.StreamState,
    streamBoxTelemetry: StreamBoxTelemetry,
    phoneNetworkState: PhoneNetworkState,
    onSurfaceReady: (SurfaceView?) -> Unit,
    onPermissionRequest: () -> Unit,
) {
    val currentContext = LocalContext.current
    Box(
        modifier = modifier.background(Color.Black, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { currentContext ->
                SurfaceView(currentContext).also { view ->
                    view.holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            onSurfaceReady(view)
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) {
                            service?.updatePreviewSize(width, height)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            service?.detachPreview()
                            onSurfaceReady(null)
                        }
                    })
                }
            },
        )

        SenderOverlay(
            modifier = Modifier.align(Alignment.TopStart),
            streamState = streamState,
            network = phoneNetworkState,
        )

        ReceiverOverlay(
            modifier = Modifier.align(Alignment.TopEnd),
            telemetry = streamBoxTelemetry,
            streamState = streamState,
        )

        TorchOverlay(
            modifier = Modifier.align(Alignment.BottomStart),
            torchEnabled = streamState.torchEnabled,
            enabled = permissionsGranted && service != null && streamState.isPrepared,
            onClick = { service?.toggleTorch() },
        )

        if (!permissionsGranted) {
            Button(onClick = onPermissionRequest) {
                Text("Allow camera and microphone")
            }
        }
    }
}

@Composable
private fun ControlPanel(
    modifier: Modifier,
    service: StreamingService?,
    permissionsGranted: Boolean,
    streamState: StreamingService.StreamState,
    selectedProfile: StreamProfile,
    endpoint: String,
    selectedTransport: TransportMode,
    transportMenuOpen: Boolean,
    profileMenuOpen: Boolean,
    controlsOnLeft: Boolean,
    onEndpointChange: (String) -> Unit,
    onTransportMenuChange: (Boolean) -> Unit,
    onTransportSelected: (TransportMode) -> Unit,
    onProfileMenuChange: (Boolean) -> Unit,
    onProfileSelected: (StreamProfile) -> Unit,
    onControlsSideChange: (Boolean) -> Unit,
    scrollable: Boolean,
) {
    val panelModifier = if (scrollable) {
        modifier.verticalScroll(rememberScrollState())
    } else {
        modifier
    }

    Column(
        modifier = panelModifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { onControlsSideChange(!controlsOnLeft) },
        ) {
            Text(if (controlsOnLeft) "Controls: LEFT" else "Controls: RIGHT")
        }

        if (scrollable) {
            ProfileSelector(
                selectedProfile = selectedProfile,
                profileMenuOpen = profileMenuOpen,
                enabled = !streamState.wantsStreaming,
                onProfileMenuChange = onProfileMenuChange,
                onProfileSelected = onProfileSelected,
            )
        }

        TransportSelector(
            selectedTransport = selectedTransport,
            menuOpen = transportMenuOpen,
            enabled = !streamState.wantsStreaming,
            onMenuChange = onTransportMenuChange,
            onTransportSelected = onTransportSelected,
        )

        OutlinedTextField(
            value = endpoint,
            onValueChange = onEndpointChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(selectedTransport.destinationLabel) },
            supportingText = { Text(selectedTransport.destinationHelp) },
            singleLine = true,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                modifier = Modifier.weight(1f),
                enabled = permissionsGranted && service != null &&
                    streamState.isPrepared && !streamState.wantsStreaming,
                onClick = { service?.startTransport(selectedTransport, endpoint) },
            ) { Text("START") }

            Button(
                modifier = Modifier.weight(1f),
                enabled = streamState.wantsStreaming,
                onClick = { service?.stopStream() },
            ) { Text("STOP") }
        }

        Text(
            text = streamState.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (selectedTransport == TransportMode.RIST || selectedTransport == TransportMode.RIST_MULTIPATH) {
            val context = LocalContext.current
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val report = service?.ristDiagnosticReport()
                        ?: RistDiagnostics.report(context, streamState)
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("BIG IRL RIST diagnostic", report))
                    Toast.makeText(context, "RIST diagnostic copied", Toast.LENGTH_SHORT).show()
                },
            ) { Text("COPY RIST DIAGNOSTIC") }
        }

        if (streamState.wantsStreaming) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = { service?.adjustVideoBitrateKbps(-500) },
                ) { Text("-500 Kbps") }
                Text(
                    "Target ${streamState.requestedBitrateKbps} Kbps",
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = { service?.adjustVideoBitrateKbps(500) },
                ) { Text("+500 Kbps") }
            }
        }
        Text("Active profile: ${streamState.profile.label}")
    }
}

@Composable
private fun TransportSelector(
    selectedTransport: TransportMode,
    menuOpen: Boolean,
    enabled: Boolean,
    onMenuChange: (Boolean) -> Unit,
    onTransportSelected: (TransportMode) -> Unit,
) {
    Box {
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            onClick = { onMenuChange(true) },
        ) {
            Text(selectedTransport.label)
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { onMenuChange(false) },
        ) {
            TransportMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label) },
                    onClick = {
                        onMenuChange(false)
                        onTransportSelected(mode)
                    },
                )
            }
        }
    }
}

@Composable
private fun ProfileSelector(
    selectedProfile: StreamProfile,
    profileMenuOpen: Boolean,
    enabled: Boolean,
    onProfileMenuChange: (Boolean) -> Unit,
    onProfileSelected: (StreamProfile) -> Unit,
) {
    Box {
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            onClick = { onProfileMenuChange(true) },
        ) {
            Text(selectedProfile.label)
        }
        DropdownMenu(
            expanded = profileMenuOpen,
            onDismissRequest = { onProfileMenuChange(false) },
        ) {
            StreamProfile.entries.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(profile.label) },
                    onClick = {
                        onProfileMenuChange(false)
                        onProfileSelected(profile)
                    },
                )
            }
        }
    }
}

@Composable
private fun SenderOverlay(
    modifier: Modifier,
    streamState: StreamingService.StreamState,
    network: PhoneNetworkState,
) {
    val stateLabel = when {
        streamState.isStreaming -> "LIVE"
        streamState.isReconnecting -> "RECONNECTING"
        streamState.wantsStreaming -> "CONNECTING"
        else -> "IDLE"
    }
    val activeBitrate = streamState.bitrateKbps
    val wifiText = if (network.activeTransport == "wifi") compactRate(activeBitrate) else "standby"
    val simText = if (network.activeTransport == "cellular") compactRate(activeBitrate) else "standby"

    Column(
        modifier = modifier
            .padding(5.dp)
            .background(Color(0x99071019), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Text(
            "$stateLabel  TX ${compactRate(streamState.bitrateKbps)}",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            lineHeight = 10.sp,
        )
        Text(
            "${if (network.wifiAvailable) "●" else "○"} Wi-Fi $wifiText",
            color = if (network.wifiAvailable) Color(0xFF51E1A6) else Color.LightGray,
            fontSize = 8.sp,
            lineHeight = 9.sp,
        )
        Text(
            "${if (network.cellularAvailable) "●" else "○"} SIM $simText",
            color = if (network.cellularAvailable) Color(0xFF51E1A6) else Color.LightGray,
            fontSize = 8.sp,
            lineHeight = 9.sp,
        )
    }
}

@Composable
private fun ReceiverOverlay(
    modifier: Modifier,
    telemetry: StreamBoxTelemetry,
    streamState: StreamingService.StreamState,
) {
    val quality = if (telemetry.width > 0 && telemetry.height > 0) {
        "${telemetry.width}x${telemetry.height} ${"%.1f".format(telemetry.fps)}p"
    } else {
        "waiting"
    }

    Column(
        modifier = modifier
            .padding(5.dp)
            .background(Color(0x99071019), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Text(
            "RX ${compactRate(telemetry.receivedKbps)}",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            lineHeight = 10.sp,
        )
        Text(quality, color = Color.White, fontSize = 8.sp, lineHeight = 9.sp)
        val nanoConnected = telemetry.online || streamState.isStreaming
        val nanoStatus = when {
            telemetry.online -> "Nano ${"%.1f".format(telemetry.nanoTemperatureC)} C"
            streamState.isStreaming -> "Nano live"
            streamState.isReconnecting -> "Nano reconnecting"
            else -> "Nano ready"
        }
        Text(
            nanoStatus,
            color = if (nanoConnected) Color(0xFF51E1A6) else Color(0xFFFFC857),
            fontSize = 8.sp,
            lineHeight = 9.sp,
        )
    }
}

@Composable
private fun TorchOverlay(
    modifier: Modifier,
    torchEnabled: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val background = if (torchEnabled) Color(0xD9FFD54F) else Color(0x995F6368)
    val foreground = if (torchEnabled) Color(0xFF302700) else Color.White

    Box(
        modifier = modifier
            .padding(7.dp)
            .size(38.dp)
            .background(background, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "🔦",
            color = foreground,
            fontSize = 18.sp,
            lineHeight = 18.sp,
        )
    }
}

private fun compactRate(kbps: Long): String = "${kbps.coerceAtLeast(0)} Kbps"

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
