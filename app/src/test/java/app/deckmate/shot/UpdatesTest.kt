package app.deckmate.shot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test
    fun comparesVersions() {
        assertTrue(Updates.newer("v1.0.18", "1.0.17"))
        assertTrue(Updates.newer("1.0.100", "1.0.99"))
        assertFalse(Updates.newer("1.0.17", "1.0.17"))
        assertFalse(Updates.newer("1.0.9", "1.0.17"))
    }
}
