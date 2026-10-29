package dev.irohaco

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Where an answer's time goes on the device (#48): loading, the images, the first token and the rest.
 * Runs only where an answer model was downloaded in Settings, so CI skips it.
 *
 * Arguments: `model` = the catalog id (default lfm3-vl-2b), or `path` = a model file elsewhere; `vision` = cpu (default) or gpu; `text` = gpu (default) or cpu.
 * The results go to logcat under the tag AnswerSpeed.
 */
@RunWith(AndroidJUnit4::class)
class AnswerSpeedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun measuresAnAnswer() = runBlocking {
        val model = args.getString("path")?.let(::File) ?: File(context.getExternalFilesDir(null), "models/${args.getString("model", "lfm3-vl-2b")}.litertlm")
        if (!model.exists()) {
            Log.i(TAG, "no model at ${model.path}")
            return@runBlocking
        }
        fun backend(name: String): Backend = if (name == "cpu") Backend.CPU() else Backend.GPU()
        val text = args.getString("text", "gpu")
        val vision = args.getString("vision", "cpu")
        var start = SystemClock.elapsedRealtime()
        val engine = Engine(EngineConfig(model.path, backend(text), backend(vision), null, null, 4, context.cacheDir.path)).also { it.initialize() }
        Log.i(TAG, "${model.name} text=$text vision=$vision load ${SystemClock.elapsedRealtime() - start} ms")
        engine.use {
            for (images in listOf(0, 1, 4)) {
                engine.createConversation(ConversationConfig(systemInstruction = Contents.of("Answer briefly."), thinkingConfig = ThinkingConfig(false))).use { conversation ->
                    val pictures = List(images) { Content.ImageBytes(picture(it)) }
                    start = SystemClock.elapsedRealtime()
                    var first = -1L
                    var tokens = 0
                    val reply = StringBuilder()
                    conversation.sendMessageAsync(Contents.of(pictures + Content.Text("What colors are in the pictures? Answer in one sentence."))).collect { message ->
                        if (first < 0) first = SystemClock.elapsedRealtime() - start
                        tokens++
                        message.contents.contents.filterIsInstance<Content.Text>().forEach { reply.append(it.text) }
                    }
                    Log.i(TAG, "$images images: first token ${first} ms, all ${SystemClock.elapsedRealtime() - start} ms, $tokens chunks: $reply")
                }
            }
        }
    }

    private fun picture(i: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(listOf(Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW)[i])
            drawCircle(256f, 256f, 120f, Paint().apply { color = Color.WHITE })
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private companion object {
        const val TAG = "AnswerSpeed"
    }
}
