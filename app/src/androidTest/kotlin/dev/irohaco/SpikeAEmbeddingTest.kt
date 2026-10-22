package dev.irohaco

import android.content.Context
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sqrt

/**
 * Spike A (docs/verification/AI_SEARCH_SPIKE.md): text-to-image search with EmbeddingGemma 2 on the
 * device. It runs only where the evaluation set was pushed to the app's files, so CI skips it:
 *
 *     Android/data/dev.irohaco/files/spike-a/model.litertlm
 *     Android/data/dev.irohaco/files/spike-a/images/<file>      the photos to search
 *     Android/data/dev.irohaco/files/spike-a/queries.json       [{"query": "花火", "relevant": ["a.jpg", ...]}, ...]
 *
 * Arguments: `backend` = gpu (default) or cpu; `dim` = the Matryoshka output size (default: the model's).
 * The report goes to spike-a/report-<backend>-<dim>.json and to logcat under the tag SpikeA.
 */
@RunWith(AndroidJUnit4::class)
class SpikeAEmbeddingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val dir = File(context.getExternalFilesDir(null), "spike-a")

    @Test
    fun searchesPhotosByText() {
        val model = File(dir, "model.litertlm")
        val queriesFile = File(dir, "queries.json")
        val images = File(dir, "images").listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
        assumeTrue("push the Spike A evaluation set first", model.exists() && queriesFile.exists() && images.isNotEmpty())

        val backendName = args.getString("backend", "gpu")
        val dim = args.getString("dim")?.toIntOrNull()
        fun backend(): Backend = if (backendName == "cpu") Backend.CPU() else Backend.GPU()
        val config = EmbeddingEngineConfig(model.path, backend(), backend(), backend(), context.cacheDir.path)
        val options = EmbeddingOptions(true, null, dim)
        val battery = context.getSystemService(BatteryManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val report = JSONObject()
            .put("backend", backendName).put("dim", dim ?: JSONObject.NULL)
            .put("images", images.size)
            .put("batteryStart", battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("thermalStart", power.currentThermalStatus)
        var peakPssKb = Debug.getPss()

        // Cold starts: create and initialize three times; the last engine stays for the measurements.
        val coldMs = JSONArray()
        var engine: EmbeddingEngine? = null
        repeat(COLD_STARTS) { run ->
            engine?.close()
            val start = SystemClock.elapsedRealtime()
            engine = EmbeddingEngine(config).also { it.initialize() }
            coldMs.put(SystemClock.elapsedRealtime() - start)
            peakPssKb = maxOf(peakPssKb, Debug.getPss())
            Log.i(TAG, "cold start ${run + 1}: ${coldMs.getLong(run)} ms")
        }
        report.put("coldStartMs", coldMs)

        engine!!.use { e ->
            val imageMs = LongArray(images.size)
            val vectors = images.mapIndexed { i, file ->
                val start = SystemClock.elapsedRealtime()
                val v = e.computeEmbedding(listOf(InputData.Image(file.readBytes())), options).embedding
                imageMs[i] = SystemClock.elapsedRealtime() - start
                if (i % 20 == 0) peakPssKb = maxOf(peakPssKb, Debug.getPss())
                v
            }
            report.put("imageMs", stats(imageMs)).put("imagesPerMinute", 60_000.0 * images.size / imageMs.sum().coerceAtLeast(1))

            val cases = JSONArray(queriesFile.readText())
            val queryMs = LongArray(cases.length())
            var precision = 0.0
            var recall = 0.0
            var reciprocal = 0.0
            val perQuery = JSONArray()
            for (q in 0 until cases.length()) {
                val case = cases.getJSONObject(q)
                val relevant = (0 until case.getJSONArray("relevant").length()).map { case.getJSONArray("relevant").getString(it) }.toSet()
                val start = SystemClock.elapsedRealtime()
                // The model card asks for a task prefix on queries; photos go in as they are.
                val query = e.computeEmbedding(listOf(InputData.Text("task: search result | query: ${case.getString("query").trim()}")), options).embedding
                queryMs[q] = SystemClock.elapsedRealtime() - start
                val ranked = vectors.indices.sortedByDescending { cosine(query, vectors[it]) }.map { images[it].name }
                val p10 = ranked.take(10).count { it in relevant } / 10.0
                val r20 = if (relevant.isEmpty()) 0.0 else ranked.take(20).count { it in relevant }.toDouble() / relevant.size
                val firstHit = ranked.indexOfFirst { it in relevant }
                val rr = if (firstHit < 0) 0.0 else 1.0 / (firstHit + 1)
                precision += p10
                recall += r20
                reciprocal += rr
                perQuery.put(JSONObject().put("query", case.getString("query")).put("p10", p10).put("r20", r20).put("rr", rr).put("top5", JSONArray(ranked.take(5))))
            }
            val n = cases.length().coerceAtLeast(1)
            report.put("queryMs", stats(queryMs))
                .put("precisionAt10", precision / n).put("recallAt20", recall / n).put("mrr", reciprocal / n)
                .put("queries", perQuery)
        }
        report.put("peakPssMb", peakPssKb / 1024)
            .put("batteryEnd", battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("thermalEnd", power.currentThermalStatus)
        val out = File(dir, "report-$backendName-${dim ?: "full"}.json")
        out.writeText(report.toString(2))
        Log.i(TAG, "report written to ${out.path}: " + report.toString())
    }

    private fun stats(ms: LongArray): JSONObject {
        val sorted = ms.sorted()
        fun at(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
        return JSONObject().put("count", ms.size).put("avg", ms.average()).put("p50", at(0.5)).put("p95", at(0.95)).put("max", sorted.last())
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        return dot / (sqrt(na) * sqrt(nb)).coerceAtLeast(1e-12)
    }

    private companion object {
        const val TAG = "SpikeA"
        const val COLD_STARTS = 3
    }
}
