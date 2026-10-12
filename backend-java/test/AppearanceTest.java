import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * The two things the editor can be told about how it looks.
 *
 * For a long time there was one of them. "make it blue" worked and "larger
 * font" produced a paragraph about fonts, because the colour directive was
 * written first and nothing followed it. These assertions are mostly that the
 * second one behaves like the first.
 */
class AppearanceTest {

    @Test
    void aSizeIsASizeAndNotADirection() {
        assertEquals(4, MaosServer.scaleIn("#SCALE 4"));
        assertEquals(1, MaosServer.scaleIn("#SCALE 1"));
        assertEquals(8, MaosServer.scaleIn("#SCALE 8"));
        assertEquals(-1, MaosServer.scaleIn("#SCALE larger"),
                "a direction depends on how many times a reply arrived");
    }

    @Test
    void whatTheEditorCannotDrawIsNotAccepted() {
        assertEquals(-1, MaosServer.scaleIn("#SCALE 0"), "there is no size zero");
        assertEquals(-1, MaosServer.scaleIn("#SCALE 9"), "and eight is the largest");
        assertEquals(-1, MaosServer.scaleIn("#SCALE 12"));
        assertEquals(-1, MaosServer.scaleIn(null));
        assertEquals(-1, MaosServer.scaleIn("make the font larger"));
    }

    @Test
    void itHasToBeTheWholeLineLikeEveryOtherOne() {
        assertEquals(-1, MaosServer.scaleIn("how do I #SCALE 4 the font"),
                "a marker in the middle of a sentence is a sentence");
    }

    @Test
    void andItNeverReachesTheScreenAsText() {
        assertEquals("", MaosServer.withoutDirectives("#SCALE 4"));
        assertEquals("there you are", MaosServer.withoutDirectives("there you are\n#SCALE 4"));
    }

    @Test
    void theTwoAreReadIndependently() {
        String both = "#COLOUR 3\n#SCALE 5";
        assertEquals(3, MaosServer.colourIn(both));
        assertEquals(5, MaosServer.scaleIn(both));
        assertEquals(-1, MaosServer.scaleIn("#COLOUR 3"), "a colour is not a size");
        assertEquals(-1, MaosServer.colourIn("#SCALE 5"), "and a size is not a colour");
    }
}
