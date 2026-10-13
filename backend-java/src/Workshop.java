import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Changing the machine from inside it.
 *
 * Everything else here changes what the editor answers. This changes what the
 * editor is, and the difference is not one of degree: an operation is a page of
 * machine code that a running editor installs and calls, and the editor is the
 * thing doing the installing. No number of operations will ever make the font
 * renderer draw differently. Until this existed, a request to change the
 * machine could only come back as a description of the machine.
 *
 * A model that has been shown the sources - see {@link Preflight} - may write
 * them back:
 *
 *     #WRITE editor/editor.S
 *     ...the entire new contents of that file...
 *     #ENDWRITE
 *
 * Whole files rather than a patch. A patch format is a second thing to get
 * right, and a model that miscounts a hunk header produces a diff that applies
 * cleanly in the wrong place; a whole file is unambiguous, and the model was
 * given the whole file to start from.
 *
 * <h2>Where it is written, which is most of the safety</h2>
 *
 * Never into the tree anybody is working in. Each account has a worktree of its
 * own on a branch of its own - see {@link Workers} - and the writes land there.
 * A change that does not compile leaves that worktree broken and nothing else.
 * Two people asking for incompatible things get two branches rather than a
 * fight. And a person's machine is then a branch somebody can look at, which is
 * what makes "build me a machine with this in it" a reviewable sentence.
 *
 * <h2>What stands in for the consortium</h2>
 *
 * An operation ships when several models agree it should exist. That gate works
 * because an operation is small, pure, and can be run on every input its
 * pattern accepts before anybody votes. A change to the editor core is none of
 * those, and asking models to read a diff and vote would be ceremony rather
 * than a check.
 *
 * The gate here is the build and the tests, which is the only thing that
 * actually knows. It compiles for both targets and runs every assertion,
 * including the ones in Rust that a port would inherit. A change that breaks
 * any of them is not committed; it is handed back with the output, and the
 * model gets to read its own compiler errors, which is how anybody fixes code.
 *
 * <h2>Where it stops</h2>
 *
 * On the person's branch. It is committed there and pushed there, and it never
 * touches the branch the project is on: becoming part of the project is what
 * the consortium is for, and this deliberately has no route to it. The thing
 * that reaches everybody and the thing that reaches one person are different
 * mechanisms on purpose.
 *
 * <h2>What it may not do</h2>
 *
 * Write outside the tree, write a file the project does not already track, or
 * commit anything that did not build. The first is checked after resolution,
 * because "..", an absolute path and a symbolic link all collapse into one
 * question. The second is deliberate: creating files is how a model adds
 * something nobody asked for, and the requests this is for are changes to code
 * that exists.
 */
final class Workshop {

    /**
     * One file the model wants written, whole.
     *
     * Terminated by #ENDWRITE rather than #END, because #END already closes a
     * taught script - and a source file whose last line is #END, which any file
     * containing a script example has, would otherwise end itself early.
     */
    private static final Pattern WRITE = Pattern.compile(
            "(?ms)^#WRITE[ \\t]+([^\\n]+?)[ \\t]*\\n(.*?)^#ENDWRITE[ \\t]*$");

    /** Longest a single file may be written as. Past this it is not an edit. */
    private static final int MAX_FILE = 400_000;

    /** And how many in one answer, because a change is not a rewrite. */
    private static final int MAX_FILES = 8;

    private final Path root;
    private final List<String> build;
    private final long buildSeconds;
    private final boolean push;

    Workshop(Path root, String buildCommand, long buildSeconds, boolean push) {
        this.root = root.toAbsolutePath().normalize();
        this.build = List.of(buildCommand.trim().split("\\s+"));
        this.buildSeconds = buildSeconds;
        this.push = push;
    }

    /** What happened, in the shape somebody has to be told about. */
    record Result(boolean wrote, boolean built, boolean committed,
                  List<String> files, String branch, String log) {

        static Result nothing() {
            return new Result(false, false, false, List.of(), "", "");
        }

        /** A sentence rather than a transcript. */
        String said() {
            if (!wrote) return "";
            if (!built) {
                return "that did not build, so nothing changed - the output is above";
            }
            if (!committed) {
                return "it built and passed, but was not committed: " + log;
            }
            return "built, passed, and committed to %s: %s. It is in the binary rather than "
                    .formatted(branch, String.join(", ", files))
                    + "in an operation, so it arrives when this machine is next built.";
        }
    }

    boolean asked(String reply) {
        return reply != null && WRITE.matcher(reply).find();
    }

