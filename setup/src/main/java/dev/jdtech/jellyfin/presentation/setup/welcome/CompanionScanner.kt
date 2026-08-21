package dev.jdtech.jellyfin.presentation.setup.welcome

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.jdtech.jellyfin.core.util.QrCodeDecoder
import dev.jdtech.jellyfin.models.companion.CompanionDiscoveryPayload
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.concurrent.Executors

@Composable
fun CompanionScanner(
    onPayloadFound: (CompanionDiscoveryPayload) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val json = remember { Json { ignoreUnknownKeys = true } }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasCameraPermission = granted
            if (!granted) {
                Timber.w("COMPANION: Camera permission denied by user")
            }
        }
    )
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            Timber.d("COMPANION: Requesting camera permission")
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            try {
                if (cameraProviderFuture.isDone) {
                    cameraProviderFuture.get().unbindAll()
                }
            } catch (_: Exception) {
            }
            analysisExecutor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (hasCameraPermission) {
            AndroidView(
                factory = { context ->
                    Timber.d("COMPANION: Initializing CameraX PreviewView")
                    val previewView = PreviewView(context).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    }
                    val executor = ContextCompat.getMainExecutor(context)
                    cameraProviderFuture.addListener({
                        try {
                            val cameraProvider = cameraProviderFuture.get()

                            val resolutionSelector = ResolutionSelector.Builder()
                                .setAspectRatioStrategy(
                                    androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY,
                                )
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        Size(1280, 720),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                    ),
                                )
                                .build()

                            val preview = Preview.Builder()
                                .setResolutionSelector(resolutionSelector)
                                .build()
                                .also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }

                            val imageAnalysis = ImageAnalysis.Builder()
                                .setResolutionSelector(resolutionSelector)
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()

                            val options = BarcodeScannerOptions.Builder()
                                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                                .build()
                            val scanner = runCatching { BarcodeScanning.getClient(options) }.getOrNull()
                            
                            var frameCount = 0
                            imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                                if (frameCount++ % 100 == 0) {
                                    Timber.d("COMPANION: Analyzer heartbeat - frame ${imageProxy.width}x${imageProxy.height} rotation=${imageProxy.imageInfo.rotationDegrees}")
                                }
                                processImageProxy(scanner, imageProxy, onPayloadFound, json)
                            }

                            val cameraSelector = when {
                                cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                                cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                                else -> {
                                    Timber.w("COMPANION: No camera available on device")
                                    return@addListener
                                }
                            }

                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                imageAnalysis
                            )
                            Timber.d("COMPANION: CameraX bound successfully")
                        } catch (e: Exception) {
                            Timber.e(e, "COMPANION: Camera binding failed")
                        }
                    }, executor)
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Camera permission is required to scan QR code")
                Button(
                    onClick = { launcher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text("Grant Permission")
                }
            }
        }
    }
}

@SuppressLint("UnsafeOptInUsageError")
private fun processImageProxy(
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner?,
    imageProxy: ImageProxy,
    onPayloadFound: (CompanionDiscoveryPayload) -> Unit,
    json: Json
) {
    fun parsePayload(rawValue: String): Boolean {
        return runCatching {
            val payload = if (rawValue.startsWith("sfcp:")) {
                val data = rawValue.substring(5).split("|")
                if (data.size == 2) {
                    CompanionDiscoveryPayload(
                        version = 1,
                        companion_url = data[0],
                        setup_token = data[1]
                    )
                } else {
                    null
                }
            } else {
                json.decodeFromString<CompanionDiscoveryPayload>(rawValue)
            }
            if (payload != null) {
                onPayloadFound(payload)
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }

    fun decodeWithZxingFallback() {
        try {
            val plane = imageProxy.planes.getOrNull(0) ?: return
            val buffer = plane.buffer
            val bytes = ByteArray(buffer.remaining())
            val origPos = buffer.position()
            buffer.get(bytes)
            buffer.position(origPos)

            val zxingResult = QrCodeDecoder.decodeYuv(
                yData = bytes,
                width = imageProxy.width,
                height = imageProxy.height,
                rowStride = plane.rowStride,
                rotationDegrees = imageProxy.imageInfo.rotationDegrees,
            )
            if (zxingResult != null) {
                parsePayload(zxingResult)
            }
        } catch (e: Exception) {
            Timber.v(e, "COMPANION: ZXing decode fallback failed")
        }
    }

    val mediaImage = imageProxy.image
    if (mediaImage == null || scanner == null) {
        try {
            decodeWithZxingFallback()
        } finally {
            imageProxy.close()
        }
        return
    }

    try {
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                var found = false
                for (barcode in barcodes) {
                    val rawValue = barcode.rawValue ?: continue
                    Timber.d("COMPANION: Detected barcode: $rawValue")
                    if (parsePayload(rawValue)) {
                        found = true
                        break
                    }
                }
                if (!found) {
                    decodeWithZxingFallback()
                }
            }
            .addOnFailureListener { error ->
                Timber.w(error, "COMPANION: MLKit scanning failed, trying ZXing fallback")
                decodeWithZxingFallback()
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    } catch (error: Throwable) {
        Timber.w(error, "COMPANION: MLKit synchronous error, falling back to ZXing")
        try {
            decodeWithZxingFallback()
        } finally {
            imageProxy.close()
        }
    }
}
