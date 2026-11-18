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
    initialCaptureMode: ScannerCaptureMode = ScannerCaptureMode.MEAL_OR_BARCODE,
    onPhotoCaptured: (bitmap: Bitmap, userHint: String, captureMode: ScannerCaptureMode) -> Unit,
    onBarcodeDetected: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val barcodeScanner = remember { BarcodeScanning.getClient() }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var barcodeHandled by remember { mutableStateOf(false) }

    var captureMode by remember { mutableStateOf(initialCaptureMode) }
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
                onPhotoCaptured(bmp, userHint.trim(), captureMode)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            barcodeScanner.close()
        }
    }

    val modeAccentColor = when (captureMode) {
        ScannerCaptureMode.MEAL_OR_BARCODE -> Color(0xFF10B981)
        ScannerCaptureMode.NUTRITION_LABEL_OCR -> Color(0xFF06B6D4)
        ScannerCaptureMode.INGREDIENTS_OCR -> Color(0xFFFBBF24)
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
                                val allowBarcode = captureMode == ScannerCaptureMode.MEAL_OR_BARCODE
                                if (mediaImage != null && !barcodeHandled && allowBarcode) {
                                    val inputImage = InputImage.fromMediaImage(
                                        mediaImage,
                                        proxy.imageInfo.rotationDegrees
                                    )
                                    barcodeScanner.process(inputImage)
                                        .addOnSuccessListener { barcodes ->
                                            val raw = barcodes.firstOrNull()?.rawValue
                                            if (!raw.isNullOrBlank() && !barcodeHandled && allowBarcode) {
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

        // Centered Reticle Box
        Canvas(modifier = Modifier.fillMaxSize()) {
            val isTextOcr = captureMode != ScannerCaptureMode.MEAL_OR_BARCODE
            val boxWidth = size.width * if (isTextOcr) 0.84f else 0.72f
            val boxHeight = size.height * if (isTextOcr) 0.42f else 0.26f
            val left = (size.width - boxWidth) / 2f
            val top = (size.height - boxHeight) / 2.3f

            drawRoundRect(
                color = if (isTextOcr) modeAccentColor.copy(alpha = 0.88f) else Color.White.copy(alpha = 0.65f),
                topLeft = Offset(left, top),
                size = Size(boxWidth, boxHeight),
                cornerRadius = CornerRadius(16.dp.toPx()),
                style = Stroke(width = 3.dp.toPx())
            )
        }

        // Top Bar: Close + 3-Mode Selector Pill
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.55f), CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }

                Surface(
                    color = Color.Black.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        ModeTabChip(
                            label = "Meal",
                            selected = captureMode == ScannerCaptureMode.MEAL_OR_BARCODE,
                            activeColor = Color(0xFF10B981),
                            onClick = { captureMode = ScannerCaptureMode.MEAL_OR_BARCODE }
                        )
                        ModeTabChip(
                            label = "Macros",
                            selected = captureMode == ScannerCaptureMode.NUTRITION_LABEL_OCR,
                            activeColor = Color(0xFF06B6D4),
                            onClick = { captureMode = ScannerCaptureMode.NUTRITION_LABEL_OCR }
                        )
                        ModeTabChip(
                            label = "Ingredients",
                            selected = captureMode == ScannerCaptureMode.INGREDIENTS_OCR,
                            activeColor = Color(0xFFFBBF24),
                            onClick = { captureMode = ScannerCaptureMode.INGREDIENTS_OCR }
                        )
                    }
                }
            }
        }

        // Bottom Controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.74f))
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = when (captureMode) {
                    ScannerCaptureMode.MEAL_OR_BARCODE ->
                        "Auto-scans barcodes in box • Tap shutter for AI meal analysis"
                    ScannerCaptureMode.NUTRITION_LABEL_OCR ->
                        "Frame the Per 100g / Nutrition table and tap capture"
                    ScannerCaptureMode.INGREDIENTS_OCR ->
                        "Frame the package's Ingredients list to check Good vs. Bad ingredients"
                },
                color = Color.White.copy(alpha = 0.92f),
                style = MaterialTheme.typography.labelMedium
            )

            if (captureMode == ScannerCaptureMode.MEAL_OR_BARCODE) {
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
                                    onPhotoCaptured(rotated, userHint.trim(), captureMode)
                                }
                            }
                        )
                    },
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = modeAccentColor,
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

@Composable
private fun ModeTabChip(
    label: String,
    selected: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = if (selected) activeColor else Color.Transparent,
        shape = RoundedCornerShape(14.dp)
    ) {
        Text(
            text = label,
            color = if (selected) Color(0xFF042F2E) else Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
        )
    }
}

private fun Bitmap.rotate(degrees: Int): Bitmap {
    if (degrees == 0) return this
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}
