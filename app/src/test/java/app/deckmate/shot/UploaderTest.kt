package app.deckmate.shot

import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UploaderTest {
    private lateinit var server: ServerSocket
    private val codes = CopyOnWriteArrayList<Int>()
    private val keys = CopyOnWriteArrayList<String?>()
    private val types = CopyOnWriteArrayList<String?>()
    private val bodies = CopyOnWriteArrayList<ByteArray>()
    @Volatile private var hits = 0
    private var pauses = 0

    private fun line(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = i.read()
            if (b < 0) return null
            if (b == 10) return sb.toString().trimEnd(13.toChar())
            sb.append(b.toChar())
        }
    }

    @Before
    fun start() {
        server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        Thread {
            try {
                while (true) {
                    val s = server.accept()
                    s.use {
                        val i = it.getInputStream()
                        line(i)
                        val h = HashMap<String, String>()
                        while (true) {
                            val l = line(i) ?: break
                            if (l.isEmpty()) break
                            val c = l.indexOf(':')
                            h[l.substring(0, c).lowercase()] = l.substring(c + 1).trim()
                        }
                        val n = (h["content-length"] ?: "0").toInt()
                        val body = ByteArray(n)
                        var got = 0
                        while (got < n) {
                            val r = i.read(body, got, n - got)
                            if (r < 0) break
                            got += r
                        }
                        keys.add(h["x-key"])
                        types.add(h["content-type"])
                        bodies.add(body)
                        val code = codes.getOrElse(hits) { codes.last() }
                        hits++
                        val reply = listOf("HTTP/1.1 $code X", "Content-Length: 0", "Connection: close", "", "").joinToString("\r\n")
                        it.getOutputStream().write(reply.toByteArray())
                        it.getOutputStream().flush()
                    }
                }
            } catch (e: SocketException) {
            }
        }.start()
    }

    @After
    fun stop() = server.close()

    private fun up(attempts: Int = 3) =
        Uploader("http://127.0.0.1:${server.localPort}/scout/shot", attempts, 2000, { pauses++ }, requireHttps = false)

    @Test
    fun sendsKeyHeaderAndRawBody() {
        codes.add(200)
        val body = ByteArray(2000) { it.toByte() }
        assertEquals(SendResult.Ok, up().send("my-key", body))
        assertEquals("my-key", keys[0])
        assertEquals("image/jpeg", types[0])
        assertArrayEquals(body, bodies[0])
        assertEquals(1, hits)
    }

    @Test
    fun plainHttpIsRejectedBeforeAnythingIsSent() {
        codes.add(200)
        val r = Uploader("http://127.0.0.1:${server.localPort}/scout/shot").send("secret-key", ByteArray(5))
        assertEquals(SendResult.Insecure, r)
        assertEquals(0, hits)
    }

    @Test
    fun unauthorizedIsNotRetried() {
        codes.add(401)
        assertEquals(SendResult.BadKey, up().send("k", ByteArray(5)))
        assertEquals(1, hits)
    }

    @Test
    fun forbiddenMeansNotOpenYetAndIsNotRetried() {
        codes.add(403)
        assertEquals(SendResult.NotYet, up().send("k", ByteArray(5)))
        assertEquals(1, hits)
    }

    @Test
    fun tooFastIsNotRetried() {
        codes.add(429)
        assertEquals(SendResult.TooFast, up().send("k", ByteArray(5)))
        assertEquals(1, hits)
    }

    @Test
    fun emptyIsMapped() {
        codes.add(400)
        assertEquals(SendResult.Empty, up().send("k", ByteArray(5)))
    }

    @Test
    fun tooLargeIsMapped() {
        codes.add(413)
        assertEquals(SendResult.TooLarge, up().send("k", ByteArray(5)))
    }

    @Test
    fun serverErrorsRetriedThenSucceeds() {
        codes.addAll(listOf(500, 502, 200))
        assertEquals(SendResult.Ok, up().send("k", ByteArray(5)))
        assertEquals(3, hits)
        assertEquals(2, pauses)
    }

    @Test
    fun persistentServerErrorGivesUpAfterThree() {
        codes.add(503)
        assertEquals(SendResult.Server(503), up().send("k", ByteArray(5)))
        assertEquals(3, hits)
    }

    @Test
    fun neverMoreThanThreeAttempts() {
        codes.add(500)
        up(attempts = 10).send("k", ByteArray(5))
        assertEquals(3, hits)
    }

    @Test
    fun networkErrorRetriedThreeTimes() {
        val port = server.localPort
        server.close()
        val r = Uploader("http://127.0.0.1:$port/scout/shot", 3, 500, { pauses++ }, requireHttps = false).send("k", ByteArray(5))
        assertTrue(r is SendResult.Network)
        assertEquals(2, pauses)
    }
}
