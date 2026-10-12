import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The call that decides what the real call needs to have read.
 *
 * What is testable here without a model is the half that matters most: what it
 * refuses to read. It names paths and a wrong answer should cost tokens, never
 * reach outside the project, and never turn a question nobody could answer into
 * a question nobody could answer slower.
 */
class PreflightTest {

    private static Preflight on(Path root) {
        return new Preflight(new Aicoin("http://127.0.0.1:1", "anthropic", ""), root);
    }

    @Test
    void aTreeThatIsNotACheckoutTurnsTheWholeThingOff(@TempDir Path dir) {
        var p = on(dir);
        assertFalse(p.available(), "no git, no manifest, and no mechanism");
        assertEquals("", p.gather("wallet", "why does the keyboard not work"));
    }

    /** Where the backend runs from, so ".." is the project. */
    private static final Path PROJECT = Path.of("..");

    @Test
    void theProjectsOwnTreeIsWhatItLists() {
        var p = on(PROJECT);
        assertTrue(p.available());
        assertTrue(p.files() > 50, "it listed " + p.files() + " files");
    }

    @Test
    void aRequestAboutTheirOwnTextIsNotAboutTheMachine() {
        var p = on(PROJECT);
        // Too short to be a description of a behaviour, so it never asks and
        // never pays for the call.
        assertEquals("", p.gather("wallet", "fix this"));
        assertEquals("", p.gather("wallet", ""));
        assertEquals("", p.gather("wallet", null));
    }

    @Test
    void whenNobodyAnswersTheQuestionIsStillAnswerable() {
        // The proxy address is deliberately unreachable. A mechanism that makes
        // answers better must never be the reason there is no answer.
        var p = on(PROJECT);
        assertEquals("", p.gather("wallet", "the trackpad does not work on this machine"));
    }

    @Test
    void itCannotNameAnythingOutsideTheProject(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("secret"), "not yours");
        var inner = Files.createDirectory(dir.resolve("inner"));
        var p = on(inner);
        // Nothing to list, so nothing can be read either - but the check that
        // matters is in read(), which resolves and then asks whether the result
        // is still under the root. "..", an absolute path and a symbolic link
        // all collapse to that one question.
        assertFalse(p.available());
    }
}
