package com.kentaurverk.moodmirror

import android.app.Activity
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kentaurverk.moodmirror.domain.ExpressionLabel
import com.kentaurverk.moodmirror.domain.CameraStatus
import com.kentaurverk.moodmirror.domain.ErrorKind
import com.kentaurverk.moodmirror.domain.MoodMirrorUiState
import com.kentaurverk.moodmirror.domain.SessionMode
import com.kentaurverk.moodmirror.ui.MoodMirrorViewModel
import android.view.TextureView
import java.util.Locale
import androidx.compose.ui.graphics.Brush
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class LocalizedStrings(private val resources: android.content.res.Resources) {
    operator fun invoke(id: Int, vararg args: Any): String = resources.getString(id, *args)
}

class MainActivity : ComponentActivity() {
    private val viewModel: MoodMirrorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MoodMirrorApp(viewModel) }
    }

    override fun onStop() {
        viewModel.onAppBackgrounded()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val canRequestAgain = !viewModel.cameraPermissionWasAttempted() || ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)
        viewModel.setCameraPermission(granted, canRequestAgain)
    }
}

private val Ink = Color(0xFF111418)
private val Paper = Color(0xFFF4F1E8)
private val Lime = Color(0xFFB7F34A)
private val Hot = Color(0xFFFF5C35)
private val Cyan = Color(0xFF42D9E8)
private val Muted = Color(0xFF74756F)

@Composable
private fun MoodMirrorApp(viewModel: MoodMirrorViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val canRequestAgain = granted || (context as? Activity)?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA)
        } == true
        viewModel.setCameraPermission(granted, canRequestAgain)
    }
    LaunchedEffect(Unit) {
        viewModel.initializeModel()
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val canRequestAgain = !viewModel.cameraPermissionWasAttempted() || (context as? Activity)?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA)
        } == true
        viewModel.setCameraPermission(granted, canRequestAgain)
    }

    val localizedResources = remember(state.languageTag) {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(state.languageTag))
        context.createConfigurationContext(configuration).resources
    }
    val s = LocalizedStrings(localizedResources)

    val activity = context as? Activity
    DisposableEffect(state.cameraRunning, activity) {
        if (state.cameraRunning) activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Paper) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopBar(state, s, viewModel)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 28.dp, vertical = 16.dp),
                ) {
                    Text(s(R.string.hero_title), color = Ink, fontSize = 38.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.SansSerif)
                    Text(s(R.string.hero_subtitle), color = Muted, fontSize = 16.sp, modifier = Modifier.padding(top = 2.dp, bottom = 18.dp))
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val wide = maxWidth > 760.dp
                        if (wide) {
                            Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                CameraCard(state, s, viewModel, lifecycleOwner, permissionLauncher, Modifier.weight(0.94f).fillMaxHeight())
                                ReadoutCard(state, s, Modifier.weight(1.06f).fillMaxHeight())
                            }
                        } else {
                            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                CameraCard(state, s, viewModel, lifecycleOwner, permissionLauncher, Modifier.fillMaxWidth().weight(1.15f))
                                ReadoutCard(state, s, Modifier.fillMaxWidth().weight(0.85f))
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.showAbout) {
        AlertDialog(
            onDismissRequest = viewModel::closeAbout,
            title = { Text(s(R.string.about_title), color = Ink) },
            text = { Text(s(R.string.about_body, BuildConfig.VERSION_NAME), color = Ink) },
            confirmButton = { TextButton(onClick = viewModel::closeAbout) { Text(s(R.string.about_close), color = Hot) } },
        )
    }
}

