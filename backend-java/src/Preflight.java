import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The call before the call: what does answering this need to have read?
 *
 * Everything else in the prompt is fixed - the screen, what they typed, what
 * they have been doing, what this machine looks like. That is enough to change
 * a document and not enough to change the machine. "larger font" can be
 * answered from a briefing because somebody wrote a briefing about font size;
 * "the trackpad does not work" cannot, because the answer is in
 * kernel/arch/aarch64/xhci.S and no briefing will ever contain it.
 *
 * Sending the whole tree instead is not an answer. It is megabytes, most of it
 * irrelevant to any one question, and a model given everything attends to
 * nothing. Guessing from keywords is not an answer either - "font" appears in
 * four files and the one that matters depends on whether they are asking about
 * size, shape or where the glyphs come from.
 *
 * So something that reads language is asked, cheaply, which files this question
 * is about. It sees the names and nothing else, says at most a handful, and
 * what it names is read and put in front of the model that does the work.
 *
 * <h2>Why this is the quickest provider and not the best one</h2>
 *
 * "Which files is this question about?" is a judgement with a right answer that
 * a fast model finds as reliably as a slow one, and the person is sitting in
 * front of a caret while it is being found. The same argument as
 * {@link Recogniser}, and the same call.
 *
 * <h2>What it may not do</h2>
 *
 * It names paths. It cannot write, cannot run, and cannot name anything outside
 * this project - every answer is resolved against the tree and anything that
 * escapes it, does not exist, or is not text is dropped without comment. The
 * worst a wrong answer can do is put the wrong file in front of a model, which
 * costs tokens and nothing else.
 */
final class Preflight {

    /** Files to read for one question. Past this it is not a question. */
    private static final int MAX_FILES = 6;

    /**
     * How much source one answer may be given, in characters.
     *
     * A number with a reason rather than a guess: it is the room the far side
     * will accept, less what the rest of the prompt is already using, and the
     * caller passes the second part because only the caller knows it.
     *
     * The first version of this had two fixed caps - sixty thousand in total
     * and twenty-four thousand per file - and both were wrong in the same
     * direction. Twelve files in this project are larger than twenty-four
     * thousand characters, and they are the twelve a question about the machine
     * would ever be about: the USB driver, the editor core, the client, the
     * server. The mechanism would name exactly the right file and then silently
     * read none of it.
     */
    static final int DEFAULT_ROOM = 360_000;

    /**
     * The kinds of context that are not files.
     *
     * A question needs some of these and not others, and until now it got all
     * of them: every request carried what this person has been doing, what
     * clouds they can reach, what machines cost, what the server already
     * answers and what each model has been like at the work. Fourteen thousand
     * characters of it, in front of "make this sentence shorter".
     *
     * That is not only waste. A model reading five briefings it does not need
     * is a model attending to five things that are not the question, and the
     * cost of the irrelevant ones is paid by the relevant one.
     *
     * Named in the vocabulary the asking model sees, with one line each saying
     * what it is for. The names are deliberately about what somebody would want
     * them for rather than about which class produces them.
     */
    static final Map<String, String> KINDS = new java.util.LinkedHashMap<>();

    private static void kind(String name, String what) {
        KINDS.put(name, what);
    }

    static {
        kind("appearance", "what size the text is set to and what colour, as numbers");
        kind("screen", "how much the screen holds at that size, and how much is on it");
        kind("history", "what this person has been typing and pressing just now");
        kind("clouds", "which clouds this account can run work on, and what sizes cost");
        kind("machines", "what the backend can bring up, and how to ask for it");
        kind("operations", "what the server already answers without a model");
        kind("models", "what each model has been like at this kind of work");
        kind("refusals", "what this person has told the system to stop doing");
        kind("agents", "the machines this account has volunteered to do work");
    }

