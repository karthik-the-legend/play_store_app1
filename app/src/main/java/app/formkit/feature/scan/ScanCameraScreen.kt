package app.formkit.feature.scan

import androidx.activity.compose.BackHandler
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.formkit.R
import app.formkit.core.ui.theme.Spacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * The viewfinder: a photo per page, without leaving FormKit. Each shot goes straight to the page
 * check, so a page whose edges weren't found is caught while the document is still on the table.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ScanCameraScreen(
    pageCount: Int,
    isBusy: Boolean,
    newPhotoFile: suspend () -> File,
    onCaptured: (File) -> Unit,
    onProblem: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val latestProblem by rememberUpdatedState(onProblem)
    var capturing by remember { mutableStateOf(false) }
    // Which shot is current. A shot that times out is written off, so its late result is ignored.
    var shot by remember { mutableIntStateOf(0) }

    val controller = remember(context) {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        }
    }
    DisposableEffect(controller, lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        controller.initializationFuture.addListener(
            { runCatching { controller.initializationFuture.get() }.onFailure { latestProblem() } },
            ContextCompat.getMainExecutor(context),
        )
        onDispose { controller.unbind() }
    }
    BackHandler(onBack = onClose)

    val capture: () -> Unit = {
        if (!capturing && !isBusy) {
            capturing = true
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            scope.launch {
                val photo = try {
                    newPhotoFile()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    capturing = false
                    latestProblem()
                    return@launch
                }
                val thisShot = ++shot
                controller.takePicture(
                    ImageCapture.OutputFileOptions.Builder(photo).build(),
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                            if (thisShot != shot) {
                                photo.delete()
                                return
                            }
                            capturing = false
                            onCaptured(photo)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            photo.delete()
                            if (thisShot != shot) return
                            capturing = false
                            latestProblem()
                        }
                    },
                )
                // A camera that accepts the shot but never delivers the photo (seen on the Android 10
                // emulator's virtual camera) would otherwise leave the shutter spinning for good.
                delay(CAPTURE_TIMEOUT_MILLIS)
                if (capturing && thisShot == shot) {
                    shot++
                    capturing = false
                    latestProblem()
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { viewContext ->
                PreviewView(viewContext).apply {
                    this.controller = controller
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.cd_close_camera), tint = Color.White)
        }

        Text(
            text = stringResource(R.string.scan_camera_hint),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = Spacing.large)
                .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                .padding(horizontal = Spacing.medium, vertical = Spacing.small),
        )

        if (capturing || isBusy) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(Spacing.large),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = pluralStringResource(R.plurals.scan_page_count, pageCount, pageCount),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            Shutter(enabled = !capturing && !isBusy, onClick = capture)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                TextButton(onClick = onClose, enabled = pageCount > 0) {
                    Text(
                        text = stringResource(R.string.scan_camera_done),
                        color = Color.White.copy(alpha = if (pageCount > 0) 1f else 0.4f),
                    )
                }
            }
        }
    }
}

@Composable
private fun Shutter(enabled: Boolean, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.4f
    val label = stringResource(R.string.cd_take_photo)
    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            // The button is a plain circle, so its name has to be spelled out for a screen reader.
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(68.dp)) {
            drawCircle(Color.White.copy(alpha = alpha), style = Stroke(width = 4.dp.toPx()))
            drawCircle(Color.White.copy(alpha = alpha), radius = size.minDimension / 2 - 9.dp.toPx())
        }
    }
}

/** Real phones take a photo in a second or two; this is long enough for a slow one. */
private const val CAPTURE_TIMEOUT_MILLIS = 20_000L
