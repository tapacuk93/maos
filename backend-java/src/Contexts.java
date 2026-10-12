import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What was gathered for one question, kept in case the next one wants it.
 *
 * {@link Preflight} decides what an answer needs and reads it. That is a model
 * call and six file reads, and then it is thrown away - so somebody working on
 * the keyboard driver pays for it again on their second question about the
 * keyboard driver, and again on the third, having changed nothing in between.
 *
 * So a gathering is written down: a folder with the assembled text in it, a
 * note of what it was for, and a time after which it stops being offered.
 * Later questions are shown what is lying about and may take one instead of
 * naming files again. The second question about the driver costs a model call
 * that answers "reuse that" and nothing else.
 *
 * <h2>Why a folder and not a cache entry</h2>
 *
 * Because it is not only for this process. A gathering is somebody's working
 * set - the files this piece of work is about - and that is a thing worth being
 * able to look at, hand to something else, or find after a restart. A map in
 * memory would be faster and would mean the system could not show anybody what
 * it had been reading.
 *
 * <h2>Why they expire</h2>
 *
 * The files were read at a moment. The branch moves, somebody commits, and what
 * is in the folder is then a description of a machine that no longer exists -
 * which is worse than no context, because it is wrong rather than absent. A
 * lifetime is a blunt answer to that and an honest one: after it, the gathering
 * is not offered and the question is asked afresh.
 *
 * Anything that commits to a branch should say so, and {@link #stale} is how.
 * Until something does, time is the only thing that knows.
 *
 * <h2>Who may have one</h2>
 *
 * Gatherings are keyed by branch, not by person. Two people on the same branch
 * are looking at the same files, and the work of finding out which files those
 * were is not personal to either of them. Somebody on their own branch sees
 * only their own, because their files are their own.
 */
final class Contexts {

    /** One gathering, as it is remembered. */
    record Bundle(String id, String branch, String about, List<String> files,
                  List<String> kinds, long madeAt, long expiresAt, AtomicLong used) {

        boolean liveAt(long now) { return now < expiresAt; }

        /** How it is offered to whoever is deciding what the next one needs. */
        String offer(long now) {
            long minutes = Math.max(1, (expiresAt - now) / 60_000);
            return "    %s - %s (%s, %d more minute%s)".formatted(
                    id, about, String.join(", ", files), minutes, minutes == 1 ? "" : "s");
        }
    }

    private final Path dir;
    private final long ttlMillis;
    private final Map<String, Bundle> bundles = new ConcurrentHashMap<>();
    private final AtomicLong made = new AtomicLong();
    private final AtomicLong reused = new AtomicLong();

    Contexts(Path dir, long ttlSeconds) {
        this.dir = dir.toAbsolutePath().normalize();
        this.ttlMillis = Math.max(60, ttlSeconds) * 1000L;
        load();
    }

    /**
     * Keep what was gathered, and say under what name.
     *
     * The text is what the next question would otherwise rebuild, so that is
     * what is written - not the list of files, which would mean reading them
     * again, and not a copy of each file, which would mean a folder that
     * disagrees with the branch it came from the moment either changes.
     */
    Bundle keep(String branch, String about, List<String> files, List<String> kinds,
                String text) {
        if (branch == null || text == null || text.isBlank()) return null;
        long now = System.currentTimeMillis();
        String id = "ctx-" + Long.toHexString(now) + "-"
                + Integer.toHexString(Math.abs((branch + about).hashCode()) % 0xFFFF);
        var bundle = new Bundle(id, branch, about == null ? "" : about.strip(),
                List.copyOf(files), List.copyOf(kinds), now, now + ttlMillis, new AtomicLong());
        try {
            Path home = dir.resolve(id);
            Files.createDirectories(home);
            Files.writeString(home.resolve("context"), text, StandardCharsets.UTF_8);
            Files.writeString(home.resolve("about"), """
                    %s
                    branch %s
                    files %s
                    kinds %s
                    made %d
                    expires %d
                    """.formatted(bundle.about(), branch, String.join(" ", files),
                            String.join(" ", kinds), bundle.madeAt(), bundle.expiresAt()),
                    StandardCharsets.UTF_8);
        } catch (Exception x) {
            System.out.printf("maos: could not keep %s: %s%n", id, x);
            return null;
        }
        bundles.put(id, bundle);
        made.incrementAndGet();
        sweep();
        return bundle;
    }

    /** What this branch has lying about, newest first. */
    List<Bundle> live(String branch) {
        long now = System.currentTimeMillis();
        var out = new ArrayList<Bundle>();
        for (var b : bundles.values()) {
            if (b.liveAt(now) && b.branch().equals(branch)) out.add(b);
        }
        out.sort((a, b) -> Long.compare(b.madeAt(), a.madeAt()));
        return out;
    }

    /**
     * Take one back out by name, for this branch only.
     *
     * The branch check is the whole of the access control and it is not
     * incidental: an id names a folder of somebody's source, and a question
     * from another branch that could name one would be reading a machine that
     * is not theirs.
     */
    String reuse(String id, String branch) {
        // A name nobody issued is not a gathering, and a null one would take
        // the map with it - this is reached from whatever a model wrote on a
        // line, so every shape of nothing arrives here eventually.
        if (id == null || id.isBlank() || branch == null) return null;
        var b = bundles.get(id);
        if (b == null || !b.branch().equals(branch) || !b.liveAt(System.currentTimeMillis())) {
            return null;
        }
        try {
            String text = Files.readString(dir.resolve(id).resolve("context"));
            b.used().incrementAndGet();
            reused.incrementAndGet();
            return text;
        } catch (Exception x) {
            bundles.remove(id);
            return null;
        }
    }

    /**
     * Everything this branch has gathered is out of date.
     *
     * Called when the branch moves. Time expires a gathering eventually and a
     * commit expires it immediately, and the difference matters: between the
     * two, a gathering describes the machine as it was before the change
     * somebody just made, which is exactly the moment they are most likely to
     * ask another question about it.
     */
    int stale(String branch) {
        int gone = 0;
        for (var b : List.copyOf(bundles.values())) {
            if (b.branch().equals(branch)) {
                drop(b.id());
                gone++;
            }
        }
        return gone;
    }

    /** Drop whatever has run out, and say how many. */
    int sweep() {
        long now = System.currentTimeMillis();
        int gone = 0;
        for (var b : List.copyOf(bundles.values())) {
            if (!b.liveAt(now)) {
                drop(b.id());
                gone++;
            }
        }
        return gone;
    }

    private void drop(String id) {
        bundles.remove(id);
        try {
            Path home = dir.resolve(id);
            if (!Files.isDirectory(home)) return;
            try (var files = Files.list(home)) {
                for (var f : files.toList()) Files.deleteIfExists(f);
            }
            Files.deleteIfExists(home);
        } catch (Exception x) {
            // A folder that will not go is a folder that is swept again next
            // time. Saying so on every sweep would be noise about a thing that
            // fixes itself.
        }
    }

    /**
     * What survived a restart.
     *
     * The folders outlive the process and their expiry is written in them, so
     * a backend that comes back up offers what is still live rather than
     * starting from nothing with a directory full of gatherings.
     */
    private void load() {
        try {
            if (!Files.isDirectory(dir)) return;
            long now = System.currentTimeMillis();
            try (var found = Files.list(dir)) {
                for (var home : found.toList()) {
                    String id = home.getFileName().toString();
                    if (!id.startsWith("ctx-") || !Files.isRegularFile(home.resolve("about"))) {
                        continue;
                    }
                    var lines = Files.readAllLines(home.resolve("about"));
                    String branch = "", about = lines.isEmpty() ? "" : lines.get(0);
                    List<String> files = List.of(), kinds = List.of();
                    long madeAt = 0, expires = 0;
                    for (String line : lines) {
                        if (line.startsWith("branch ")) branch = line.substring(7).strip();
                        else if (line.startsWith("files ")) files = words(line.substring(6));
                        else if (line.startsWith("kinds ")) kinds = words(line.substring(6));
                        else if (line.startsWith("made ")) madeAt = number(line.substring(5));
                        else if (line.startsWith("expires ")) expires = number(line.substring(8));
                    }
                    if (expires <= now || branch.isBlank()) {
                        drop(id);
                        continue;
                    }
                    bundles.put(id, new Bundle(id, branch, about, files, kinds,
                            madeAt, expires, new AtomicLong()));
                }
            }
            if (!bundles.isEmpty()) {
                System.out.printf("maos: %d gathering(s) still worth offering%n", bundles.size());
            }
        } catch (Exception x) {
            System.out.printf("maos: could not read what was gathered before (%s)%n", x);
        }
    }

    private static List<String> words(String s) {
        var out = new ArrayList<String>();
        for (var w : s.strip().split("\\s+")) if (!w.isBlank()) out.add(w);
        return List.copyOf(out);
    }

    private static long number(String s) {
        try {
            return Long.parseLong(s.strip());
        } catch (NumberFormatException bad) {
            return 0;
        }
    }

    int size() { return bundles.size(); }

    String asJson() {
        return Json.obj("kept", bundles.size(), "made", made.get(), "reused", reused.get());
    }
}
