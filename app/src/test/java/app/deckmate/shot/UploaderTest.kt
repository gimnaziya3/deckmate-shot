package app.deckmate.shot

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UploaderTest {
    private lateinit var server: HttpServer
    private val codes = CopyOnWriteArrayList<Int>()
    private val keys = CopyOnWriteArrayList<String?>()
    private val types = CopyOnWriteArrayList<String?>()
    private val bodies = CopyOnWriteArrayList<ByteArray>()
    private var hits = 0
    private var pauses = 0

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/scout/shot") { ex ->
            keys.add(ex.requestHeaders.getFirst("X-Key"))
            types.add(ex.requestHeaders.getFirst("Content-Type"))
            bodies.add(ex.requestBody.readBytes())
            val code = codes.getOrElse(hits) { codes.last() }
            hits++
            ex.sendResponseHeaders(code, -1)
            ex.close()
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun up(attempts: Int = 3) = Uploader("http://127.0.0.1:${server.address.port}/scout/shot", attempts, 2000, { pauses++ })

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
        val port = server.address.port
        server.stop(0)
        val r = Uploader("http://127.0.0.1:$port/scout/shot", 3, 500, { pauses++ }).send("k", ByteArray(5))
        assertTrue(r is SendResult.Network)
        assertEquals(2, pauses)
    }
}
