package blue.anika.wolle.scanner

import android.graphics.ImageFormat
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import timber.log.Timber

/**
 * Decodes QR/barcodes from CameraX frames with ZXing - no Google Play Services dependency (SNA-13,
 * ported from nyetbox's `BarcodeAnalyzer`).
 */
class BarcodeAnalyzer(private val onResult: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader()

    override fun analyze(image: ImageProxy) {
        try {
            if (image.format != ImageFormat.YUV_420_888) return
            val buffer = image.planes[0].buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)

            val source =
                PlanarYUVLuminanceSource(
                    data,
                    image.width,
                    image.height,
                    0,
                    0,
                    image.width,
                    image.height,
                    false,
                )
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = reader.decodeWithState(bitmap)
            onResult(result.text)
        } catch (_: NotFoundException) {
            // No barcode in this frame - expected on most frames, nothing to log.
        } catch (e: Exception) {
            Timber.w(e, "Barcode decode failed")
        } finally {
            reader.reset()
            image.close()
        }
    }
}
