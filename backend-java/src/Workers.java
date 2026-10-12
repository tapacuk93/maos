import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A source tree per person, kept warm.
 *
 * {@link Preflight} answers "which files is this question about" and then reads
 * them. Both halves assume there is a tree to read, and until now that was the
 * one directory the backend happens to sit in - which is wrong in two
 * directions at once.
 *
 * It is wrong for the person, because once somebody has changed their machine
 * their sources are not these sources. A question about the caret, asked by
 * somebody whose caret already works differently, has to be answered from the
 * code their machine is actually built from, or the answer describes a machine
 * nobody is using.
 *
 * And it is wrong for the work. Listing the tree, reading it, working out what
 * is where - that is the same work every time, for every person, about files
 * that did not change between one question and the next. Doing it per request
 * is paying to learn the same thing repeatedly.
 *
 * So: a worktree per account, on that account's own branch, created once and
 * kept. Each one remembers what it has already worked out about itself, and
 * the pool is bounded - the trees are cheap, git shares the object store
 * between them, but they are not free and somebody with a thousand accounts
 * should not get a thousand checkouts.
 *
 * <h2>Why a worktree rather than a clone</h2>
 *
 * A clone copies the history; a worktree shares it. Twenty worktrees of this
 * project cost twenty working copies and one object store, and switching a
 * person between branches is a checkout rather than a fetch. It also means a
 * branch made here is a branch in the same repository the project is in, which
 * is what makes "build me a machine with this in it" a thing somebody else can
 * look at.
 *
 * <h2>What this does not do</h2>
 *
 * It does not write. Nothing here changes a file, commits, or pushes - it
 * materialises trees and remembers what has been learned about them. What may
 * write into one of these trees is a separate question with a separate answer,
 * and keeping the two apart is deliberate: a pool that can only read is a pool
 * that can be wrong without being dangerous.
 */
final class Workers {

    /**
     * How many trees to keep.
     *
     * Each is a working copy of the project, which is a few megabytes, against
     * an object store they all share. The number is small because the thing it
     * is trading against is small: evicting one costs the next question from
     * that person a checkout, and keeping one costs disk whether or not anybody
     * asks anything.
     */
    private static final int KEEP = 8;

    /** One person's tree, and what has already been worked out about it. */
    static final class Worker {
        final String accountId;
        final String branch;
        final Path tree;
        private volatile List<String> manifest;
        /** What a question turned out to need, so it is asked once per tree. */
        private final Map<String, List<String>> recalled = new ConcurrentHashMap<>();
        private volatile long used = System.currentTimeMillis();
        private final AtomicLong asked = new AtomicLong();
        private final AtomicLong remembered = new AtomicLong();

        Worker(String accountId, String branch, Path tree) {
            this.accountId = accountId;
            this.branch = branch;
            this.tree = tree;
        }

        Path tree() { return tree; }
        String branch() { return branch; }

        /**
         * Every source file in this tree, worked out once.
         *
         * Held rather than asked for each time. It changes when the tree
         * changes, and the tree changes when somebody commits to this branch -
         * which goes through {@link #forget}, so nothing here has to guess
         * whether it is stale.
         */
        List<String> manifest() {
            var m = manifest;
            if (m == null) {
                m = Preflight.manifest(tree);
                manifest = m;
            }
            return m;
        }

        /** What this question needed last time somebody asked it here. */
        List<String> recall(String question) {
            var got = recalled.get(Scripts.normalise(question));
            if (got != null) remembered.incrementAndGet();
            return got;
        }

        void learned(String question, List<String> files) {
            asked.incrementAndGet();
            if (files.isEmpty()) return;
            // Bounded the same way the recogniser's memory is, and for the same
            // reason: this grows like the vocabulary of things people ask about
            // one tree, which is slowly and then not at all.
            if (recalled.size() > 2_000) recalled.clear();
            recalled.put(Scripts.normalise(question), List.copyOf(files));
        }

        /** The tree changed, so what was worked out about it did too. */
        void forget() {
            manifest = null;
            recalled.clear();
        }

        void touch() { used = System.currentTimeMillis(); }
        long used() { return used; }
        long asked() { return asked.get(); }
        long remembered() { return remembered.get(); }
    }

    private final Path root;
    private final Path pool;
    private final Map<String, Worker> workers = new ConcurrentHashMap<>();

    Workers(Path root, Path pool) {
        this.root = root.toAbsolutePath().normalize();
        this.pool = pool.toAbsolutePath().normalize();
    }

    /** Is there a repository here to make trees out of at all? */
    boolean available() {
        return Files.isDirectory(root.resolve(".git")) || Files.isRegularFile(root.resolve(".git"));
    }

    /**
     * This account's tree, made if it is not there.
     *
     * Null when there is no repository, which turns every caller off rather
     * than making them handle a tree that does not exist.
     */
    Worker forAccount(String accountId) {
        if (!available() || accountId == null || accountId.isBlank()) return null;
        var existing = workers.get(accountId);
        if (existing != null && Files.isDirectory(existing.tree())) {
            existing.touch();
            return existing;
        }
        String branch = branchFor(accountId);
        Path tree = pool.resolve(branch.replace('/', '-'));
        try {
            if (!Files.exists(tree.resolve(".git"))) {
                Files.createDirectories(pool);
                // -B so a branch that is already there is used and one that is
                // not is made from where this tree is now. A person's machine
                // follows the project forward until they ask for something that
                // takes it off.
                var made = run(root, List.of("git", "worktree", "add", "-B", branch,
                        tree.toString(), "HEAD"));
                if (!made) {
                    System.out.printf("maos: no worktree for %s, so it reads the project's own%n",
                            accountId);
                    return null;
                }
                System.out.printf("maos: %s works in %s on %s%n", accountId, tree, branch);
            }
        } catch (Exception x) {
            System.out.printf("maos: could not make a tree for %s: %s%n", accountId, x);
            return null;
        }
        var worker = new Worker(accountId, branch, tree);
        workers.put(accountId, worker);
        evict();
        return worker;
    }

    /** The branch a person's machine is built from. */
    static String branchFor(String accountId) {
        return "machine/" + accountId.replaceAll("[^A-Za-z0-9_-]", "");
    }

    /**
     * Drop the least recently used trees past the limit.
     *
     * By when they were last used rather than by how much they hold, because
     * what is being reclaimed is disk and every tree costs the same disk. The
     * one being evicted is the one whose owner is least likely to ask next.
     */
    private void evict() {
        if (workers.size() <= KEEP) return;
        var oldest = new ArrayList<>(workers.values());
        oldest.sort((a, b) -> Long.compare(a.used(), b.used()));
        for (int i = 0; i < oldest.size() - KEEP; i++) {
            var w = oldest.get(i);
            workers.remove(w.accountId);
            run(root, List.of("git", "worktree", "remove", "--force", w.tree().toString()));
            System.out.printf("maos: let go of %s's tree%n", w.accountId);
        }
    }

    /** For the status page: how much of this is not being done twice. */
    String asJson() {
        long asked = 0, remembered = 0;
        for (var w : workers.values()) {
            asked += w.asked();
            remembered += w.remembered();
        }
        return Json.obj("trees", workers.size(), "kept", KEEP,
                "asked", asked, "remembered", remembered);
    }

    int size() { return workers.size(); }

    private boolean run(Path where, List<String> argv) {
        try {
            var p = new ProcessBuilder(argv)
                    .directory(where.toFile())
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception x) {
            return false;
        }
    }
}
