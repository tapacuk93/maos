import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    /** And how much of them, in bytes, before the real prompt is crowded out. */
    private static final int MAX_BYTES = 60_000;

    /** A single file this large is a generated blob, not something to read. */
    private static final int MAX_ONE = 24_000;

    /**
     * Below this many words a request is about the document, not the machine.
     *
     * Cheap and roughly right. "fix this" is about the screen in front of them
     * and reading the kernel to answer it would be absurd; the requests that
     * need source are the ones that describe a behaviour of the system, and
     * those are sentences.
     */
    private static final int MIN_WORDS = 3;

    private final Aicoin aicoin;
    private final Path root;
    private final List<String> manifest;

    Preflight(Aicoin aicoin, Path root) {
        this.aicoin = aicoin;
        this.root = root.toAbsolutePath().normalize();
        this.manifest = manifest(this.root);
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
        if (!available()) return "";
        if (instruction == null || instruction.isBlank()) return "";
        if (instruction.strip().split("\\s+").length < MIN_WORDS) return "";

        String said;
        try {
            said = aicoin.text(wallet, prompt(instruction), null, true);
        } catch (Exception x) {
            System.out.printf("maos: could not ask what \"%s\" needs read: %s%n",
                    instruction, x.getMessage());
            return "";
        }
        if (said == null || said.isBlank()
                || said.strip().toLowerCase(Locale.ROOT).startsWith("none")) {
            return "";
        }

        var out = new StringBuilder();
        int budget = MAX_BYTES;
        int taken = 0;
        for (String line : said.split("\n")) {
            if (taken >= MAX_FILES || budget <= 0) break;
            String body = read(line.strip());
            if (body == null) continue;
            if (body.length() > budget) continue;
            out.append("----- ").append(line.strip()).append('\n')
               .append(body).append("\n\n");
            budget -= body.length();
            taken++;
        }
        if (taken == 0) return "";
        System.out.printf("maos: \"%s\" reads %d file(s)%n", instruction, taken);
        return """
                THE PART OF THIS SYSTEM THE QUESTION IS ABOUT

                These are the actual sources of the machine they are using, as
                they are on disk right now. They were chosen for this question
                and are not a complete picture: if what you need is plainly not
                here, say which file you would want rather than guessing at its
                contents.

                """ + out;
    }

    /**
     * Read one named file, or null for every way that can fail.
     *
     * Resolved against the tree and checked to be inside it afterwards, which
     * is the only check that holds: a path can climb out with "..", with a
     * symbolic link, or by being absolute, and normalising first collapses all
     * three into the same question - is this still under the root.
     */
    private String read(String named) {
        if (named.isBlank() || named.startsWith("#") || named.contains(" ")) return null;
        try {
            Path p = root.resolve(named).normalize();
            if (!p.startsWith(root) || !Files.isRegularFile(p)) return null;
            if (Files.size(p) > MAX_ONE) return null;
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

    private String prompt(String instruction) {
        return """
                Somebody using a text editor that is also its own operating
                system has asked for this:

                    %s

                Answering it may need you to have read part of the system's own
                source. These are its files:

                %s
                Which of them does answering this question actually require?

                Reply with their paths, copied exactly, one per line, at most
                %d of them. Reply with the single word none if the question is
                about the text they are editing rather than about the behaviour
                of the system itself - which is the common case and costs
                nothing to get right.

                Choose the files where the behaviour they are describing is
                implemented, not the ones whose names contain the same words. A
                question about the keyboard on real hardware is about the driver
                that reads it, not about the file that lists key names. A
                question that would change what the editor does needs the editor
                core and, usually, the one place that decides what a reply may
                ask for.

                Name nothing you do not need. Every file you name is read in
                full and takes room from the ones that mattered.
                """.formatted(instruction, String.join("\n", manifest), MAX_FILES);
    }

    /**
     * Every source file in the project, by path.
     *
     * Asked of git rather than walked, so what is listed is what is tracked:
     * no build output, no local scratch, nothing in .gitignore. A tree this is
     * not a checkout of produces an empty list, and an empty list turns this
     * whole mechanism off rather than making it wrong.
     */
    private static List<String> manifest(Path root) {
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
