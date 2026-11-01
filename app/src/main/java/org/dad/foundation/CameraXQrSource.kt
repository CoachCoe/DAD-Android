package org.dad.foundation

import android.content.pm.PackageManager
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/** Feeds camera frames to ML Kit's bundled barcode model and reports every QR string it reads. */
internal class MlKitQrAnalyzer(private val scanner: BarcodeScanner, private val onDecoded: (String) -> Unit) : ImageAnalysis.Analyzer {
    @OptIn(markerClass = [ExperimentalGetImage::class])
    override fun analyze(image: ImageProxy) {
        val media = image.image
        if (media == null) {
            image.close()
            return
        }
        scanner.process(InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees))
            .addOnSuccessListener { codes -> codes.firstNotNullOfOrNull { it.rawValue }?.let(onDecoded) }
            .addOnCompleteListener { image.close() }
    }
}

/**
 * The live viewfinder (CameraX preview + analysis, bound to the lifecycle). Detection is limited to QR codes;
 * analysis runs on its own thread with KEEP_ONLY_LATEST so slow frames are dropped, not queued. Everything is
 * released when the composable leaves composition. Needs the CAMERA permission already granted by the caller.
 */
object CameraXQrSource : QrScannerSource {
    @Composable
    override fun Viewfinder(modifier: Modifier, torchOn: Boolean, onDecoded: (String) -> Unit, onStatus: (ScannerStatus) -> Unit) {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val currentOnDecoded by rememberUpdatedState(onDecoded)
        val currentOnStatus by rememberUpdatedState(onStatus)
        var camera by remember { mutableStateOf<Camera?>(null) }
        val previewView = remember(context) {
            PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        }

        DisposableEffect(lifecycleOwner, previewView) {
            val executor = Executors.newSingleThreadExecutor()
            val scanner = BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
            )
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(executor, MlKitQrAnalyzer(scanner) { text -> currentOnDecoded(text) }) }
            var provider: ProcessCameraProvider? = null
            var disposed = false
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                currentOnStatus(ScannerStatus.Unavailable)
            } else {
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener({
                    if (disposed) return@addListener
                    try {
                        val bound = future.get()
                        provider = bound
                        if (!bound.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                            currentOnStatus(ScannerStatus.Unavailable)
                            return@addListener
                        }
                        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                        bound.unbindAll()
                        val bound1 = bound.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                        camera = bound1
                        currentOnStatus(ScannerStatus.Ready(hasTorch = bound1.cameraInfo.hasFlashUnit()))
                    } catch (_: Exception) {
                        currentOnStatus(ScannerStatus.Unavailable)
                    }
                }, ContextCompat.getMainExecutor(context))
            }
            onDispose {
                disposed = true
                analysis.clearAnalyzer()
                provider?.unbindAll()
                camera = null
                executor.shutdown()
                scanner.close()
            }
        }

        LaunchedEffect(camera, torchOn) { camera?.cameraControl?.enableTorch(torchOn) }
        AndroidView(factory = { previewView }, modifier = modifier)
    }
}
