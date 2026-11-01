package org.dad.foundation

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * What one decoded QR string means for check-in (E-8). The camera delivers the same code on every frame while it
 * is held up, so the gate accepts the FIRST well-formed token, then ignores everything after it (a duplicate frame
 * must never submit twice). A code of another shape is reported and scanning continues. Main-thread only.
 */
class ScanGate(private val onValid: (String) -> Unit, private val onInvalid: () -> Unit) {
    private var accepted = false

    /** True once a valid token has been handed on; later frames are ignored. */
    val isDone: Boolean get() = accepted

    fun offer(decoded: String) {
        if (accepted) return
        val code = decoded.trim()
        if (CheckInCode.isWellFormed(code)) {
            accepted = true
            onValid(code)
        } else {
            onInvalid()
        }
    }
}

/** What the viewfinder reports about itself. */
sealed interface ScannerStatus {
    /** Camera bound and analysing; [hasTorch] is whether the torch toggle should be offered. */
    data class Ready(val hasTorch: Boolean) : ScannerStatus

    /** No usable camera (none on the device, in use elsewhere, or it failed to open). */
    data object Unavailable : ScannerStatus
}

/**
 * A live source of decoded QR strings. The real one is [CameraXQrSource]; instrumentation tests supply a fake so no
 * camera is needed. Implementations must call [onDecoded] on the main thread, and release the camera when the
 * composable leaves composition.
 */
interface QrScannerSource {
    @Composable
    fun Viewfinder(modifier: Modifier, torchOn: Boolean, onDecoded: (String) -> Unit, onStatus: (ScannerStatus) -> Unit)
}

/**
 * The platform pieces the scanner leans on, gathered so a test can replace them: the viewfinder source and the
 * camera-permission checks. [permissionContract] is the request contract; a test's contract can answer
 * synchronously (via `getSynchronousResult`) so no system dialog appears.
 */
internal class ScannerEnvironment(
    val source: QrScannerSource = CameraXQrSource,
    val isGranted: (Context) -> Boolean = { context ->
        androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    },
    val permissionContract: ActivityResultContract<String, Boolean> = ActivityResultContracts.RequestPermission(),
    /** After a refusal: true when the system would still show its dialog (a plain denial); false when it is permanent. */
    val canAskAgain: (Context) -> Boolean = { context ->
        generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull()
            ?.let { androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(it, android.Manifest.permission.CAMERA) } ?: false
    },
)

internal object ScannerEnvironments {
    /** Replaced only by instrumentation tests (same process); production always uses the defaults. */
    @Volatile var current: ScannerEnvironment = ScannerEnvironment()
}
