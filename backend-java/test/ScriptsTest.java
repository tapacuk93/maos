import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * The server as a cache the model writes to.
 *
 * Most of what is worth testing here is refusal: a pattern that is too loose,
 * a script taught from somebody's private screen, a body that will not
 * compile. A script that fires when it should not is the one failure in this
 * system that nobody can see happening.
 */
class ScriptsTest {

    private static final String HELLO = """
            #SCRIPT hello-world :: {lang:lang} hello world
            #WHEN screen = empty
            a program in {lang}
            #END
            """;

    private static Scripts.Ctx ctx(String instruction) {
        return new Scripts.Ctx("aify", instruction, "", "", "");
    }

    @Test
    void aTaughtScriptAnswersTheNextOneLikeIt() {
        var s = new Scripts();
        assertEquals(1, s.learn(HELLO, "opus", true).size());
        var hit = s.lookup(ctx("c hello world"));
        assertNotNull(hit);
        assertEquals("a program in c", hit.text());
        assertEquals(1, s.served());
    }

    @Test
    void aTypedVariableIsWhatMakesItUnderstandRatherThanMatch() {
        var s = new Scripts();
        s.learn(HELLO, "opus", true);
        assertNull(s.lookup(ctx("my hello world")),
                "'my' is not a language, so this is somebody's own work");
    }

    @Test
    void aConditionIsAboutTheStateAndNotTheSentence() {
        var s = new Scripts();
        s.learn(HELLO, "opus", true);
        var busy = new Scripts.Ctx("aify", "c hello world", "a program already here", "", "");
        assertNull(s.lookup(busy), "the screen is not empty, so this is not that request");
    }

    @Test
    void nothingPrivateMayBecomeGeneral() {
        var s = new Scripts();
        assertTrue(s.learn(HELLO, "opus", false).isEmpty(),
                "the exchange it came from was not shareable");
        assertEquals(0, s.size());
    }

    @Test
    void aPatternThatWouldSwallowEverythingIsRefused() {
        var s = new Scripts();
        s.learn("""
                #SCRIPT catch-all :: {anything}
                whatever you asked for
                #END
                """, "opus", true);
        assertEquals(0, s.size(), "a pattern that is one free variable is not a pattern");
    }

    @Test
    void theFirstTeacherOfAPatternKeepsIt() {
        var s = new Scripts();
        s.learn(HELLO, "opus", true);
        s.learn("""
                #SCRIPT hello-again :: {lang:lang} hello world
                something else entirely
                #END
                """, "sonnet", true);
        assertEquals(1, s.size());
        assertEquals("a program in c", s.lookup(ctx("c hello world")).text());
    }

    @Test
    void anAliasIsTheSameRequestTypedAnotherWay() {
        var s = new Scripts();
        s.learn("""
                #SCRIPT clear-screen :: clear the screen
                #CLEAR
                #END
                """, "opus", true);
        var original = s.byPattern("clear the screen");
        assertNotNull(original);
        var alias = s.alias(original, "cls");
        assertNotNull(alias);
        assertEquals(original.name(), alias.name());
        assertNotNull(s.lookup(ctx("cls")));
        assertSame(original.hits(), alias.hits(), "three phrasings are one request");
    }

    @Test
    void anAliasWithAVariableInItIsNotAnAlias() {
        var s = new Scripts();
        s.learn(HELLO, "opus", true);
        var op = s.byPattern("{lang:lang} hello world");
        assertNotNull(op);
        assertNull(s.alias(op, "{x} hello world"),
                "an alias is a sentence somebody typed, not a new pattern");
        assertNull(s.alias(op, "   "));
        assertNull(s.alias(null, "anything"));
    }

    @Test
    void anAliasCannotStealAPatternThatIsTaken() {
        var s = new Scripts();
        s.learn(HELLO, "opus", true);
        var op = s.byPattern("{lang:lang} hello world");
        assertNull(s.alias(op, "{lang:lang} hello world"));
    }

    @Test
    void theDevicePatternSpendsTheTypesAndKeepsTheArity() {
        var s = new Scripts();
        s.learn("""
                #SCRIPT set-colour :: colours {name:colour}
                #JS
                if (name == "blue") { return "#COLOUR 3"; }
                return "";
                #END
                """, "opus", true);
        var op = s.byPattern("colours {name:colour}");
        assertNotNull(op);
        assertEquals("colours {}", Scripts.devicePattern(op));
    }

    @Test
    void aJsBodyBecomesMachineCode() {
        var s = new Scripts();
        s.learn("""
                #SCRIPT set-colour :: colours {name:colour}
                #JS
                if (name == "blue") { return "#COLOUR 3"; }
                return "";
                #END
                """, "opus", true);
        var op = s.byPattern("colours {name:colour}");
        assertNotNull(op.blob(), "a #JS body is the rung that reaches a device");
        assertTrue(op.blob().code().length > 0);
        assertEquals("#COLOUR 3", s.lookup(ctx("colours blue")).text());
        assertNull(s.lookup(ctx("colours chartreuse")), "returning nothing is declining");
    }

    @Test
    void onlyOperationsWantedOftenEnoughAreOfferedToDevices() {
        var s = new Scripts();
        s.learn("""
                #SCRIPT set-colour :: colours {name:colour}
                #JS
                if (name == "blue") { return "#COLOUR 3"; }
                return "";
                #END
                """, "opus", true);
        assertEquals(0, s.nativeOps(3).size(), "nobody has wanted it yet");
        s.lookup(ctx("colours blue"));
        s.lookup(ctx("colours blue"));
        s.lookup(ctx("colours blue"));
        assertEquals(1, s.nativeOps(3).size());
        assertEquals(1, s.nativeOps().size());
    }

    @Test
    void theTeachingNeverReachesTheUser() {
        assertEquals("here you are", Scripts.without("here you are\n" + HELLO).strip());
        assertEquals("", Scripts.without(null));
    }

    @Test
    void oneCanBeTakenBackOutByName() {
        var s = new Scripts();
        s.learn(HELLO, "opus", true);
        assertNotNull(s.byName("hello-world"));
        assertTrue(s.forget("hello-world"));
        assertFalse(s.forget("hello-world"));
        assertNull(s.byName("hello-world"));
    }

    @Test
    void theBriefingListsWhatIsAlreadyThere() {
        var s = new Scripts();
        s.learn(HELLO, "opus", true);
        assertTrue(s.briefing().contains("hello world"));
        assertTrue(s.inventory().contains("hello-world"));
        assertTrue(s.asJson().contains("count"));
    }

    @Test
    void nothingAtAllIsNotARequest() {
        var s = new Scripts();
        assertNull(s.lookup(ctx(null)));
        assertNull(s.lookup(ctx("   ")));
        assertNull(s.byPattern(null));
        assertTrue(s.learn(null, "opus", true).isEmpty());
    }
}
