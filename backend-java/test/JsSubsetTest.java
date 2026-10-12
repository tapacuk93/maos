import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What the JavaScript subset accepts, and what it refuses.
 *
 * The refusals matter more than the acceptances. A body that reaches outside
 * the subset is refused here, on the server, where the model that wrote it can
 * be told - the alternative is machine code on a device, doing something
 * nobody checked, where nobody can be told anything. So each absence is
 * asserted rather than assumed, because an absence that quietly became an
 * acceptance would be found on a phone.
 */
class JsSubsetTest {

    private static final List<String> ARGS = List.of("name", "screen", "subject", "selection");

    private static NativeOp.Blob compile(String src) {
        return Js.compile("probe", src, ARGS);
    }

    private static void refused(String src, String why) {
        assertThrows(RuntimeException.class, () -> compile(src), why);
    }

    @Test
    void theShapeAlmostEveryOperationHas() {
        var b = compile("if (name == \"blue\") { return \"#COLOUR 3\"; } return \"\";");
        assertTrue(b.code().length > 0);
        assertEquals("probe", b.name());
        assertEquals(64, b.sha().length(), "a sha-256, so two versions can be told apart");
    }

    @Test
    void loopsAndArithmeticAndJoinedStrings() {
        assertNotNull(compile(
                "let i = 0; let s = \"\"; while (i < 3) { s = s + name; i = i + 1; } return s;"));
        assertNotNull(compile("let n = 2 * 3 - 1; if (n >= 5) { return \"y\"; } return \"\";"));
        assertNotNull(compile("let n = 1; if (n <= 1) { return \"y\"; } else { return \"n\"; }"));
    }

    @Test
    void theAmbientArgumentsArriveWhetherNamedOrNot() {
        assertNotNull(compile("return screen + subject + selection;"));
    }

    @Test
    void andEverythingElseIsRefused() {
        refused("if (name == \"a\" && name != \"b\") { return \"y\"; } return \"\";", "no &&");
        refused("if (name == \"a\" || name == \"b\") { return \"y\"; } return \"\";", "no ||");
        refused("if (!name) { return \"y\"; } return \"\";", "no !");
        refused("let n = 6 / 2; return \"\";", "no division");
        refused("let xs = [1,2]; return \"\";", "no arrays");
        refused("return name.toUpperCase();", "no method calls");
        refused("return { a: 1 };", "no objects");
    }

    @Test
    void aVariableNobodyPassedIsNotAVariable() {
        refused("return nowhere;", "a name that was never an argument");
    }

    @Test
    void anEmptyBodyIsAnOperationThatDeclinesEverything() {
        // Not refused, which is the right answer and not the obvious one: a
        // body with no statements compiles to a function that returns nothing,
        // and returning nothing is how an operation says "not me". So it is a
        // useless operation rather than an invalid one, and the harm it can do
        // is bounded by the thing it would have to get past to exist.
        var b = compile("");
        assertTrue(b.code().length > 0);
    }
}
