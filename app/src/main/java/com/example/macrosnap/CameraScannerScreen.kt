package com.example.macrosnap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

@OptIn(ExperimentalGetImage::class)
@Composable
fun CameraScannerScreen(
    initialLabelOcrMode: Boolean = false,
    onPhotoCaptured: (bitmap: Bitmap, userHint: String, isLabelOcr: Boolean) -> Unit,
    onBarcodeDetected: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val barcodeScanner = remember { BarcodeScanning.getClient() }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var barcodeHandled by remember { mutableStateOf(false) }

    var isLabelOcrMode by remember { mutableStateOf(initialLabelOcrMode) }
    var userHint by remember { mutableStateOf("") }

    val quickHints = listOf(
        "Cooked in 1 tbsp oil",
        "No added oil/butter",
        "Restaurant portion",
        "Half portion",
        "With dressing on side"
    )

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            }.getOrNull()?.let { bmp ->
                onPhotoCaptured(bmp, userHint.trim(), isLabelOcrMode)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            barcodeScanner.close()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                    imageCapture = capture

                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { imageAnalysis ->
                            imageAnalysis.setAnalyzer(cameraExecutor) { proxy ->
                                val mediaImage = proxy.image
                                if (mediaImage != null && !barcodeHandled && !isLabelOcrMode) {
                                    val inputImage = InputImage.fromMediaImage(
                                        mediaImage,
                                        proxy.imageInfo.rotationDegrees
                                    )
                                    barcodeScanner.process(inputImage)
                                        .addOnSuccessListener { barcodes ->
                                            val raw = barcodes.firstOrNull()?.rawValue
                                            if (!raw.isNullOrBlank() && !barcodeHandled && !isLabelOcrMode) {
                                                barcodeHandled = true
                                                onBarcodeDetected(raw)
                                            }
                                        }
                                        .addOnCompleteListener { proxy.close() }
                                } else {
                                    proxy.close()
                                }
                            }
                        }

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        capture,
                        analysis
                    )
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            }
        )

        // Centered Reticle Box for Barcodes, Nutrition Labels & Meal Framing
        Canvas(modifier = Modifier.fillMaxSize()) {
            val boxWidth = size.width * if (isLabelOcrMode) 0.80f else 0.72f
            val boxHeight = size.height * if (isLabelOcrMode) 0.44f else 0.26f
            val left = (size.width - boxWidth) / 2f
            val top = (size.height - boxHeight) / 2.3f

            drawRoundRect(
                color = if (isLabelOcrMode) Color(0xFF06B6D4).copy(alpha = 0.85f) else Color.White.copy(alpha = 0.65f),
                topLeft = Offset(left, top),
                size = Size(boxWidth, boxHeight),
                cornerRadius = CornerRadius(16.dp.toPx()),
                style = Stroke(width = 3.dp.toPx())
            )
        }

        // Top Bar: Close & Mode Toggle (AI Meal / Barcode vs Nutrition Label OCR)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }

            Surface(
                onClick = { isLabelOcrMode = !isLabelOcrMode },
                color = if (isLabelOcrMode) Color(0xFF06B6D4) else Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.semantics(mergeDescendants = true) {}
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                        contentDescription = null,
                        tint = if (isLabelOcrMode) Color(0xFF042F2E) else Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = if (isLabelOcrMode) "Label OCR Mode: ON" else "Scan Nutrition Label",
                        color = if (isLabelOcrMode) Color(0xFF042F2E) else Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Bottom Controls: Pre-capture AI Hint, Quick Chips, Gallery Picker & Shutter
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.72f))
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = if (isLabelOcrMode) {
                    "Frame the package's Nutrition Facts table and tap capture"
                } else {
                    "Auto-scans barcodes in box • Tap shutter or gallery for AI meal analysis"
                },
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.labelMedium
            )

            if (!isLabelOcrMode) {
                OutlinedTextField(
                    value = userHint,
                    onValueChange = { userHint = it },
                    placeholder = {
                        Text(
                            "Optional AI hint (e.g. 'cooked in 1 tbsp olive oil, 200g chicken')",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF10B981),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.25f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    quickHints.forEach { chip ->
                        val active = userHint.contains(chip, ignoreCase = true)
                        FilterChip(
                            selected = active,
                            onClick = {
                                userHint = if (userHint.isBlank()) chip else "$userHint, $chip"
                            },
                            label = { Text(chip, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Color.White.copy(alpha = 0.12f),
                                labelColor = Color.White,
                                selectedContainerColor = Color(0xFF10B981),
                                selectedLabelColor = Color(0xFF042F2E)
                            )
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalIconButton(
                    onClick = {
                        galleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier.size(52.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = Color.White.copy(alpha = 0.16f),
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = "Pick from Gallery")
                }

                FilledIconButton(
                    onClick = {
                        val capture = imageCapture ?: return@FilledIconButton
                        capture.takePicture(
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    val rotated = image.toBitmap().rotate(image.imageInfo.rotationDegrees)
                                    image.close()
                                    onPhotoCaptured(rotated, userHint.trim(), isLabelOcrMode)
                                }
                            }
                        )
                    },
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isLabelOcrMode) Color(0xFF06B6D4) else Color(0xFF10B981),
                        contentColor = Color(0xFF042F2E)
                    )
                ) {
                    Icon(
                        Icons.Default.PhotoCamera,
                        contentDescription = "Capture Photo",
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.size(52.dp))
            }
        }
    }
}

private fun Bitmap.rotate(degrees: Int): Bitmap {
    if (degrees == 0) return this
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}
