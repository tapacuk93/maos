import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Keeping what was gathered, and knowing when to stop offering it.
 *
 * Working out which files a question is about costs a model call and six file
 * reads, and somebody working on one thing asks about it several times. The
 * properties that matter are the ones that stop this becoming wrong: it belongs
 * to a branch, it expires, and a commit expires it immediately rather than
 * leaving a description of the machine as it was before the change somebody
 * just made.
 */
class ContextsTest {

    private static Contexts fresh(Path dir) {
        return new Contexts(dir, 3600);
    }

    @Test
    void whatWasGatheredComesBack(@TempDir Path dir) {
        var c = fresh(dir);
        var kept = c.keep("machine/acct-1", "why the caret blinks",
                List.of("editor/editor.S"), List.of(), "----- editor/editor.S\nthe source\n");
        assertNotNull(kept);
        assertEquals("----- editor/editor.S\nthe source\n",
                c.reuse(kept.id(), "machine/acct-1"));
    }

    @Test
    void itBelongsToABranchAndNotToWhoeverKnowsItsName(@TempDir Path dir) {
        var c = fresh(dir);
        var kept = c.keep("machine/acct-1", "the keyboard", List.of("kernel.S"), List.of(), "x");
        assertNull(c.reuse(kept.id(), "machine/acct-2"),
                "an id names a folder of somebody's source");
        assertTrue(c.live("machine/acct-2").isEmpty());
        assertEquals(1, c.live("machine/acct-1").size());
    }

    @Test
    void oneThatHasRunOutIsNotOffered(@TempDir Path dir) throws Exception {
        var c = new Contexts(dir, 60);
        var kept = c.keep("machine/acct-1", "a thing", List.of("a.S"), List.of(), "x");
        assertNotNull(kept);
        assertTrue(kept.liveAt(System.currentTimeMillis()));
        assertFalse(kept.liveAt(System.currentTimeMillis() + 61_000),
                "the files were read at a moment, and the moment passes");
    }

    @Test
    void aCommitMakesEveryGatheringOnThatBranchWrong(@TempDir Path dir) {
        var c = fresh(dir);
        c.keep("machine/acct-1", "one", List.of("a.S"), List.of(), "x");
        c.keep("machine/acct-1", "two", List.of("b.S"), List.of(), "y");
        c.keep("machine/acct-2", "three", List.of("c.S"), List.of(), "z");
        assertEquals(2, c.stale("machine/acct-1"));
        assertTrue(c.live("machine/acct-1").isEmpty());
        assertEquals(1, c.live("machine/acct-2").size(),
                "somebody else's branch did not move");
    }

    @Test
    void whatIsOfferedSaysWhatItIsAndHowLongItHas(@TempDir Path dir) {
        var c = fresh(dir);
        var kept = c.keep("machine/acct-1", "why the caret blinks",
                List.of("editor/editor.S"), List.of(), "x");
        String offered = kept.offer(System.currentTimeMillis());
        assertTrue(offered.contains(kept.id()), offered);
        assertTrue(offered.contains("why the caret blinks"), offered);
        assertTrue(offered.contains("editor/editor.S"), offered);
        assertTrue(offered.contains("minute"), "age is the honest thing to say: " + offered);
    }

    @Test
    void theyOutliveTheProcessThatMadeThem(@TempDir Path dir) {
        var kept = fresh(dir).keep("machine/acct-1", "the keyboard driver",
                List.of("kernel/xhci.S"), List.of("history"), "the gathered text");
        var later = fresh(dir);
        assertEquals(1, later.size(), "a restart should not throw away live work");
        assertEquals("the gathered text", later.reuse(kept.id(), "machine/acct-1"));
    }

    @Test
    void oneThatRanOutBeforeTheRestartIsNotLoaded(@TempDir Path dir) throws Exception {
        var c = new Contexts(dir, 60);
        var kept = c.keep("machine/acct-1", "a thing", List.of("a.S"), List.of(), "x");
        // Rewrite its expiry into the past, which is what a restart an hour
        // later would find.
        Path about = dir.resolve(kept.id()).resolve("about");
        Files.writeString(about, Files.readString(about)
                .replaceFirst("(?m)^expires \\d+$", "expires 1"));
        assertEquals(0, new Contexts(dir, 60).size());
        assertFalse(Files.exists(dir.resolve(kept.id())), "and the folder goes with it");
    }

    @Test
    void nothingWorthKeepingIsNotKept(@TempDir Path dir) {
        var c = fresh(dir);
        assertNull(c.keep("machine/acct-1", "a thing", List.of(), List.of(), ""));
        assertNull(c.keep("machine/acct-1", "a thing", List.of(), List.of(), null));
        assertNull(c.keep(null, "a thing", List.of(), List.of(), "x"));
        assertEquals(0, c.size());
    }

    @Test
    void anIdNobodyIssuedIsNotAGathering(@TempDir Path dir) {
        var c = fresh(dir);
        assertNull(c.reuse("ctx-nothing", "machine/acct-1"));
        assertNull(c.reuse(null, "machine/acct-1"));
    }
}