@Composable
private fun TopBar(state: MoodMirrorUiState, s: LocalizedStrings, viewModel: MoodMirrorViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Ink).padding(horizontal = 28.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(s(R.string.brand_wordmark), color = Lime, fontSize = 18.sp, fontWeight = FontWeight.Black, letterSpacing = 1.6.sp)
            Text(s(R.string.brand_tagline), color = Color.White.copy(alpha = 0.62f), fontSize = 9.sp, letterSpacing = 1.2.sp)
        }
        TextButton(onClick = viewModel::toggleSound, colors = ButtonDefaults.textButtonColors(contentColor = Color.White), modifier = Modifier.semantics { contentDescription = s(R.string.accessibility_sound) }) {
            Text(if (state.soundEnabled) "◉  ${s(R.string.sound_on)}" else "○  ${s(R.string.sound_off)}", fontSize = 11.sp)
        }
        TextButton(onClick = viewModel::toggleLanguage, colors = ButtonDefaults.textButtonColors(contentColor = Lime), modifier = Modifier.semantics { contentDescription = s(R.string.accessibility_language) }) {
            Text(if (state.languageTag == "en") s(R.string.language_english) else s(R.string.language_italian), fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = viewModel::openAbout, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
            Text(s(R.string.about), fontSize = 11.sp)
        }
    }
}

@Composable
private fun CameraCard(
    state: MoodMirrorUiState,
    s: LocalizedStrings,
    viewModel: MoodMirrorViewModel,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    permissionLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val requestCamera = {
        if (state.permissionPermanentlyDenied) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        } else {
            viewModel.markCameraPermissionRequest()
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    CardShell(modifier) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(s(R.string.camera_title), color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
            FilterChip(
                selected = state.isDogMode,
                onClick = viewModel::toggleDogMode,
                label = { Text(if (state.isDogMode) s(R.string.dog_mode_on) else s(R.string.dog_mode), fontSize = 11.sp) },
                modifier = Modifier.semantics { contentDescription = s(R.string.accessibility_dog) },
            )
        }
        Text(
            text = s(cameraStatusRes(state.cameraStatus)),
            color = when (state.cameraStatus) {
                CameraStatus.SIGNAL_LOCKED -> Color(0xFF3E8E3E)
                CameraStatus.ERROR, CameraStatus.UNSUPPORTED, CameraStatus.PERMISSION_DENIED -> Hot
                else -> Muted
            },
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(Ink),
        ) {
            if (state.isDogMode) {
                DogImage(state.dogLabel, Modifier.fillMaxSize())
            } else if (!state.cameraRunning) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (state.modelLoading) s(R.string.camera_initializing) else s(R.string.camera_waiting), color = Color.White.copy(alpha = 0.72f)) }
            }
            val showLivePreview = state.cameraRunning && state.mode == SessionMode.CAMERA
            if (showLivePreview) {
                AndroidView(
                    factory = { context ->
                        TextureView(context).apply {
                            viewModel.attachPreview(this)
                        }
                    },
                    modifier = if (state.isDogMode) {
                        Modifier.align(Alignment.TopStart).padding(12.dp).size(128.dp, 96.dp).clip(RoundedCornerShape(8.dp)).border(2.dp, Lime, RoundedCornerShape(8.dp))
                    } else Modifier.fillMaxSize(),
                )
            }
            if (state.isDogMode && showLivePreview) {
                Text(s(R.string.camera_source_label), color = Color.White.copy(alpha = 0.78f), fontSize = 9.sp, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
            }
            if (!state.cameraPermissionGranted && state.mode != SessionMode.DEMO) {
                Column(modifier = Modifier.align(Alignment.Center).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(s(R.string.camera_permission), color = Color.White, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = requestCamera,
                        colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink),
                    ) { Text(if (state.permissionPermanentlyDenied) s(R.string.camera_open_settings) else s(R.string.camera_permission_action)) }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { if (state.cameraRunning) viewModel.stopCamera() else if (state.cameraPermissionGranted) viewModel.startCamera() else requestCamera() },
                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Lime),
            ) { Text(if (state.cameraRunning) s(R.string.camera_stop) else s(R.string.camera_start)) }
            TextButton(onClick = viewModel::startDemo) { Text(s(R.string.demo_mode), color = Hot, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.weight(1f))
            Text(if (state.mode == SessionMode.DEMO) s(R.string.demo_mode_running) else s(R.string.camera_note), color = Muted, fontSize = 10.sp)
        }
        state.errorMessage?.let { error ->
            Text(s(if (state.errorKind == ErrorKind.CAMERA) R.string.error_camera else R.string.error_model, error), color = Hot, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

private fun cameraStatusRes(status: CameraStatus): Int = when (status) {
    CameraStatus.READY -> R.string.camera_status_ready
    CameraStatus.WARMING -> R.string.camera_status_warming
    CameraStatus.FINDING_FACE -> R.string.camera_status_finding
    CameraStatus.SIGNAL_LOCKED -> R.string.camera_status_locked
    CameraStatus.DEMO -> R.string.demo_mode_running
    CameraStatus.PERMISSION_REQUIRED -> R.string.camera_status_permission
    CameraStatus.PERMISSION_DENIED -> R.string.camera_status_denied
    CameraStatus.UNSUPPORTED -> R.string.camera_status_unsupported
    CameraStatus.ERROR -> R.string.camera_status_error
}

@Composable
private fun ReadoutCard(state: MoodMirrorUiState, s: LocalizedStrings, modifier: Modifier) {
    CardShell(modifier) {
        Text(s(R.string.readout_title), color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.currentLabel.emoji, fontSize = 58.sp)
            Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
                Text(s(state.currentLabel.labelRes), color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Black)
                Text(if (state.faceDetected) s(R.string.camera_face_ready) else s(R.string.camera_no_face), color = Muted, fontSize = 12.sp)
            }
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { state.confidence }, color = Hot, trackColor = Color(0xFFE6E0D2), strokeWidth = 8.dp, modifier = Modifier.size(76.dp))
                Text("${(state.confidence * 100).toInt()}%", color = Ink, fontWeight = FontWeight.Black, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(15.dp))
        Text(s(R.string.score_breakdown), color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(5.dp))
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            ExpressionLabel.entries.forEach { label -> ScoreLine(label, state.scores[label], s) }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s(R.string.pulse_history), color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
            Text(s(R.string.sample_count, state.sampleCount), color = Muted, fontSize = 10.sp)
        }
        PulseGraph(state, Modifier.fillMaxWidth().height(52.dp).padding(top = 6.dp))
        Text(s(R.string.privacy_disclaimer), color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun ScoreLine(label: ExpressionLabel, score: Float, s: LocalizedStrings) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(s(label.labelRes), color = Ink, fontSize = 11.sp, modifier = Modifier.width(76.dp))
        Box(Modifier.weight(1f).height(7.dp).clip(CircleShape).background(Color(0xFFE3DED1))) {
            Box(Modifier.fillMaxWidth(score.coerceIn(0f, 1f)).fillMaxHeight().background(if (label == ExpressionLabel.HAPPY) Lime else Cyan))
        }
        Text("${(score * 100).toInt()}%", color = Muted, fontSize = 10.sp, modifier = Modifier.width(34.dp).wrapContentWidth(Alignment.End))
    }
}

