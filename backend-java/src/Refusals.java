import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What this person has told the system to stop doing.
 *
 * Everything else here makes the editor grow: somebody asks for something, a
 * model answers, the answer becomes a script, the script becomes machine code,
 * the machine code is agreed and shipped to everybody. That is the whole
 * design and it has one direction. A system that can only grow is a system
 * nobody can live in - what arrives is whatever enough other people wanted,
 * and the person it arrives at has no say.
 *
 * So this is the other direction, and it is per person rather than per system.
 * Saying "stop clearing my screen like that" does not unship the operation,
 * argue with the people who wanted it, or require anybody's agreement. It
 * records that this account refuses it, and from then on:
 *
 *   - the server stops answering that account from that script, and asks a
 *     model instead, which is what they would have got before it existed;
 *   - the catalogue stops offering that account the machine code, so the
 *     device stops installing it;
 *   - and the model is told, so it does not helpfully teach the same thing
 *     back the next time somebody asks for it.
 *
 * <h2>Why refusals are not votes</h2>
 *
 * A refusal says nothing about whether an operation should exist. Somebody who
 * does not want their screen cleared a particular way is not claiming the
 * clearing is wrong for everybody, and a mechanism that treated their refusal
 * as evidence against it would make disagreement expensive - people stop
 * saying "not for me" when saying it costs somebody else their feature.
 *
 * The consortium decides what ships. This decides what arrives here. They are
 * different questions and this file answers only the second.
 *
 * <h2>What this cannot reach</h2>
 *
 * Operations arrive at run time, so refusing one takes effect on the next
 * request. A change to the kernel, the editor core or the boot path arrives
 * only in a new image, and no amount of server-side filtering can take it back
 * out of a binary that is already running. Refusing one of those is a
 * different mechanism - a branch, built per person - and this file records the
 * refusal either way, so that the thing which builds those branches has
 * something to read. Nothing builds them yet, and
 * {@link #needsItsOwnBuild} is where the distinction is made rather than
 * blurred.
 */
final class Refusals {

    /** One thing somebody refused, and why they said so. */
    record Refusal(String what, String why, long at) {}

    private final Path dir;
    private final Map<String, Map<String, Refusal>> byAccount = new ConcurrentHashMap<>();

    Refusals(Path dir) {
        this.dir = dir;
        load();
    }

    /**
     * Does this account refuse this?
     *
     * Named rather than hashed, because what gets refused is an operation with
     * a name somebody can say. "Stop doing clear-screen" is a sentence; a
     * refusal keyed on a content hash would be a sentence nobody can speak.
     */
    boolean refuses(String accountId, String what) {
        if (accountId == null || what == null) return false;
        var mine = byAccount.get(accountId);
        return mine != null && mine.containsKey(what);
    }

    /** Record one, and say whether it was new. */
    boolean refuse(String accountId, String what, String why) {
        if (accountId == null || what == null || what.isBlank()) return false;
        // The two things that are not features cannot be refused either. A
        // refusal is how somebody says "not for me" about a behaviour, and
        // these are not behaviours - they are the terms the machine is used on.
        if (Covenant.binds(what)) {
            System.out.printf("maos: %s may not refuse %s - %s%n",
                    accountId, what, Covenant.of(what).why());
            return false;
        }
        var mine = byAccount.computeIfAbsent(accountId, k -> new ConcurrentHashMap<>());
        boolean fresh = mine.putIfAbsent(what,
                new Refusal(what, why == null ? "" : why.strip(), System.currentTimeMillis())) == null;
        if (fresh) save(accountId);
        return fresh;
    }

    /** Take one back: people change their minds, and that has to be as cheap. */
    boolean restore(String accountId, String what) {
        var mine = byAccount.get(accountId);
        if (mine == null || mine.remove(what) == null) return false;
        save(accountId);
        return true;
    }

    List<Refusal> all(String accountId) {
        var mine = byAccount.get(accountId);
        return mine == null ? List.of() : List.copyOf(mine.values());
    }

    /**
     * Is this something a server can stop doing, or does it need a build?
     *
     * An operation is served, so refusing one is a filter. Anything else named
     * here is a commit, and a commit that changed the kernel or the editor core
     * is already inside the binary on somebody's desk. Saying which is which is
     * the honest half of this feature: the alternative is recording a refusal,
     * reporting success, and leaving the thing running.
     */
    static boolean needsItsOwnBuild(Scripts scripts, String what) {
        return scripts.byName(what) == null;
    }

    /**
     * What the model is told about this person's refusals.
     *
     * So that it does not teach back what they have just taken away. A model
     * that re-scripted a refused operation would be overruling somebody with a
     * helpful intention, which is the worst way to be overruled.
     */
    String briefing(String accountId) {
        var mine = all(accountId);
        if (mine.isEmpty()) return "";
        var b = new StringBuilder("\nTHIS PERSON HAS REFUSED THESE, AND THEY ARE NOT TO COME BACK:\n");
        for (var r : mine) {
            b.append("  - ").append(r.what());
            if (!r.why().isBlank()) b.append(" - ").append(r.why());
            b.append('\n');
        }
        b.append("""

                Do not script them again, do not work around them, and do not
                argue the case. They are about this person and not about whether
                the thing should exist. If what they are asking for now needs one
                of them, say so plainly and answer without it.

                """);
        return b.toString();
    }

    /**
     * How a model says it, and how it takes one back.
     *
     * Two directives rather than a flag, because the two are not symmetrical in
     * how often they are right: a person says "stop doing that" far more often
     * than they say "start again", and a single toggle would turn the second
     * sentence into an accident waiting for the first.
     */
    private static final java.util.regex.Pattern REFUSE = java.util.regex.Pattern.compile(
            "(?mi)^#REFUSE\\s+([A-Za-z0-9_-]{1,64})\\s*(.*)$");
    private static final java.util.regex.Pattern RESTORE = java.util.regex.Pattern.compile(
            "(?mi)^#RESTORE\\s+([A-Za-z0-9_-]{1,64})\\s*$");

    /** Act on whatever a reply asked for, and say what happened. */
    String act(String accountId, String reply, Scripts scripts) {
        if (reply == null) return "";
        var said = new StringBuilder();
        var m = REFUSE.matcher(reply);
        while (m.find()) {
            String what = m.group(1);
            if (!refuse(accountId, what, m.group(2))) continue;
            if (needsItsOwnBuild(scripts, what)) {
                // Said plainly, because the difference is the whole of what
                // somebody needs to know: one of these stops happening on the
                // next keystroke and the other stops happening when the machine
                // is next built.
                said.append("refused %s - that one is not served from here, so it goes "
                        .formatted(what))
                    .append("when this machine is next built\n");
            } else {
                said.append("refused %s\n".formatted(what));
            }
        }
        var r = RESTORE.matcher(reply);
        while (r.find()) {
            if (restore(accountId, r.group(1))) {
                said.append("restored %s\n".formatted(r.group(1)));
            }
        }
        return said.toString();
    }

    // ------------------------------------------------------------ on disk

    private Path file(String accountId) {
        return dir.resolve(accountId + ".refused");
    }

    private void save(String accountId) {
        try {
            Files.createDirectories(dir);
            var b = new StringBuilder();
            for (var r : all(accountId)) {
                b.append(r.at()).append('\t').append(r.what()).append('\t')
                 .append(r.why().replace('\t', ' ').replace('\n', ' ')).append('\n');
            }
            Files.writeString(file(accountId), b.toString(), StandardCharsets.UTF_8);
        } catch (Exception x) {
            System.out.printf("maos: could not write %s's refusals: %s%n", accountId, x);
        }
    }

    private void load() {
        try {
            if (!Files.isDirectory(dir)) return;
            try (var files = Files.list(dir)) {
                for (var f : files.toList()) {
                    String name = f.getFileName().toString();
                    if (!name.endsWith(".refused")) continue;
                    String accountId = name.substring(0, name.length() - ".refused".length());
                    var mine = new LinkedHashMap<String, Refusal>();
                    for (String line : Files.readAllLines(f)) {
                        var parts = line.split("\t", 3);
                        if (parts.length < 2) continue;
                        long at;
                        try {
                            at = Long.parseLong(parts[0]);
                        } catch (NumberFormatException bad) {
                            continue;
                        }
                        mine.put(parts[1],
                                new Refusal(parts[1], parts.length > 2 ? parts[2] : "", at));
                    }
                    if (!mine.isEmpty()) byAccount.put(accountId, new ConcurrentHashMap<>(mine));
                }
            }
            if (!byAccount.isEmpty()) {
                System.out.printf("maos: %d account(s) have refused something%n", byAccount.size());
            }
        } catch (Exception x) {
            System.out.printf("maos: could not read refusals (%s) - starting with none%n", x);
        }
    }

    String asJson() {
        var b = new StringBuilder("{\"accounts\":").append(byAccount.size()).append(",\"total\":");
        int n = 0;
        for (var m : byAccount.values()) n += m.size();
        return b.append(n).append('}').toString();
    }
}
