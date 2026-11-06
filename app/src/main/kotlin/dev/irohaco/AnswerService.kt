package dev.irohaco

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import dev.irohaco.core.contracts.AiBackend
import dev.irohaco.core.contracts.AnswerRuntime
import dev.irohaco.core.contracts.AnswerSession
import dev.irohaco.infrastructure.ai.LiteRtAnswerRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Runs the answer model in its own process (`:answer`). In the app's process, the embedding model using the
 * GPU between two answers left the answer model's next image never coming back (LiteRT-LM 0.19.0, #237);
 * here each process has the GPU to itself. Closing ends the process, so a native close that never returns
 * cannot hold anything up either (#215).
 */
class AnswerService : Service() {
    @Volatile
    private var session: AnswerSession? = null

    override fun onBind(intent: Intent): IBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            // The reply is the failure, null when there is none, and then the result.
            val result: Result<Parcel.() -> Unit> = when (code) {
                LOAD -> runCatching {
                    session = LiteRtAnswerRuntime(cacheDir.path).load(data.readString().orEmpty(), AiBackend.valueOf(data.readString().orEmpty()))
                    val pid = Process.myPid()
                    return@runCatching { writeInt(pid) }
                }
                WARM -> runCatching {
                    runBlocking { checkNotNull(session).warm() }
                    return@runCatching {}
                }
                ANSWER -> runCatching {
                    val instruction = data.readString().orEmpty()
                    val images = List(data.readInt()) { data.createByteArray() ?: ByteArray(0) }
                    val question = data.readString().orEmpty()
                    val think = data.readInt() == 1
                    val listener = data.readStrongBinder()
                    val text = runBlocking {
                        checkNotNull(session).answer(instruction, images, question, think) { so ->
                            val parcel = Parcel.obtain()
                            try {
                                parcel.writeString(so)
                                listener.transact(TEXT, parcel, null, FLAG_ONEWAY)
                            } finally {
                                parcel.recycle()
                            }
                        }
                    }
                    return@runCatching { writeString(text) }
                }
                else -> return super.onTransact(code, data, reply, flags)
            }
            reply?.writeString(result.exceptionOrNull()?.let { "${it::class.simpleName}: ${it.message}" })
            result.getOrNull()?.let { write -> reply?.write() }
            return true
        }
    }

    // The process held the model and the GPU driver; ending it gives all of that memory back.
    override fun onDestroy() {
        super.onDestroy()
        Process.killProcess(Process.myPid())
    }

    /** The app's side: each loaded model is one bound `:answer` process. */
    class Runtime(private val context: Context) : AnswerRuntime {
        override fun load(modelPath: String, backend: AiBackend): AnswerSession {
            val connected = CompletableFuture<IBinder>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    connected.complete(service)
                }

                override fun onServiceDisconnected(name: ComponentName) = Unit

                override fun onBindingDied(name: ComponentName) {
                    connected.completeExceptionally(IllegalStateException("The answer process died"))
                }
            }
            check(context.bindService(Intent(context, AnswerService::class.java), connection, Context.BIND_AUTO_CREATE)) { "Cannot start the answer process" }
            var pid = 0
            try {
                val binder = connected.get(CONNECT_S, TimeUnit.SECONDS)
                pid = call(binder, LOAD, { writeString(modelPath); writeString(backend.name) }) { readInt() }
                return Remote(binder, connection, pid)
            } catch (e: Throwable) {
                end(connection, pid)
                throw e
            }
        }

        private inner class Remote(private val binder: IBinder, private val connection: ServiceConnection, private val pid: Int) : AnswerSession {
            override suspend fun answer(instruction: String, images: List<ByteArray>, question: String, think: Boolean, onText: (String) -> Unit): String =
                withContext(Dispatchers.IO) {
                    val listener = object : Binder() {
                        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                            if (code != TEXT) return super.onTransact(code, data, reply, flags)
                            onText(data.readString().orEmpty())
                            return true
                        }
                    }
                    call(binder, ANSWER, {
                        writeString(instruction)
                        writeInt(images.size)
                        images.forEach(::writeByteArray)
                        writeString(question)
                        writeInt(if (think) 1 else 0)
                        writeStrongBinder(listener)
                    }) { readString().orEmpty() }
                }

            override suspend fun warm() = withContext(Dispatchers.IO) { call(binder, WARM, {}) { } }

            override fun close() = end(connection, pid)
        }

        private fun end(connection: ServiceConnection, pid: Int) {
            context.unbindService(connection)
            // Not left to onDestroy: the next load may bind again before the old process has gone.
            if (pid != 0) Process.killProcess(pid)
        }

        /** One blocking call into the process; a failure there, or the process dying, throws here. */
        private fun <T> call(binder: IBinder, code: Int, write: Parcel.() -> Unit, read: Parcel.() -> T): T {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.write()
                binder.transact(code, data, reply, 0)
                reply.readString()?.let { throw IllegalStateException(it) }
                return reply.read()
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    private companion object {
        const val LOAD = IBinder.FIRST_CALL_TRANSACTION
        const val WARM = IBinder.FIRST_CALL_TRANSACTION + 1
        const val ANSWER = IBinder.FIRST_CALL_TRANSACTION + 2
        const val TEXT = IBinder.FIRST_CALL_TRANSACTION + 3
        const val CONNECT_S = 30L
    }
}
