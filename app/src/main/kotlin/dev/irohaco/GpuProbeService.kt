package dev.irohaco

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.RemoteException
import dev.irohaco.core.contracts.AiBackend
import dev.irohaco.infrastructure.ai.LiteRtEmbeddingRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Runs the embedding model on the GPU once, in its own process (`:gpu_probe`), so a GPU driver that
 * crashes in native code ends only this process (#169). The app reads a dead process as a GPU that
 * does not work.
 */
class GpuProbeService : Service() {
    override fun onBind(intent: Intent): IBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != PROBE) return super.onTransact(code, data, reply, flags)
            val works = try {
                LiteRtEmbeddingRuntime(cacheDir.path).load(data.readString().orEmpty(), AiBackend.GPU).use {
                    it.embedText("probe")
                    it.embedImage(probeImage())
                }
                true
            } catch (e: Throwable) { // a native library that fails to link is an Error
                false
            }
            reply?.writeInt(if (works) 1 else 0)
            return true
        }
    }

    // The process held the model and the GPU driver; ending it gives all of that memory back.
    override fun onDestroy() {
        super.onDestroy()
        Process.killProcess(Process.myPid())
    }

    private fun probeImage(): ByteArray {
        val bitmap = Bitmap.createBitmap(PROBE_IMAGE_PX, PROBE_IMAGE_PX, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    companion object {
        private const val PROBE = IBinder.FIRST_CALL_TRANSACTION
        private const val PROBE_IMAGE_PX = 64

        /** Whether the GPU embeds a text and an image with the model at [modelPath]; takes seconds. */
        suspend fun gpuWorks(context: Context, modelPath: String): Boolean {
            val connected = CompletableDeferred<IBinder>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    connected.complete(service)
                }

                override fun onServiceDisconnected(name: ComponentName) = Unit

                override fun onBindingDied(name: ComponentName) {
                    connected.completeExceptionally(DeadObjectException())
                }
            }
            if (!context.bindService(Intent(context, GpuProbeService::class.java), connection, Context.BIND_AUTO_CREATE)) return false
            return try {
                val binder = connected.await()
                withContext(Dispatchers.IO) {
                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    try {
                        data.writeString(modelPath)
                        binder.transact(PROBE, data, reply, 0)
                        reply.readInt() == 1
                    } finally {
                        data.recycle()
                        reply.recycle()
                    }
                }
            } catch (e: RemoteException) { // the driver took the probe's process down
                false
            } finally {
                context.unbindService(connection)
            }
        }
    }
}
