import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Writing the machine's own sources from inside it.
 *
 * What is worth asserting without running a build is what it refuses: a path
 * that leaves the tree, a file the project does not have, an answer with no
 * request in it. The build is the gate and a test that stubbed the build would
 * be testing the stub, so the passing case here goes as far as "it wrote the
 * files and then tried to build them" and the build is proved by `make test`
 * being the thing that gates it.
 */
class WorkshopTest {

    /** A repository with one file, and a build that always says yes or no. */
    private static Path repo(Path dir, boolean buildPasses) throws Exception {
        run(dir, "git", "init", "-q", "-b", "main");
        run(dir, "git", "config", "user.email", "test@example.com");
        run(dir, "git", "config", "user.name", "test");
        Files.writeString(dir.resolve("editor.S"), "the editor as it was\n");
        Path build = dir.resolve("build.sh");
        Files.writeString(build, buildPasses ? "#!/bin/sh\nexit 0\n"
                                             : "#!/bin/sh\necho 'editor.S:1: syntax error'\nexit 1\n");
        build.toFile().setExecutable(true);
        run(dir, "git", "add", "-A");
        run(dir, "git", "commit", "-q", "-m", "first");
        return dir;
    }

    private static void run(Path where, String... argv) throws Exception {
        var p = new ProcessBuilder(argv).directory(where.toFile())
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor(), String.join(" ", argv));
    }

    private static Workers.Worker worker(Path root, Path pool) {
        return new Workers(root, pool).forAccount("acct-1");
    }

    @Test
    void anAnswerWithNoRequestInItDoesNothing(@TempDir Path dir) throws Exception {
        var root = repo(Files.createDirectory(dir.resolve("repo")), true);
        var w = new Workshop(root, "./build.sh", 60, false);
        assertFalse(w.asked("here is some text for your screen"));
        assertFalse(w.apply(worker(root, dir.resolve("pool")),
                "here is some text for your screen").wrote());
        assertFalse(w.apply(null, "#WRITE editor.S\nx\n#ENDWRITE").wrote(),
                "no tree, nothing written");
    }

    @Test
    void itWritesBuildsAndCommitsToTheirOwnBranch(@TempDir Path dir) throws Exception {
        var root = repo(Files.createDirectory(dir.resolve("repo")), true);
        var w = new Workshop(root, "./build.sh", 60, false);
        var worker = worker(root, dir.resolve("pool"));

        var r = w.apply(worker, "done.\n#WRITE editor.S\nthe editor as it now is\n#ENDWRITE");
        assertTrue(r.wrote());
        assertTrue(r.built(), r.log());
        assertTrue(r.committed(), r.log());
        assertEquals("machine/acct-1", r.branch());
        assertEquals("the editor as it now is\n",
                Files.readString(worker.tree().resolve("editor.S")));
        assertEquals("the editor as it was\n", Files.readString(root.resolve("editor.S")),
                "the tree somebody is working in is not touched");
    }

    @Test
    void aChangeThatDoesNotBuildIsNotCommitted(@TempDir Path dir) throws Exception {
        var root = repo(Files.createDirectory(dir.resolve("repo")), false);
        var w = new Workshop(root, "./build.sh", 60, false);
        var worker = worker(root, dir.resolve("pool"));

        var r = w.apply(worker, "#WRITE editor.S\nbroken\n#ENDWRITE");
        assertTrue(r.wrote());
        assertFalse(r.built());
        assertFalse(r.committed());
        assertTrue(r.log().contains("syntax error"),
                "the model has to be handed what the compiler said: " + r.log());
        assertTrue(r.said().contains("did not build"), r.said());
    }

    @Test
    void whatFailedIsLeftInTheTreeToBeFixed(@TempDir Path dir) throws Exception {
        var root = repo(Files.createDirectory(dir.resolve("repo")), false);
        var worker = worker(root, dir.resolve("pool"));
        new Workshop(root, "./build.sh", 60, false)
                .apply(worker, "#WRITE editor.S\nbroken\n#ENDWRITE");
        assertEquals("broken\n", Files.readString(worker.tree().resolve("editor.S")),
                "the next attempt is against what was actually written");
    }

    @Test
    void aFileTheProjectDoesNotHaveIsNotCreated(@TempDir Path dir) throws Exception {
        var root = repo(Files.createDirectory(dir.resolve("repo")), true);
        var worker = worker(root, dir.resolve("pool"));
        var r = new Workshop(root, "./build.sh", 60, false)
                .apply(worker, "#WRITE backdoor.S\nanything\n#ENDWRITE");
        assertFalse(r.wrote(), "creating files is how something nobody asked for arrives");
        assertFalse(Files.exists(worker.tree().resolve("backdoor.S")));
    }

    @Test
    void aPathThatLeavesTheProjectIsRefused(@TempDir Path dir) throws Exception {
        var root = repo(Files.createDirectory(dir.resolve("repo")), true);
        var worker = worker(root, dir.resolve("pool"));
        var w = new Workshop(root, "./build.sh", 60, false);
        for (String escape : new String[]{"../outside.S", "/etc/passwd",
                                          "../../etc/passwd", "a/../../b.S"}) {
            var r = w.apply(worker, "#WRITE " + escape + "\nx\n#ENDWRITE");
            assertFalse(r.wrote(), escape + " was not refused");
        }
        assertFalse(Files.exists(dir.resolve("outside.S")));
    }

    @Test
    void theTerminatorIsNotTheOneThatClosesAScript(@TempDir Path dir) throws Exception {
        var root = repo(Files.createDirectory(dir.resolve("repo")), true);
        var worker = worker(root, dir.resolve("pool"));
        // A source file can perfectly well contain the line #END - any file
        // with a script example in it does - and a block that ended there would
        // write half a file and call it a change.
        var r = new Workshop(root, "./build.sh", 60, false).apply(worker,
                "#WRITE editor.S\nbefore\n#END\nafter\n#ENDWRITE");
        assertTrue(r.wrote());
        assertEquals("before\n#END\nafter\n", Files.readString(worker.tree().resolve("editor.S")));
    }

    @Test
    void aBuildThatNeverFinishesIsGivenUpOn(@TempDir Path dir) throws Exception {
        var root = repo(Files.createDirectory(dir.resolve("repo")), true);
        Files.writeString(root.resolve("build.sh"), "#!/bin/sh\nsleep 30\n");
        root.resolve("build.sh").toFile().setExecutable(true);
        run(root, "git", "commit", "-qam", "slow");
        var worker = worker(root, dir.resolve("pool"));

        var r = new Workshop(root, "./build.sh", 1, false)
                .apply(worker, "#WRITE editor.S\nx\n#ENDWRITE");
        assertFalse(r.built());
        assertTrue(r.log().contains("gave up"), r.log());
    }

    @Test
    void theBriefingSaysWhereItStopsAndThatABinaryNeedsARebuild() {
        String b = Workshop.briefing();
        assertTrue(b.contains("#WRITE"), b);
        assertTrue(b.contains("#ENDWRITE"), b);
        assertTrue(b.contains("branch"), "it has to say where the change goes");
        assertTrue(b.contains("built"), "and that it is not running yet");
    }
}
