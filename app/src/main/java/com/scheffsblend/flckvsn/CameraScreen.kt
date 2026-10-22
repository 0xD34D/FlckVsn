package com.scheffsblend.flckvsn

import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

private const val TAG = "CameraScreen"

// Matrix HUD Color Palette
val Green = Color(0xFF00FF66)
val GreenBright = Color(0xFF39FF14)
val GreenDim = Color(0xFF008833)
val BgDark = Color(0xF0081008)
val Surface = Color(0xD80D180D)
val Border = Color(0x6600FF66)

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var selectedModel by remember { mutableStateOf(ModelCatalog.YOLO_PICO3) }
    var enabledLabels by remember(selectedModel) {
        mutableStateOf(selectedModel.labels.toSet())
    }
    val currentSelectedModel by rememberUpdatedState(selectedModel)
    val currentEnabledLabels by rememberUpdatedState(enabledLabels)
    var detections by remember { mutableStateOf<List<Detection>>(emptyList()) }
    var frameSize by remember { mutableStateOf(0 to 0) }
    var lastInferenceMs by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var activeProcessor by remember { mutableStateOf("CPU") }

    val detectorLock = remember { Any() }
    var detector by remember { mutableStateOf<Detector?>(null) }

    // Safely load and switch model interpreters without race conditions or memory leaks
    LaunchedEffect(selectedModel) {
        detections = emptyList()
        var freshDetector: Detector? = null
        try {
            freshDetector = withContext(Dispatchers.IO) {
                DetectorFactory.create(context, selectedModel)
            }
            withContext(Dispatchers.IO) {
                synchronized(detectorLock) {
                    val old = detector
                    detector = freshDetector
                    freshDetector = null // Marked assigned; prevents cleanup in finally block
                    old?.close()
                }
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "Model load cancelled for ${selectedModel.id}")
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create detector for ${selectedModel.id}", t)
        } finally {
            // Close freshDetector if coroutine was cancelled before assignment to `detector`
            freshDetector?.close()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            synchronized(detectorLock) {
                detector?.close()
                detector = null
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Camera Preview
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                }

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                    val resolutionSelector = androidx.camera.core.resolutionselector.ResolutionStrategy(
                        android.util.Size(640, 640),
                        androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    ).let { strategy ->
                        androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                            .setResolutionStrategy(strategy)
                            .build()
                    }

                    val analysisExecutor = Executors.newSingleThreadExecutor()
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setResolutionSelector(resolutionSelector)
                        .build()

                    imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        try {
                            val bitmap: Bitmap = ImageUtils.imageProxyToBitmap(
                                imageProxy,
                                imageProxy.imageInfo.rotationDegrees,
                            )
                            val detectionResult = synchronized(detectorLock) {
                                val activeDetector = detector ?: return@synchronized null
                                val start = System.currentTimeMillis()
                                val raw = activeDetector.detect(bitmap)
                                val elapsed = System.currentTimeMillis() - start

                                // Use currentEnabledLabels if activeDetector matches currentSelectedModel.
                                // If all labels are disabled, filter out everything.
                                // Otherwise during model load transitions, fall back to activeDetector's labels.
                                val validLabels = if (currentEnabledLabels.isEmpty()) {
                                    emptySet()
                                } else if (activeDetector.spec.id == currentSelectedModel.id) {
                                    currentEnabledLabels
                                } else {
                                    activeDetector.spec.labels.toSet()
                                }

                                Triple(raw.filter { it.label in validLabels }, elapsed, bitmap.width to bitmap.height) to activeDetector.processor
                            }

                            if (detectionResult != null) {
                                val (triple, proc) = detectionResult
                                val (filteredDets, elapsed, size) = triple
                                lastInferenceMs = elapsed
                                detections = filteredDets
                                frameSize = size
                                activeProcessor = proc
                            }
                        } catch (t: Throwable) {
                            Log.e(TAG, "Inference failed", t)
                        } finally {
                            imageProxy.close()
                        }
                    }

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner, cameraSelector, preview, imageAnalysis,
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Camera bind failed", e)
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
        )

        // Bounding Box Detection Overlay
        DetectionOverlay(
            detections = detections,
            frameWidth = frameSize.first,
            frameHeight = frameSize.second,
            modifier = Modifier.fillMaxSize(),
        )

        // Top Metrics Bar (Single Row for Model, Speed, and Detection Count)
        TopMetricsBar(
            selectedModel = selectedModel,
            processor = activeProcessor,
            inferenceMs = lastInferenceMs,
            detectionCount = detections.size,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(WindowInsets.statusBars.asPaddingValues()),
        )

        // Game HUD Sliding Control Panel (Bottom Drawer)
        HudControlPanel(
            selectedModel = selectedModel,
            onModelSelected = { model ->
                selectedModel = model
                enabledLabels = model.labels.toSet()
            },
            enabledLabels = enabledLabels,
            onLabelsChanged = { enabledLabels = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(WindowInsets.navigationBars.asPaddingValues()),
        )
    }
}

