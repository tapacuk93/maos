import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Which model gets the work, decided from the text and nothing cleverer. */
class RouterTest {

    @Test
    void theTiersAreOrderedByWhatTheyCost() {
        assertNotNull(Router.cheapest());
        assertNotNull(Router.strongest());
        assertTrue(Router.strongest().costPerMTok() >= Router.cheapest().costPerMTok());
    }

    @Test
    void aTierIsFoundByItsNameAndNotByAnythingElse() {
        assertEquals(Router.cheapest(), Router.byName(Router.cheapest().name()));
        assertNull(Router.byName("a model nobody configured"));
        assertNull(Router.byName(null));
    }

    @Test
    void infrastructureGoesToTheStrongestWhateverItsLength() {
        assertEquals(Router.strongest(), Router.choose("aify", "deploy this", ""));
        assertEquals(Router.strongest(), Router.choose("aify", "an s3:// path", ""));
    }

    @Test
    void soDoesSomethingLongOrSomethingThatChangedALot() {
        assertEquals(Router.strongest(), Router.choose("aify", "x".repeat(5000), ""));
        assertEquals(Router.strongest(), Router.choose("aify", "x".repeat(2000), ""),
                "two thousand characters against an empty baseline is a big edit");
    }

    @Test
    void plainProseTakesTheCheapestThereIs() {
        assertEquals(Router.cheapest(), Router.choose("aify", "a shopping list", ""));
        assertEquals(Router.cheapest(), Router.choose("aify", null, null));
    }

    @Test
    void codeGoesSomewhereInTheMiddle() {
        var t = Router.choose("aify", "class Thing {}", "class Thing {}");
        assertNotEquals(Router.cheapest(), t);
    }

    @Test
    void aHandoffIsReadAndThenTakenOut() {
        String name = Router.strongest().name();
        String reply = "#HANDOFF " + name + " this needs more thought\nthe answer";
        var h = Router.requested(reply);
        assertNotNull(h);
        assertEquals(Router.strongest(), h.to());
        assertTrue(h.reason().contains("more thought"));
        assertEquals("the answer", Router.withoutHandoff(reply));
    }

    @Test
    void aHandoffToNobodyIsNotAHandoff() {
        assertNull(Router.requested("#HANDOFF nosuchmodel because"));
        assertNull(Router.requested("no handoff here"));
        assertNull(Router.requested(null));
        assertEquals("", Router.withoutHandoff(null));
    }

    @Test
    void theBriefingNamesWhereItCanGo() {
        String b = Router.briefing(Router.cheapest());
        assertTrue(b.contains("#HANDOFF"), b);
    }
}