    /**
     * What a question turned out to need: files, and kinds of context.
     *
     * Empty means nothing was decided - no model, no repository, a request too
     * short to be about the machine - and an empty answer means the caller
     * should send what it always sent. A mechanism for choosing less must never
     * turn into a mechanism for sending nothing.
     */
    record Needs(List<String> files, java.util.Set<String> kinds, boolean decided) {

        static Needs undecided() {
            return new Needs(List.of(), java.util.Set.of(), false);
        }

        /** Does the answer need this?  Everything, when nothing was decided. */
        boolean wants(String kind) {
            return !decided || kinds.contains(kind);
        }
    }

    /**
     * Nothing decides this but the model that is asked.
     *
     * There was a word count here: below three words, a request was assumed to
     * be about the document and no source was gathered. It was cheap and it was
     * wrong in exactly the case this mechanism exists for. "larger font" is two
     * words and is a request to change the machine; so is "fix the keyboard",
     * and so is "slower". Meanwhile "can you explain what this function does to
     * the array" is eleven words about text on a screen.
     *
     * Length is not the signal. What the request is about is the signal, and
     * working that out is what the asked model is for - so it is asked, and the
     * answer is remembered per tree, which makes a novel phrasing cost one fast
     * call and every repeat cost nothing.
     */

    private final Aicoin aicoin;
    private final Path root;
    private final List<String> manifest;
    private final Contexts contexts;

    Preflight(Aicoin aicoin, Path root) {
        this(aicoin, root, null);
    }

    Preflight(Aicoin aicoin, Path root, Contexts contexts) {
        this.aicoin = aicoin;
        this.root = root.toAbsolutePath().normalize();
        this.manifest = manifest(this.root);
        this.contexts = contexts;
    }

    boolean available() {
        return !manifest.isEmpty();
    }

    /**
     * What this question needs read, as a block for the prompt, or "".
     *
     * Failure at any point is "", silently. This makes an answer better when it
     * works and must never be the reason there is no answer: a model that was
     * going to answer from the briefings can still answer from the briefings.
     */
    String gather(String wallet, String instruction) {
        return gather(wallet, instruction, DEFAULT_ROOM);
    }

    /**
     * @param room characters of source this answer may be given, which is what
     *             the far side accepts less what the prompt already holds. A
     *             request built past that limit is refused before any provider
     *             sees it, so sending the files and finding out is the one
     *             outcome worse than not sending them.
     */
    String gather(String wallet, String instruction, int room) {
        return gather(wallet, instruction, room, null);
    }

    /**
     * The same, in somebody's own tree and with what that tree already knows.
     *
     * Two things change when a worker is given. The files are read from the
     * branch their machine is built from, which is the only tree whose sources
     * describe the machine they are using. And a question asked before in that
     * tree is not asked again - the answer to "which files is this about" does
     * not change while the tree does not, so paying a model to work it out
     * twice is paying to learn the same thing twice.
     */
    /** The text to splice in, when the caller only wants that. */
    String gather(String wallet, String instruction, int room, Workers.Worker worker) {
        return decide(wallet, instruction, room, worker).text();
    }

    /** What was gathered, and what else the answer was judged to need. */
    record Gathered(String text, Needs needs) {}

