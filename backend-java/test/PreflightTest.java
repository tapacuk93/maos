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
    void nothingToAskAboutMeansNoCall() {
        var p = on(PROJECT);
        assertEquals("", p.gather("wallet", ""));
        assertEquals("", p.gather("wallet", null));
    }

    @Test
    void shortnessIsNotEvidenceOfAnything() throws Exception {
        // There was a word count here once: below three words a request was
        // assumed to be about the document. "larger font" is two words and is a
        // request to change the machine; "can you explain what this function
        // does to the array" is eleven about text on a screen. Length is not
        // the signal, so nothing in this class may act on it.
        var p = on(PROJECT);
        String source = new String(java.nio.file.Files.readAllBytes(
                PROJECT.resolve("backend-java/src/Preflight.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(source.contains("MIN_WORDS"),
                "deciding by length is the deciding model's job, not a constant's");
    }

    @Test
    void whenNobodyAnswersTheQuestionIsStillAnswerable() {
        // The proxy address is deliberately unreachable. A mechanism that makes
        // answers better must never be the reason there is no answer.
        var p = on(PROJECT);
        assertEquals("", p.gather("wallet", "the trackpad does not work on this machine"));
    }

    @Test
    void theFilesAQuestionWouldBeAboutAreNotTooBigToRead() throws Exception {
        // The first version capped a single file at twenty-four thousand
        // characters, which excluded the USB driver, the editor core, the
        // client and the server - every file a question about the machine could
        // possibly be about. It would name the right one and read none of it.
        for (String big : new String[]{"kernel/arch/aarch64/xhci.S", "editor/editor.S",
                                       "app/backend_client.S"}) {
            Path p = PROJECT.resolve(big);
            assertTrue(Files.size(p) > 24_000, big + " is no longer the large case");
        }
        assertTrue(Preflight.DEFAULT_ROOM > 200_000,
                "the budget has to hold the files the questions are about");
    }

    @Test
    void noRoomMeansNoSourceRatherThanARequestThatWillBeRefused() {
        var p = on(PROJECT);
        assertEquals("", p.gather("wallet", "the keyboard driver does not see my keyboard", 500));
    }

    @Test
    void whenNothingWasDecidedEverythingIsStillSent() {
        // The one property that keeps this from being a way to send nothing: a
        // request that never reached a model, or reached one that did not
        // answer, must get the context it always got.
        var undecided = Preflight.Needs.undecided();
        for (String kind : Preflight.KINDS.keySet()) {
            assertTrue(undecided.wants(kind), kind + " has to survive an undecided answer");
        }
    }

    @Test
    void aDecidedAnswerSendsWhatItNamedAndNoMore() {
        var needs = new Preflight.Needs(java.util.List.of("editor/editor.S"),
                java.util.Set.of("history"), true);
        assertTrue(needs.wants("history"));
        assertFalse(needs.wants("clouds"), "a question about typing is not about EC2");
        assertFalse(needs.wants("models"));
    }

    @Test
    void theVocabularyIsAboutWhatSomebodyWouldWantItFor() {
        // Every kind the server gates on has to be one the asking model was
        // offered, or it is a briefing that can never be asked for and will
        // therefore never be sent.
        for (String gated : new String[]{"history", "clouds", "machines",
                                         "operations", "models", "refusals", "agents"}) {
            assertTrue(Preflight.KINDS.containsKey(gated),
                    gated + " is gated on but never offered");
        }
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