@Composable
private fun PulseGraph(state: MoodMirrorUiState, modifier: Modifier) {
    Canvas(modifier) {
        val points = state.history
        if (points.size < 2) return@Canvas
        val path = Path()
        points.forEachIndexed { index, sample ->
            val x = size.width * index / (points.size - 1).toFloat()
            val y = size.height * (1f - sample.confidence.coerceIn(0.05f, 1f))
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawLine(Color(0xFFE1DACE), Offset(0f, size.height), Offset(size.width, size.height), 1f)
        drawPath(path, color = Hot, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f, cap = StrokeCap.Round))
    }
}

@Composable
private fun CardShell(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = modifier.background(Color.White.copy(alpha = 0.78f), RoundedCornerShape(16.dp)).padding(18.dp), content = content)
}

@Composable
private fun DogImage(label: ExpressionLabel, modifier: Modifier) {
    val context = LocalContext.current
    val dogImages by produceState<Map<ExpressionLabel, ImageBitmap>>(initialValue = emptyMap(), context) {
        value = withContext(Dispatchers.IO) {
            ExpressionLabel.entries.mapNotNull { expression ->
                runCatching {
                    val bitmap = context.assets.open("${expression.name.lowercase(Locale.ROOT)}.png").use(BitmapFactory::decodeStream)
                    expression to bitmap.asImageBitmap()
                }.getOrNull()
            }.toMap()
        }
    }
    val image = dogImages[label] ?: dogImages[ExpressionLabel.NEUTRAL]
    image?.let { Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier) }
}
