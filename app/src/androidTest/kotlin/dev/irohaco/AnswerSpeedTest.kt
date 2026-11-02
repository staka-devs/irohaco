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
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
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
 * Arguments: `model` = the catalog id (default lfm3-vl-2b), or `path` = a model file elsewhere; `vision` = cpu (default) or gpu; `text` = gpu (default) or cpu;
 * `warm`, `embeddings`, `embedding` and `embed` replay the order that stopped the answer at its first image (#237).
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
        // warm=text|image: one exchange first, as the app does right after loading.
        args.getString("warm")?.let { warm ->
            engine.createConversation(ConversationConfig(systemInstruction = Contents.of("Reply with OK."), thinkingConfig = ThinkingConfig(false))).use {
                it.sendMessage(Contents.of(listOfNotNull(Content.ImageBytes(picture(3)).takeIf { warm == "image" }) + Content.Text("Hi")))
            }
            Log.i(TAG, "warmed with $warm")
        }
        // embedding=gpu|cpu: the embedding model loaded beside it, as the search before a question does; embed=text|image|none uses it.
        // embeddings=N: the embedding model loaded and closed N - 1 times first, as an idle release and a later search do.
        repeat(args.getString("embeddings", "1").toInt() - 1) {
            val path = File(context.getExternalFilesDir(null), "models/embeddinggemma-2-text-vision-440m.litertlm").path
            EmbeddingEngine(EmbeddingEngineConfig(path, Backend.GPU(), Backend.GPU(), Backend.GPU(), context.cacheDir.path)).apply { initialize(); close() }
            Log.i(TAG, "embedding model loaded and closed")
        }
        val embedding = args.getString("embedding")?.let { name ->
            val path = File(context.getExternalFilesDir(null), "models/embeddinggemma-2-text-vision-440m.litertlm").path
            EmbeddingEngine(EmbeddingEngineConfig(path, backend(name), backend(args.getString("embedvision", name)), backend(name), context.cacheDir.path)).also {
                it.initialize()
                Log.i(TAG, "embedding model on $name loaded")
                when (args.getString("embed", "text")) {
                    "text" -> it.computeEmbedding(listOf(InputData.Text("a train")), EmbeddingOptions(true))
                    "image" -> it.computeEmbedding(listOf(InputData.Image(picture(2))), EmbeddingOptions(true))
                }
                Log.i(TAG, "embedding model used")
            }
        }
        engine.use {
            // rounds=N: N questions with four images, each after a search with the embedding model, as the Ask screen does.
            val rounds = args.getString("rounds")?.toInt()
            for ((round, images) in (rounds?.let { List(it) { 4 } } ?: listOf(0, 1, 4)).withIndex()) {
                if (rounds != null && embedding != null) {
                    embedding.computeEmbedding(listOf(InputData.Text("a train $round")), EmbeddingOptions(true))
                    Log.i(TAG, "round ${round + 1}: searched")
                }
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
        embedding?.close()
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
