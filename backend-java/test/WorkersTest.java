import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A tree per person, and what each one has already worked out about itself.
 *
 * The expensive part of answering a question about the machine is deciding
 * which of a hundred and sixty files it is about. That answer does not change
 * while the tree does not, so paying for it twice is paying to learn the same
 * thing twice - and the tree it should be learned about is the branch that
 * person's machine is built from, not whichever directory the backend happens
 * to sit in.
 */
class WorkersTest {

    /** A repository with one file in it, which is enough to make worktrees of. */
    private static Path repo(Path dir) throws Exception {
        run(dir, "git", "init", "-q", "-b", "main");
        run(dir, "git", "config", "user.email", "test@example.com");
        run(dir, "git", "config", "user.name", "test");
        Files.writeString(dir.resolve("editor.S"), "the editor\n");
        Files.writeString(dir.resolve("kernel.S"), "the kernel\n");
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

    @Test
    void somewhereThatIsNotARepositoryHasNoTrees(@TempDir Path dir) {
        var w = new Workers(dir, dir.resolve("pool"));
        assertFalse(w.available());
        assertNull(w.forAccount("acct-1"), "no repository, so no tree, rather than a broken one");
    }

    @Test
    void eachAccountGetsItsOwnTreeOnItsOwnBranch(@TempDir Path dir) throws Exception {
        var root = repo(Files.createDirectory(dir.resolve("repo")));
        var w = new Workers(root, dir.resolve("pool"));
        assertTrue(w.available());

        var one = w.forAccount("acct-1");
        var two = w.forAccount("acct-2");
        assertNotNull(one);
        assertNotNull(two);
        assertNotEquals(one.tree(), two.tree());
        assertEquals("machine/acct-1", one.branch());
        assertTrue(Files.isRegularFile(one.tree().resolve("editor.S")),
                "the tree is a checkout, not an empty directory");
    }

    @Test
    void thesameAccountComesBackToTheTreeItAlreadyHad(@TempDir Path dir) throws Exception {
        var root = repo(Files.createDirectory(dir.resolve("repo")));
        var w = new Workers(root, dir.resolve("pool"));
        var first = w.forAccount("acct-1");
        var again = w.forAccount("acct-1");
        assertSame(first, again, "a second question is not a second checkout");
        assertEquals(1, w.size());
    }

    @Test
    void aTreeRemembersWhatHasBeenWorkedOutAboutIt(@TempDir Path dir) throws Exception {
        var root = repo(Files.createDirectory(dir.resolve("repo")));
        var w = new Workers(root, dir.resolve("pool"));
        var worker = w.forAccount("acct-1");

        assertNull(worker.recall("why does the caret blink"));
        worker.learned("why does the caret blink", List.of("editor.S"));
        assertEquals(List.of("editor.S"), worker.recall("why does the caret blink"));
        assertEquals(List.of("editor.S"), worker.recall("  Why Does The Caret Blink  "),
                "the same question asked another way is the same question");
    }

    @Test
    void anAnswerOfNothingIsNotWorthRemembering(@TempDir Path dir) throws Exception {
        var root = repo(Files.createDirectory(dir.resolve("repo")));
        var worker = new Workers(root, dir.resolve("pool")).forAccount("acct-1");
        worker.learned("fix my typo please", List.of());
        assertNull(worker.recall("fix my typo please"),
                "remembering that nothing was needed would stop it being asked again");
    }

    @Test
    void whenTheTreeChangesWhatWasLearnedAboutItDoesNot(@TempDir Path dir) throws Exception {
        var root = repo(Files.createDirectory(dir.resolve("repo")));
        var worker = new Workers(root, dir.resolve("pool")).forAccount("acct-1");
        worker.learned("a question", List.of("editor.S"));
        assertFalse(worker.manifest().isEmpty());
        worker.forget();
        assertNull(worker.recall("a question"));
        assertFalse(worker.manifest().isEmpty(), "and it works it out again");
    }

    @Test
    void theManifestIsOfTheirTreeAndNotOfTheProjects(@TempDir Path dir) throws Exception {
        var root = repo(Files.createDirectory(dir.resolve("repo")));
        var w = new Workers(root, dir.resolve("pool"));
        var worker = w.forAccount("acct-1");
        assertTrue(worker.manifest().contains("editor.S"));
        assertEquals(2, worker.manifest().size(), worker.manifest().toString());
    }

    @Test
    void aBranchNameCannotBeTalkedIntoBeingSomethingElse() {
        assertEquals("machine/acct-1", Workers.branchFor("acct-1"));
        assertEquals("machine/mainrefsheads", Workers.branchFor("main/../refs/heads"),
                "a path cannot be smuggled through an account id");
    }
}
