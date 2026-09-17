package ai.nuxie.sdk.network

import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real platform socket behavior; fake disconnect callbacks cannot prove read interruption. */
class HttpCancellationDeviceTest {
    @Test
    fun disconnectInterruptsStalledHeadersAndBody() {
        for (sendHeaders in listOf(false, true)) {
            val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
            val requestReceived = CountDownLatch(1)
            val readingBody = CountDownLatch(1)
            val releaseServer = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(3)
            val cancellation = HttpCancellation()
            val transportError = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            try {
                val serving = executor.submit {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = socket.getInputStream().bufferedReader()
                        while (!input.readLine().isNullOrEmpty()) { }
                        if (sendHeaders) {
                            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n".toByteArray())
                            socket.getOutputStream().flush()
                        }
                        requestReceived.countDown()
                        releaseServer.await(10, TimeUnit.SECONDS)
                    }
                }
                val reading = executor.submit<Boolean> {
                    try {
                        HttpUrlConnectionTransport().open(HttpTransport.Request(
                            URL("http://127.0.0.1:${server.localPort}/video.mp4"), emptyMap(), ByteArray(0),
                            method = "GET", cancellation = cancellation,
                        )).use { response ->
                            readingBody.countDown()
                            response.body.read()
                        }
                        false
                    } catch (error: java.io.IOException) { transportError.set(error); true }
                }
                val reached = requestReceived.await(5, TimeUnit.SECONDS)
                assertTrue("Request reached server: ${transportError.get()}", reached)
                if (sendHeaders) assertTrue(readingBody.await(5, TimeUnit.SECONDS))
                val cancelling = executor.submit { cancellation.cancel() }
                cancelling.get(3, TimeUnit.SECONDS)
                assertTrue("Cancellation must interrupt platform IO", reading.get(3, TimeUnit.SECONDS))
                releaseServer.countDown()
                serving.get(3, TimeUnit.SECONDS)
            } finally {
                releaseServer.countDown()
                server.close()
                executor.shutdownNow()
                executor.awaitTermination(5, TimeUnit.SECONDS)
            }
        }
    }
}
