import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one direction this system did not have.
 *
 * Everything else makes the editor grow, and growth that nobody can decline is
 * growth imposed. These assertions are mostly about the limits of a refusal:
 * that it belongs to one account, that it can be taken back, and that it does
 * not quietly claim to have removed something it cannot reach.
 */
class RefusalsTest {

    private static Scripts withClearScreen() {
        var s = new Scripts();
        s.learn("""
                #SCRIPT clear-screen :: clear the screen
                #CLEAR
                #END
                """, "opus", true);
        return s;
    }

    @Test
    void refusingIsPerPersonAndNotAVote(@TempDir Path dir) {
        var r = new Refusals(dir);
        assertTrue(r.refuse("acct-1", "clear-screen", "I liked it the old way"));
        assertTrue(r.refuses("acct-1", "clear-screen"));
        assertFalse(r.refuses("acct-2", "clear-screen"),
                "somebody else's objection is not an argument about this account");
    }

    @Test
    void sayingItTwiceIsSayingItOnce(@TempDir Path dir) {
        var r = new Refusals(dir);
        assertTrue(r.refuse("acct-1", "clear-screen", "no"));
        assertFalse(r.refuse("acct-1", "clear-screen", "still no"));
        assertEquals(1, r.all("acct-1").size());
    }

    @Test
    void peopleChangeTheirMindsAndThatIsAsCheap(@TempDir Path dir) {
        var r = new Refusals(dir);
        r.refuse("acct-1", "clear-screen", "");
        assertTrue(r.restore("acct-1", "clear-screen"));
        assertFalse(r.refuses("acct-1", "clear-screen"));
        assertFalse(r.restore("acct-1", "clear-screen"), "and twice is still once");
    }

    @Test
    void aRefusalSurvivesARestart(@TempDir Path dir) {
        new Refusals(dir).refuse("acct-1", "clear-screen", "because");
        var later = new Refusals(dir);
        assertTrue(later.refuses("acct-1", "clear-screen"));
        assertEquals("because", later.all("acct-1").get(0).why());
    }

    @Test
    void itSaysWhichThingsItCanActuallyStopDoing(@TempDir Path dir) {
        var scripts = withClearScreen();
        assertFalse(Refusals.needsItsOwnBuild(scripts, "clear-screen"),
                "an operation is served, so refusing it is a filter");
        assertTrue(Refusals.needsItsOwnBuild(scripts, "a1b2c3d"),
                "anything else is already inside a binary somebody is running");
    }

    @Test
    void theModelSaysItAndTheServerActsOnIt(@TempDir Path dir) {
        var r = new Refusals(dir);
        var scripts = withClearScreen();
        String said = r.act("acct-1",
                "right, that one is gone.\n#REFUSE clear-screen you said it was jarring",
                scripts);
        assertTrue(said.contains("clear-screen"), said);
        assertTrue(r.refuses("acct-1", "clear-screen"));
        assertEquals("you said it was jarring", r.all("acct-1").get(0).why());
    }

    @Test
    void refusingSomethingNotServedDoesNotPretendItIsGone(@TempDir Path dir) {
        var r = new Refusals(dir);
        String said = r.act("acct-1", "#REFUSE 34c74bd the caret change", withClearScreen());
        assertTrue(said.contains("built"),
                "it has to say that this one waits for a build: " + said);
    }

    @Test
    void andPuttingOneBack(@TempDir Path dir) {
        var r = new Refusals(dir);
        var scripts = withClearScreen();
        r.act("acct-1", "#REFUSE clear-screen no", scripts);
        String said = r.act("acct-1", "#RESTORE clear-screen", scripts);
        assertTrue(said.contains("restored"), said);
        assertFalse(r.refuses("acct-1", "clear-screen"));
    }

    @Test
    void theModelIsToldSoItDoesNotHelpfullyTeachItBack(@TempDir Path dir) {
        var r = new Refusals(dir);
        assertEquals("", r.briefing("acct-1"), "nothing refused, nothing to say");
        r.refuse("acct-1", "clear-screen", "jarring");
        String b = r.briefing("acct-1");
        assertTrue(b.contains("clear-screen"), b);
        assertTrue(b.contains("jarring"), b);
    }

    @Test
    void nothingIsRefusedByAccident(@TempDir Path dir) {
        var r = new Refusals(dir);
        assertFalse(r.refuse(null, "x", ""));
        assertFalse(r.refuse("acct-1", "  ", ""));
        assertFalse(r.refuses(null, "x"));
        assertFalse(r.refuses("acct-1", null));
        assertEquals("", r.act("acct-1", null, withClearScreen()));
        assertEquals("", r.act("acct-1", "no directive here", withClearScreen()));
        assertTrue(r.all("acct-1").isEmpty());
    }
}
