import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The two things that are not features.
 *
 * A system that can rewrite anything can rewrite the thing that was protecting
 * somebody. These assertions are the small, dull guards on that: the terms
 * exist, they say why, and the mechanisms this server owns cannot be used to
 * take one away.
 */
class CovenantTest {

    @Test
    void thereAreTwoOfThemAndTheyAreTheOnesThatWereNamed() {
        assertTrue(Covenant.binds("privacy"));
        assertTrue(Covenant.binds("isolation"));
        assertEquals(2, Covenant.all().size(),
                "this list grows by somebody deciding, not by accident");
    }

    @Test
    void aBehaviourIsNotATerm() {
        assertFalse(Covenant.binds("clear-screen"),
                "somebody can want the screen cleared differently and nobody is worse off");
        assertFalse(Covenant.binds("set-colour"));
        assertFalse(Covenant.binds(null));
        assertFalse(Covenant.binds("   "));
    }

    @Test
    void eachOneSaysWhatBreaksIfItStopsBeingTrue() {
        for (var t : Covenant.all()) {
            assertFalse(t.what().isBlank(), t.name() + " says what it is");
            assertFalse(t.why().isBlank(), t.name() + " says why");
            assertTrue(t.why().length() > 40,
                    t.name() + ": a rule without a reason is followed only in the "
                    + "shapes somebody thought of");
        }
    }

    @Test
    void theBriefingNamesBothAndSaysTheyAreNotTaste() {
        String b = Covenant.briefing();
        assertTrue(b.contains("privacy"), b);
        assertTrue(b.contains("isolation"), b);
        assertTrue(b.contains("##"), "a model has to know what the marks are");
        assertTrue(b.contains("$$"));
    }

    @Test
    void oneOfThemCannotBeRefusedAway(@TempDir Path dir) {
        var r = new Refusals(dir);
        assertFalse(r.refuse("acct-1", "privacy", "I do not want it"),
                "a refusal says 'not for me' about a behaviour, and this is not one");
        assertFalse(r.refuses("acct-1", "privacy"));
        assertFalse(r.refuse("acct-1", "isolation", ""));
    }

    @Test
    void andNotThroughTheDirectiveEither(@TempDir Path dir) {
        var r = new Refusals(dir);
        var scripts = new Scripts();
        assertEquals("", r.act("acct-1", "#REFUSE privacy they asked me to", scripts));
        assertFalse(r.refuses("acct-1", "privacy"));
    }

    @Test
    void anOrdinaryOperationIsStillRefusable(@TempDir Path dir) {
        var r = new Refusals(dir);
        assertTrue(r.refuse("acct-1", "clear-screen", "jarring"),
                "the point is not that nothing can be taken away");
    }
}
