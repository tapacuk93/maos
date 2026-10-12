import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * The parser every request goes through, and the encoder every reply comes
 * out of. It is flat on purpose, and most of what is worth testing is what it
 * does with the shapes a client actually sends rather than with valid JSON in
 * general.
 */
class JsonTest {

    @Test
    void readsTheFlatObjectAClientSends() {
        var m = Json.parse("{\"mode\":\"aify\",\"screen\":2,\"ok\":true}");
        assertEquals("aify", m.get("mode"));
        assertEquals("2", m.get("screen"), "numbers come back as their text");
        assertEquals("true", m.get("ok"));
    }

    @Test
    void escapesComeBackAsTheCharactersTheyStoodFor() {
        var m = Json.parse("{\"text\":\"one\\ntwo\\t\\\"three\\\"\\\\\"}");
        assertEquals("one\ntwo\t\"three\"\\", m.get("text"));
    }

    @Test
    void aUnicodeEscapeIsOneCharacter() {
        assertEquals("\u00e9", Json.parse("{\"a\":\"\\u00e9\"}").get("a"));
    }

    @Test
    void nothingUsefulOutOfNothing() {
        assertTrue(Json.parse("").isEmpty());
        assertTrue(Json.parse("{}").isEmpty());
    }

    @Test
    void escapingAndUnescapingAreOpposites() {
        String awkward = "a \"quote\", a \\slash, a\nnewline and a \ttab";
        assertEquals(awkward, Json.unescape(Json.escape(awkward)));
    }

    @Test
    void controlCharactersLeaveAsEscapes() {
        assertFalse(Json.escape("a\u0001b").contains("\u0001"),
                "a raw control byte in a JSON string is a parser's problem");
    }

    @Test
    void objWritesNumbersAndBooleansBare() {
        String s = Json.obj("n", 3, "b", true, "s", "x");
        assertTrue(s.contains("\"n\":3"), s);
        assertTrue(s.contains("\"b\":true"), s);
        assertTrue(s.contains("\"s\":\"x\""), s);
    }

    @Test
    void whatObjWritesIsWhatParseReads() {
        var m = Json.parse(Json.obj("text", "with \"quotes\" and \\slashes", "n", 7));
        assertEquals("with \"quotes\" and \\slashes", m.get("text"));
        assertEquals("7", m.get("n"));
    }
}