    Gathered decide(String wallet, String instruction, int room, Workers.Worker worker) {
        List<String> files = worker == null ? manifest : worker.manifest();
        Path tree = worker == null ? root : worker.tree();
        var nothing = new Gathered("", Needs.undecided());
        if (files.isEmpty()) return nothing;
        if (instruction == null || instruction.isBlank()) return nothing;
        if (room < 2_000) return nothing;

        List<String> named = worker == null ? null : worker.recall(instruction);
        if (named == null) {
            String branch = worker == null ? "" : worker.branch();
            var lying = contexts == null ? List.<Contexts.Bundle>of() : contexts.live(branch);
            String said;
            try {
                said = aicoin.text(wallet, prompt(instruction, files, lying), null, true);
            } catch (Exception x) {
                System.out.printf("maos: could not ask what \"%s\" needs: %s%n",
                        instruction, x.getMessage());
                return nothing;
            }
            if (said == null || said.isBlank()) return nothing;

            // "reuse ctx-..." - the same working set as a question somebody
            // already paid to work out. Checked against this branch, because an
            // id names a folder of somebody's source.
            for (String line : said.split("\n")) {
                String word = line.strip();
                if (!word.toLowerCase(Locale.ROOT).startsWith("reuse ")) continue;
                String kept = contexts == null ? null
                        : contexts.reuse(word.substring(6).strip(), branch);
                if (kept == null) continue;
                System.out.printf("maos: \"%s\" takes a gathering that was already made%n",
                        instruction);
                return new Gathered(kept, new Needs(List.of(), java.util.Set.of(), true));
            }
            named = List.of(said.split("\n"));
        }

        var kinds = new java.util.LinkedHashSet<String>();
        var paths = new ArrayList<String>();
        for (String line : named) {
            String word = line.strip().toLowerCase(Locale.ROOT);
            if (KINDS.containsKey(word)) kinds.add(word);
            else paths.add(line.strip());
        }
        var needs = new Needs(List.copyOf(paths), java.util.Set.copyOf(kinds), true);
        if (worker != null) worker.learned(instruction, named);

        var out = new StringBuilder();
        int budget = room;
        int taken = 0;
        for (String line : paths) {
            if (taken >= MAX_FILES || budget <= 0) break;
            String body = read(tree, line.strip());
            if (body == null) continue;
            if (body.length() > budget) continue;
            out.append("----- ").append(line.strip()).append('\n')
               .append(body).append("\n\n");
            budget -= body.length();
            taken++;
        }
        System.out.printf("maos: \"%s\" needs %d file(s) and %s%s%n", instruction, taken,
                kinds.isEmpty() ? "no other context" : kinds,
                worker == null ? "" : " in " + worker.branch());
        if (taken == 0) return new Gathered("", needs);
        String text = """
                THE PART OF THIS SYSTEM THE QUESTION IS ABOUT

                These are the actual sources of the machine they are using, as
                they are on disk right now. They were chosen for this question
                and are not a complete picture: if what you need is plainly not
                here, say which file you would want rather than guessing at its
                contents.

                """ + out;
        // Kept, so the next question about the same part of the machine is a
        // call that answers "reuse that" rather than six file reads and a
        // decision somebody already made.
        if (contexts != null && worker != null) {
            contexts.keep(worker.branch(), instruction, paths, List.copyOf(kinds), text);
        }
        return new Gathered(text, needs);
    }

    /**
     * Read one named file, or null for every way that can fail.
     *
     * Resolved against the tree and checked to be inside it afterwards, which
     * is the only check that holds: a path can climb out with "..", with a
     * symbolic link, or by being absolute, and normalising first collapses all
     * three into the same question - is this still under the root.
     */
    private String read(Path tree, String named) {
        if (named.isBlank() || named.startsWith("#") || named.contains(" ")) return null;
        try {
            Path p = tree.resolve(named).normalize();
            if (!p.startsWith(tree) || !Files.isRegularFile(p)) return null;
            String body = Files.readString(p);
            // Something whose bytes are not text is a blob somebody generated,
            // and a model reading one learns nothing but spends the budget a
            // file that would have helped was going to use.
            for (int i = 0; i < body.length(); i++) {
                if (body.charAt(i) == 0) return null;
            }
            return body;
        } catch (Exception x) {
            return null;
        }
    }

