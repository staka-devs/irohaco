package dev.irohaco

import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import kotlin.concurrent.thread

/** A server the user added with an http:// address can be reached, as the remote models' settings allow (#249). */
@RunWith(AndroidJUnit4::class)
class CleartextTrafficTest {
    @Test
    fun thePolicyAllowsHttpToAnyHost() {
        assertTrue(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted)
        assertTrue(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("192.168.1.10"))
    }

    @Test
    fun anHttpRequestGetsItsAnswer() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            thread {
                server.accept().use { socket ->
                    socket.getInputStream().bufferedReader().let { reader -> while (reader.readLine().orEmpty().isNotEmpty()) Unit }
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                }
            }
            val connection = URL("http://127.0.0.1:${server.localPort}/").openConnection() as HttpURLConnection
            try {
                assertEquals(200, connection.responseCode)
                assertEquals("ok", connection.inputStream.bufferedReader().readText())
            } finally {
                connection.disconnect()
            }
        }
    }
}
