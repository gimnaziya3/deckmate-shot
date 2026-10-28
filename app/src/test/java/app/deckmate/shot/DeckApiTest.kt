package app.deckmate.shot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckApiTest {
    @Test
    fun parsesDeck() {
        val r = DeckApi.parse("""{"seq":3,"ok":true,"msg":"Deck","cards":[{"name":"A","elixir":3,"evo":true,"hero":false,"icon":"/img/cards/x.webp"},{"name":"B","elixir":null,"evo":false,"hero":true,"icon":null}]}""")
        assertEquals(3, r.seq)
        assertTrue(r.ok)
        assertEquals(2, r.cards.size)
        assertEquals(3, r.cards[0].elixir)
        assertTrue(r.cards[0].evo)
        assertEquals(null, r.cards[1].elixir)
        assertEquals(null, r.cards[1].icon)
    }

    @Test
    fun parsesFailure() {
        val r = DeckApi.parse("""{"seq":1,"ok":false,"msg":"none","cards":[]}""")
        assertTrue(!r.ok && r.cards.isEmpty() && r.msg == "none")
    }
}