    private String prompt(String instruction, List<String> files,
                          List<Contexts.Bundle> lying) {
        var kinds = new StringBuilder();
        for (var e : KINDS.entrySet()) {
            kinds.append("    ").append(e.getKey())
                 .append(" - ").append(e.getValue()).append('\n');
        }
        return """
                Somebody using a text editor that is also its own operating
                system has asked for this:

                    %s

                Answering it well may need things they did not say. Your job is
                to decide which, before the model that does the work is asked -
                it will be given exactly what you name and nothing else, so
                this is the whole of what it will know beyond their screen and
                what they typed.

                You may name two sorts of thing, one per line, in any order.

                The first is the system's own source. The machine they are
                using is these files:

                %s

                The second is what is true right now rather than what is
                written down:

                %s
                %s
                Name only what answering this actually requires.

                Most requests need neither. Somebody fixing a sentence, writing
                a function or asking a question about their own text needs the
                screen they already have - reply with the single word none, and
                that is the common and correct answer.

                Name a source file when the request would change how the
                machine behaves, or asks why it behaves as it does. Choose where
                the behaviour is implemented, not where the words match: a
                question about the keyboard on real hardware is about the driver
                that reads it, not the file that lists key names.

                Work out what the answer turns on, and name that. Not what
                the request mentions - what the answer would be wrong without.

                "set the size to 4" turns on nothing: the request contains its
                own answer. "larger" turns on the size it is at, so name
                appearance. "I can't read this" turns on the size, how much the
                screen holds and how much is on it, because the comfortable
                answer for a full screen of long lines is not the comfortable
                answer for three words - name appearance and screen. "undo
                that" turns on what just happened, so name history.

                The same subject can need nothing one time and three things the
                next, which is why this is asked rather than decided in advance
                by whoever wrote the system. If a future request turns on
                something not on this list, say so in a line of your own and it
                will be there next time.

                Every line you name is read in full and takes room from the
                others, and every one that was not needed is something the
                answering model has to read past to reach the question.
                """.formatted(instruction, String.join("\n", files), kinds, already(lying));
    }

    /**
     * What has already been gathered, offered rather than hidden.
     *
     * Somebody working on one thing asks about it several times, and the second
     * question has the same working set as the first. Finding that out again
     * costs a call and six file reads for an answer that has not changed - so
     * what was gathered is still there, and this is where it is put in front of
     * the model that would otherwise rebuild it.
     *
     * Each one says when it stops being offered, because a gathering is a
     * reading of files at a moment and the honest thing to say about one is how
     * old it is.
     */
    private static String already(List<Contexts.Bundle> lying) {
        if (lying == null || lying.isEmpty()) return "";
        long now = System.currentTimeMillis();
        var b = new StringBuilder("""
                And these were gathered for earlier questions about this same
                machine, and are still here:

                """);
        int shown = 0;
        for (var one : lying) {
            if (shown++ == 8) break;
            b.append(one.offer(now)).append('\n');
        }
        return b.append("""

                If one of them is the working set this question needs - the same
                files, about the same part of the system - reply with

                    reuse <its name>

                and nothing else. Prefer that to naming the same files again: it
                is the same answer, already paid for. Do not reuse one that is
                merely nearby; a working set that is almost right is a model
                reading the wrong file carefully.

                """).toString();
    }

    /**
     * Every source file in the project, by path.
     *
     * Asked of git rather than walked, so what is listed is what is tracked:
     * no build output, no local scratch, nothing in .gitignore. A tree this is
     * not a checkout of produces an empty list, and an empty list turns this
     * whole mechanism off rather than making it wrong.
     */
    static List<String> manifest(Path root) {
        var out = new ArrayList<String>();
        try {
            var p = new ProcessBuilder("git", "ls-files")
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .start();
            try (var r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.endsWith(".S") || line.endsWith(".java") || line.endsWith(".inc")
                            || line.endsWith(".py") || line.endsWith(".rs")
                            || line.endsWith(".md") || line.equals("Makefile")) {
                        out.add(line);
                    }
                }
            }
            p.waitFor();
        } catch (Exception x) {
            return List.of();
        }
        return List.copyOf(out);
    }

    int files() { return manifest.size(); }
}
