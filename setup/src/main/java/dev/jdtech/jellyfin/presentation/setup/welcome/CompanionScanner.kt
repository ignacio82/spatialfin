package dev.jdtech.jellyfin.presentation.setup.welcome

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.jdtech.jellyfin.core.util.QrCodeDecoder
import dev.jdtech.jellyfin.models.companion.CompanionDiscoveryPayload
import dev.spatialfin.unified.applock.LocalSystemModalGraceHook
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import timber.log.Timber

fun isCompanionCameraAvailable(context: Context): Boolean {
    if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
        return false
    }
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return false
    return runCatching { cameraManager.cameraIdList.isNotEmpty() }.getOrDefault(false)
}

@Composable
fun CompanionScanner(
    onPayloadFound: (CompanionDiscoveryPayload) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val systemModalGraceHook = LocalSystemModalGraceHook.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val json = remember { Json { ignoreUnknownKeys = true } }
    val deliveredLatch = remember { AtomicBoolean(false) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionDialogOpen = remember { AtomicBoolean(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (permissionDialogOpen.compareAndSet(true, false)) {
                systemModalGraceHook.setSystemModalActive(false)
            }
            hasCameraPermission = granted
            if (!granted) {
                Timber.w("COMPANION: Camera permission denied by user")
            }
        },
    )

    fun requestCameraPermission() {
        if (permissionDialogOpen.compareAndSet(false, true)) {
            systemModalGraceHook.setSystemModalActive(true)
        }
        launcher.launch(Manifest.permission.CAMERA)
    }

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            Timber.d("COMPANION: Requesting camera permission")
            requestCameraPermission()
        }
    }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            if (permissionDialogOpen.compareAndSet(true, false)) {
                systemModalGraceHook.setSystemModalActive(false)
            }
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
                factory = { ctx ->
                    Timber.d("COMPANION: Initializing CameraX PreviewView")
                    val previewView = PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    }
                    val executor = ContextCompat.getMainExecutor(ctx)
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
                                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

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
                                    Timber.d(
                                        "COMPANION: Analyzer heartbeat - frame %dx%d rotation=%d",
                                        imageProxy.width,
                                        imageProxy.height,
                                        imageProxy.imageInfo.rotationDegrees,
                                    )
                                }
                                processImageProxy(scanner, imageProxy, deliveredLatch, onPayloadFound, json)
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
                                imageAnalysis,
                            )
                            Timber.d("COMPANION: CameraX bound successfully")
                        } catch (e: Exception) {
                            Timber.e(e, "COMPANION: Camera binding failed")
                        }
                    }, executor)
                    previewView
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Camera permission is required to scan QR code")
                Button(
                    onClick = { requestCameraPermission() },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("Grant Permission")
                }
            }
        }
    }
}

@SuppressLint("UnsafeOptInUsageError")
private fun processImageProxy(
    barcodeScanner: com.google.mlkit.vision.barcode.BarcodeScanner?,
    imageProxy: ImageProxy,
    deliveredLatch: AtomicBoolean,
    onPayloadFound: (CompanionDiscoveryPayload) -> Unit,
    json: Json,
) {
    if (deliveredLatch.get()) {
        imageProxy.close()
        return
    }
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }

    val zxingResult = try {
        val plane = mediaImage.planes[0]
        val buffer = plane.buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        QrCodeDecoder.decodeYuv(
            yData = data,
            width = imageProxy.width,
            height = imageProxy.height,
            rowStride = plane.rowStride,
            rotationDegrees = imageProxy.imageInfo.rotationDegrees,
        )
    } catch (e: Exception) {
        Timber.d(e, "COMPANION: ZXing decode error")
        null
    }

    if (zxingResult != null) {
        if (tryHandlePayload(zxingResult, deliveredLatch, onPayloadFound, json)) {
            imageProxy.close()
            return
        }
    }

    if (barcodeScanner != null) {
        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        barcodeScanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    val raw = barcode.rawValue ?: continue
                    if (tryHandlePayload(raw, deliveredLatch, onPayloadFound, json)) break
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    } else {
        imageProxy.close()
    }
}

private fun tryHandlePayload(
    rawValue: String,
    deliveredLatch: AtomicBoolean,
    onPayloadFound: (CompanionDiscoveryPayload) -> Unit,
    json: Json,
): Boolean {
    val payload = runCatching { json.decodeFromString<CompanionDiscoveryPayload>(rawValue) }.getOrNull()
        ?: return false
    if (deliveredLatch.compareAndSet(false, true)) {
        onPayloadFound(payload)
        return true
    }
    return false
}
