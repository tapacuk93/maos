import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * The exact-question cache, and the one rule that keeps it from leaking:
 * anything carrying a reference to text the server cannot see is nobody's but
 * the asker's.
 */
class CacheTest {

    @Test
    void aMarkedReferenceMakesAnExchangePrivate() {
        assertTrue(Cache.shareable("write a hello world"));
        assertFalse(Cache.shareable("summarise $$notes"),
                "$$notes names something only this person has");
        assertFalse(Cache.shareable("fine", "but $$this is not"));
    }

    @Test
    void nothingAtAllIsNotShareable() {
        assertFalse(Cache.shareable(), "there is no exchange to share");
        assertFalse(Cache.shareable("", "   ", (String) null));
    }

    @Test
    void caseAndSpacingAreNoiseAndNothingElseIs() {
        assertEquals(Cache.key("aify", "Tetris Game"), Cache.key("aify", "tetris  game"));
        assertNotEquals(Cache.key("aify", "tetris game"), Cache.key("agent", "tetris game"),
                "the mode shaped the answer, so it shapes the key");
        assertNotEquals(Cache.key("aify", "a", "b"), Cache.key("aify", "ab"),
                "two fields are not one field with the same letters");
    }

    @Test
    void aMissIsCountedAndSoIsAHit() {
        var c = new Cache();
        assertNull(c.get("nothing"));
        c.put("k", "an answer", "opus");
        var e = c.get("k");
        assertNotNull(e);
        assertEquals("an answer", e.text());
        assertEquals("opus", e.model());
        assertEquals(1, c.hits());
        assertEquals(1, c.misses());
        assertEquals(1, e.hits().get());
    }

    @Test
    void anEmptyAnswerIsNotWorthRemembering() {
        var c = new Cache();
        c.put("k", "   ", "opus");
        c.put("k2", null, "opus");
        assertNull(c.get("k"));
        assertNull(c.get("k2"));
    }

    @Test
    void theFirstAnswerKeepsItsPlace() {
        var c = new Cache();
        c.put("k", "first", "opus");
        c.put("k", "second", "sonnet");
        assertEquals("first", c.get("k").text());
    }

    @Test
    void oneCanBeTakenBackOut() {
        var c = new Cache();
        c.put("k", "answer", "opus");
        assertTrue(c.forget("k"));
        assertFalse(c.forget("k"));
        assertNull(c.get("k"));
    }
}
