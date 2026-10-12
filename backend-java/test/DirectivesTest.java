import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * The markers a model writes to the server, and the one rule they all share:
 * the person never sees them.
 *
 * Every one of these has reached somebody's screen as literal text at some
 * point, which is why withoutDirectives exists and why it is tested against
 * each marker separately rather than against a representative one.
 */
class DirectivesTest {

    @Test
    void aColourIsASlotAndNotAName() {
        assertEquals(3, MaosServer.colourIn("#COLOUR 3"));
        assertEquals(3, MaosServer.colourIn("#COLOR 3"), "the American spelling too");
        assertEquals(4, MaosServer.colourIn("#colour 4"), "case does not matter");
        assertEquals(0, MaosServer.colourIn("#COLOUR 0"), "slot zero is a slot");
        assertEquals(-1, MaosServer.colourIn("#COLOUR blue"), "a name is not a slot");
        assertEquals(-1, MaosServer.colourIn("make it blue"), "and prose is not a directive");
        assertEquals(-1, MaosServer.colourIn(null));
    }

    @Test
    void aRestartMustBeTheWholeLine() {
        assertTrue(MaosServer.restartIn("#REBOOT"));
        assertTrue(MaosServer.restartIn("here you are\n#REBOOT\n"));
        assertFalse(MaosServer.restartIn("how do I #REBOOT a linux box"),
                "a directive in the middle of a sentence is a sentence");
        assertFalse(MaosServer.restartIn(null));
    }

    @Test
    void clearingSaysSoRatherThanBeingSilent() {
        assertTrue(MaosServer.clearIn("#CLEAR"));
        assertFalse(MaosServer.clearIn(""), "an empty reply is not a request to empty the screen");
        assertFalse(MaosServer.clearIn("clear the array"));
        assertTrue(MaosServer.wholeIn("#WHOLE\nthe new screen"));
        assertFalse(MaosServer.wholeIn("just this line"));
    }

    @Test
    void everyMarkerIsStrippedBeforeAnybodySeesIt() {
        assertEquals("", MaosServer.withoutDirectives("#CLEAR"));
        assertEquals("here you are", MaosServer.withoutDirectives("#WHOLE\nhere you are"));
        assertEquals("text", MaosServer.withoutDirectives("#COLOUR 3\ntext\n#REBOOT"));
        assertEquals("", MaosServer.withoutDirectives(null));
        assertEquals("a #tag is not a directive",
                MaosServer.withoutDirectives("a #tag is not a directive"),
                "lowercase after the hash is somebody's text");
    }

    @Test
    void fencesComeOutAndTheCodeStays() {
        assertEquals("int main(void){}", MaosServer.plainText("```c\nint main(void){}\n```"));
        assertEquals("", MaosServer.plainText(null));
        assertEquals("", MaosServer.plainText("   "));
    }
}