    /**
     * Act on whatever the reply asked to be written, in this person's tree.
     *
     * Returns at once when nothing was asked, which is the common case and has
     * to cost nothing: most answers are text for somebody's screen and have no
     * business near a build.
     */
    Result apply(Workers.Worker worker, String reply) {
        if (reply == null || worker == null) return Result.nothing();
        var wanted = new LinkedHashMap<String, String>();
        var m = WRITE.matcher(reply);
        while (m.find() && wanted.size() < MAX_FILES) {
            String path = m.group(1).strip();
            String body = m.group(2);
            if (body.length() > MAX_FILE) continue;
            if (!tracked(path)) {
                System.out.printf("maos: will not write %s - not a file this project has%n", path);
                continue;
            }
            wanted.put(path, body);
        }
        if (wanted.isEmpty()) return Result.nothing();

        Path tree = worker.tree();
        var files = List.copyOf(wanted.keySet());
        for (var e : wanted.entrySet()) {
            Path target = tree.resolve(e.getKey()).normalize();
            if (!target.startsWith(tree)) {
                return new Result(true, false, false, List.of(), worker.branch(),
                        "that path leaves the project");
            }
            try {
                Files.writeString(target, e.getValue());
            } catch (Exception x) {
                return new Result(true, false, false, files, worker.branch(),
                        "could not write " + e.getKey() + ": " + x);
            }
        }
        System.out.printf("maos: %s wrote %s, building%n", worker.branch(), files);

        var made = run(tree, build, buildSeconds);
        if (!made.ok()) {
            // Left in the tree on purpose. The model is about to be handed the
            // errors and asked to try again, and it can only do that against
            // what it actually wrote.
            return new Result(true, false, false, files, worker.branch(), made.out());
        }

        String why = """
                %s

                Asked for from inside the machine, by whoever is using this
                branch. Written by a model that was shown these files, then
                built for both targets and put through every test before
                anything was committed.

                The build is the gate. An operation ships when several models
                agree it should exist, and that works because an operation is
                small enough to run on every input it accepts; this is not, so
                what stands in for the vote is that it compiles and that every
                assertion still passes.

                This branch only. Becoming part of the project is what the
                consortium is for, and this has no route to it.
                """.formatted(String.join(", ", files));

        var add = new ArrayList<>(List.of("git", "add", "--"));
        add.addAll(files);
        if (!run(tree, add, 60).ok()) {
            return new Result(true, true, false, files, worker.branch(), "could not stage");
        }
        var commit = new ArrayList<>(List.of("git", "commit", "-m", why, "--"));
        commit.addAll(files);
        var done = run(tree, commit, 60);
        if (!done.ok()) {
            return new Result(true, true, false, files, worker.branch(),
                    "nothing changed, or could not commit: " + done.out());
        }
        if (!push) {
            return new Result(true, true, true, files, worker.branch(), "committed, not pushed");
        }
        var pushed = run(tree, List.of("git", "push", "-u", "origin", worker.branch()), 180);
        return new Result(true, true, true, files, worker.branch(),
                pushed.ok() ? "pushed" : "committed but not pushed: " + pushed.out());
    }

    /** Is this a file the project already has?  Creating files is not editing. */
    private boolean tracked(String path) {
        if (path == null || path.isBlank() || path.contains("..") || path.startsWith("/")) {
            return false;
        }
        return run(root, List.of("git", "ls-files", "--error-unmatch", "--", path), 30).ok();
    }

    private record Ran(boolean ok, String out) {}

    /**
     * Run something and wait, but not forever.
     *
     * The output goes to a file rather than being read from a pipe, which looks
     * like the long way round and is the only way the deadline means anything.
     * Reading a pipe blocks until the far end closes it, and the far end is the
     * process - so a version that read first and then waited had already waited
     * for as long as the build took, whatever number it was given. A build that
     * hung would hang the request behind it until somebody noticed.
     */
    private Ran run(Path where, List<String> argv, long seconds) {
        java.io.File log = null;
        try {
            log = java.io.File.createTempFile("maos-build", ".log");
            var p = new ProcessBuilder(argv)
                    .directory(where.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log)
                    .start();
            if (!p.waitFor(seconds, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Ran(false, "gave up after " + seconds + " seconds");
            }
            // Only the tail is kept. A failing build prints its error and then a
            // hundred lines of what else it was doing, and the part that says
            // what is wrong is near the end.
            String out = Files.readString(log.toPath());
            if (out.length() > 20_000) out = out.substring(out.length() - 16_000);
            return new Ran(p.exitValue() == 0, out);
        } catch (Exception x) {
            return new Ran(false, String.valueOf(x));
        } finally {
            if (log != null) log.delete();
        }
    }

    /** What the model is told it may do, and what happens when it does. */
    static String briefing() {
        return """

                CHANGING THE MACHINE ITSELF

                Some requests are not about the text and not about an operation.
                "the caret should blink slower", "the bar should show the time",
                "this driver does not see my keyboard" are asking for the editor
                to be different, and no stored answer can do that: an operation
                is a page of machine code that the editor installs and calls,
                and the editor is the thing doing the installing.

                If the sources you need are in front of you, you may write them
                back. Give the entire new contents of each file:

                    #WRITE path/to/file.S
                    ...every line of the file, as it should now be...
                    #ENDWRITE

                Whole files, not a patch: you were given the whole file, and a
                diff that miscounts a line applies cleanly in the wrong place.
                Only files that already exist, and only ones you were shown.

                What happens next is not a vote. It is written into a branch
                belonging to this person alone, built for both targets, and put
                through every test. If any of that fails nothing is committed
                and you are handed the output - which is how anybody fixes code,
                and is to be used rather than apologised for. If it passes, it is
                committed to their branch and to no other.

                A change to anything that runs before the editor does - the
                kernel, the boot path, the font - is in the binary rather than in
                an operation, so it arrives when they next build and not before.
                Say so plainly; do not imply it is already running.

                Write nothing you were not shown. If the file you need is not in
                front of you, name it and say you need it, and it will be there
                next time.
                """;
    }
}
