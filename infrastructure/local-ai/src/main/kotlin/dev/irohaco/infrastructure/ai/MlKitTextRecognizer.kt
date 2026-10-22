package dev.irohaco.infrastructure.ai

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import dev.irohaco.core.contracts.TextRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ML Kit's bundled Japanese recognizer, which reads Latin script too (IDX-003, M2-07). The model ships
 * inside the app, so it works offline and never sends the image out.
 */
class MlKitTextRecognizer : TextRecognizer {
    override val engineRevision = "mlkit-text-recognition-japanese:16.0.1"

    private val client by lazy { TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) }

    override suspend fun recognize(image: ByteArray): String {
        val bitmap = BitmapFactory.decodeByteArray(image, 0, image.size) ?: throw IOException("Not an image")
        try {
            return suspendCancellableCoroutine { continuation ->
                client.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { continuation.resume(it.textBlocks.joinToString("\n") { block -> block.text }) }
                    .addOnFailureListener { continuation.resumeWithException(it) }
            }
        } finally {
            bitmap.recycle()
        }
    }
}
