package app.deckmate.shot

import java.io.ByteArrayOutputStream
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
        val o = ByteArrayOutputStream()
        while (true) {
            val b = i.read()
            if (b < 0) return null
            if (b == '
'.code) return o.toString("ISO-8859-1").trimEnd('')
            o.write(b)
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
                        it.getOutputStream().write("HTTP/1.1 $code X
Content-Length: 0
Connection: close

".toByteArray())
                        it.getOutputStream().flush()
                    }
                }
            } catch (e: SocketException) {
            }
        }.start()
    }

    @After
    fun stop() = server.close()

    private fun up(attempts: Int = 3) = Uploader("http://127.0.0.1:${server.localPort}/scout/shot", attempts, 2000, { pauses++ })

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
    fun unauthorizedIsNotRetried() {
        codes.add(401)
        assertEquals(SendResult.BadKey, up().send("k", ByteArray(5)))
        assertEquals(1, hits)
    }

    @Test
    fun tooFastIsNotRetried() {
        codes.add(429)
        assertEquals(SendResult.TooFast, up().send("k", ByteArray(5)))
        assertEquals(1, hits)
    }

    @Test
    fun emptyAndTooLargeMapped() {
        codes.add(400)
        assertEquals(SendResult.Empty, up().send("k", ByteArray(5)))
        hits = 0
        codes.clear()
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
    fun networkErrorRetriedThreeTimes() {
        val port = server.localPort
        server.close()
        val r = Uploader("http://127.0.0.1:$port/scout/shot", 3, 500, { pauses++ }).send("k", ByteArray(5))
        assertTrue(r is SendResult.Network)
        assertEquals(2, pauses)
    }
}