/**
 * Top HUD Metrics Bar displaying stats on a single row at the top of the screen.
 */
@Composable
private fun TopMetricsBar(
    selectedModel: ModelSpec,
    processor: String,
    inferenceMs: Long,
    detectionCount: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(10.dp),
        color = Surface,
        border = BorderStroke(1.dp, Border),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${selectedModel.id.uppercase()} [$processor]",
                color = GreenBright,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )

            Text(
                text = "${inferenceMs}ms/frame",
                color = Green,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            )

            Text(
                text = "$detectionCount SHOWN",
                color = if (detectionCount > 0) GreenBright else GreenDim,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Game HUD Bottom Panel Drawer that slides up and down.
 */
@Composable
private fun HudControlPanel(
    selectedModel: ModelSpec,
    onModelSelected: (ModelSpec) -> Unit,
    enabledLabels: Set<String>,
    onLabelsChanged: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isExpanded by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 8.dp, bottomEnd = 8.dp),
        color = BgDark,
        border = BorderStroke(1.5.dp, Border),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top Drawer Header (Always Visible) - Drag handle & Model selector
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { _, dragAmount ->
                            if (dragAmount < -10) isExpanded = true
                            else if (dragAmount > 10) isExpanded = false
                        }
                    }
                    .clickable { isExpanded = !isExpanded },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Drag Handle
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (isExpanded) GreenBright else GreenDim),
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Model Selector Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Dropdown for selecting models (flexes and truncates cleanly)
                    Box(modifier = Modifier.weight(1f, fill = false)) {
                        Surface(
                            onClick = { menuExpanded = true },
                            shape = RoundedCornerShape(6.dp),
                            color = Surface,
                            border = BorderStroke(1.dp, Green),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = selectedModel.displayName,
                                    color = GreenBright,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (menuExpanded) "▲" else "▼",
                                    color = Green,
                                    fontSize = 10.sp,
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            modifier = Modifier
                                .background(BgDark)
                                .border(1.dp, Green, RoundedCornerShape(6.dp)),
                        ) {
                            ModelCatalog.ALL.forEach { model ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = model.displayName,
                                            color = if (model.id == selectedModel.id) GreenBright else Green,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = if (model.id == selectedModel.id) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 13.sp,
                                        )
                                    },
                                    onClick = {
                                        onModelSelected(model)
                                        menuExpanded = false
                                    },
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = if (isExpanded) "▲ HIDE" else "▼ LABELS",
                        color = Green,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Expandable Content (Slide Up / Down for Label Toggles & All Button)
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Border),
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "LABEL FILTERS",
                        color = GreenDim,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.align(Alignment.Start),
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // 2-Column Grid Format for Per-Model Labels
                    val chunkedLabels = selectedModel.labels.chunked(2)
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        for (rowLabels in chunkedLabels) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                for (label in rowLabels) {
                                    val isSelected = label in enabledLabels
                                    HudLabelToggleChip(
                                        label = label,
                                        isSelected = isSelected,
                                        onClick = {
                                            onLabelsChanged(
                                                if (isSelected) enabledLabels - label else enabledLabels + label,
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                if (rowLabels.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // "ALL" Button Below and Centered Horizontally
                    val allSelected = enabledLabels.size == selectedModel.labels.size
                    Button(
                        onClick = {
                            if (allSelected) {
                                onLabelsChanged(emptySet())
                            } else {
                                onLabelsChanged(selectedModel.labels.toSet())
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (allSelected) Color(0x3300FF66) else Color(0x1100FF66),
                            contentColor = GreenBright,
                        ),
                        border = BorderStroke(1.dp, Green),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = if (allSelected) "[ DESELECT ALL ]" else "[ SELECT ALL ]",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            letterSpacing = 1.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HudLabelToggleChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(4.dp),
        color = if (isSelected) Color(0x2B00FF66) else Color(0x0F003311),
        border = BorderStroke(
            width = 1.dp,
            color = if (isSelected) GreenBright else GreenDim,
        ),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Text(
                text = if (isSelected) "[■]" else "[ ]",
                color = if (isSelected) GreenBright else GreenDim,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                color = if (isSelected) GreenBright else Color(0xAA00FF66),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
