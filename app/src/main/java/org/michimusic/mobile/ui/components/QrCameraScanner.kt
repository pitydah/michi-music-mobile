package org.michimusic.mobile.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX viewfinder paired with a ZXing QR decoder.
 *
 * CameraX and ZXing core are both FLOSS and run without Google Play Services, so the
 * F-Droid flavor decodes pairing codes with the same code path as the Play build. The
 * scanner reports the first decoded payload exactly once and only forwards strings;
 * validating that the payload is a real `michi://pair` link stays in the pairing flow.
 */
@Composable
fun QrCameraScanner(
    onQrDetected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val delivered = remember { AtomicBoolean(false) }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose { analyzerExecutor.shutdown() }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxSize().testTag("qr_scanner_preview"),
            factory = { viewContext ->
                val previewView = PreviewView(viewContext).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val providerFuture = ProcessCameraProvider.getInstance(viewContext)
                providerFuture.addListener(
                    {
                        val provider = runCatching { providerFuture.get() }.getOrNull()
                        if (provider == null) return@addListener
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(analyzerExecutor) { image ->
                            try {
                                if (!delivered.get()) {
                                    val payload = decodeQrPayload(image)
                                    if (payload != null && delivered.compareAndSet(false, true)) {
                                        provider.unbindAll()
                                        previewView.post { onQrDetected(payload) }
                                    }
                                }
                            } finally {
                                image.close()
                            }
                        }
                        // A device without a back camera, or a provider that is already
                        // bound, must fall back to the manual field instead of crashing.
                        runCatching {
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analysis,
                            )
                        }
                    },
                    ContextCompat.getMainExecutor(viewContext),
                )
                previewView
            },
        )
    }
}

/** True when the app may open the camera right now. */
fun canUseQrScanner(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Decodes the QR payload from the luminance plane of a CameraX frame.
 *
 * The luminance plane honours a row stride that is usually wider than the image, so the
 * rows are copied into a tightly packed buffer before ZXing sees them. Frames that need a
 * rotation are rotated here because the analysis pipeline is not allowed to rotate them.
 */
private fun decodeQrPayload(image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val width = image.width
    val height = image.height
    if (width <= 0 || height <= 0) return null
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    if (rowStride < width || buffer.remaining() < rowStride * (height - 1) + width) return null

    val packed = ByteArray(width * height)
    val row = ByteArray(rowStride)
    buffer.rewind()
    for (y in 0 until height) {
        val read = minOf(rowStride, buffer.remaining())
        if (read < width) return null
        buffer.get(row, 0, read)
        System.arraycopy(row, 0, packed, y * width, width)
    }

    val rotation = image.imageInfo.rotationDegrees
    val (data, dataWidth, dataHeight) = when (rotation) {
        90 -> Triple(rotateQuarterTurns(packed, width, height, clockwise = false), height, width)
        180 -> Triple(rotateHalfTurn(packed, width, height), width, height)
        270 -> Triple(rotateQuarterTurns(packed, width, height, clockwise = true), height, width)
        else -> Triple(packed, width, height)
    }

    return decodeQrLuminance(data, dataWidth, dataHeight)
}

/**
 * Decodes a QR payload from a tightly packed luminance plane.
 *
 * Split from the frame plumbing so the decoder can be exercised without a camera: a
 * unit test feeds a QR generated by ZXing's encoder and expects the same text back.
 */
internal fun decodeQrLuminance(data: ByteArray, width: Int, height: Int): String? {
    if (width <= 0 || height <= 0 || data.size < width * height) return null
    return runCatching {
        val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }.getOrNull()
}

internal fun rotateQuarterTurns(src: ByteArray, width: Int, height: Int, clockwise: Boolean): ByteArray {
    val out = ByteArray(src.size)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val value = src[y * width + x]
            if (clockwise) {
                out[x * height + (height - 1 - y)] = value
            } else {
                out[(width - 1 - x) * height + y] = value
            }
        }
    }
    return out
}

internal fun rotateHalfTurn(src: ByteArray, width: Int, height: Int): ByteArray {
    val out = ByteArray(src.size)
    val last = src.size - 1
    for (index in src.indices) {
        out[last - index] = src[index]
    }
    return out
}
