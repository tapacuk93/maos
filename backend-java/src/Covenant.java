import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The relations between a device and its server that may not be rewritten.
 *
 * This system changes itself. An answer becomes a script, a script becomes
 * machine code, machine code is committed back into the project, and a person
 * can ask for any of it to be different. That is the whole design and it has
 * one hole in it: a system that can rewrite anything can rewrite the thing that
 * was protecting somebody.
 *
 * The hole is not closed by making the system cautious. It is closed by naming,
 * in one place, the small set of things that are not features. They are the
 * terms on which a device talks to a server at all - what the server may see,
 * whose money is spent, what a device is trusted with - and every one of them
 * is a promise made to the person holding the machine rather than a behaviour
 * somebody chose.
 *
 * <h2>Why these and not others</h2>
 *
 * Each is a relation rather than a capability. "The caret blinks" is a
 * behaviour: somebody can want it differently and nobody is worse off. "Text
 * marked $$ is never sent" is not a behaviour - it is the reason somebody was
 * willing to type a password into a text editor that talks to a server. Change
 * it and the thing that was true when they typed it stops being true
 * retroactively, which no amount of agreement afterwards can repair.
 *
 * The test for admitting one here: if it changed, would somebody have been
 * wrong to trust the machine before it changed? If yes, it belongs here. If it
 * is only that they would prefer the old way, it does not.
 *
 * <h2>What this can and cannot enforce</h2>
 *
 * It can refuse to let one be taken away through the mechanisms this server
 * owns - a refusal cannot name one, and the briefing tells every model that
 * these are not up for change. It cannot stop somebody with a text editor and
 * the source from deleting this file, and it does not pretend to: the point of
 * writing them down is that removing one has to be a deliberate act by a person
 * rather than something a model does helpfully while it was changing something
 * else.
 */
final class Covenant {

    /** One promise, and what breaks if it stops being true. */
    record Term(String name, String what, String why) {}

    private static final Map<String, Term> TERMS = new LinkedHashMap<>();

    private static void term(String name, String what, String why) {
        TERMS.put(name, new Term(name, what, why));
    }

    static {
        term("privacy",
             "text written as $$name is never sent anywhere. The server does not "
             + "see it, no model is shown it, and it is substituted back on the "
             + "person's own machine",
             "it is the reason somebody is willing to put a password, a key or a "
             + "private note in a document that talks to a server. If it were "
             + "ever sent, every $$ anybody had already typed would retroactively "
             + "have been a mistake they had no way of knowing they were making");

        term("isolation",
             "a request marked $$$ is developed for that person alone. It goes to "
             + "their own branch, spends their own coins, and nothing from it is "
             + "cached, counted towards anybody else wanting it, scripted or "
             + "promoted towards the project",
             "somebody building their own machine is not building everybody's. A "
             + "system that quietly generalised their work would be spending their "
             + "money on somebody else's feature and putting their unfinished "
             + "thinking in front of strangers");
    }

    private Covenant() {}

    static boolean binds(String name) {
        return name != null && TERMS.containsKey(name.toLowerCase(java.util.Locale.ROOT).strip());
    }

    static Term of(String name) {
        return name == null ? null : TERMS.get(name.toLowerCase(java.util.Locale.ROOT).strip());
    }

    static java.util.Collection<Term> all() {
        return TERMS.values();
    }

    /**
     * What every model is told, wherever it could change the machine.
     *
     * Stated with the reason rather than as a list of prohibitions, because a
     * rule whose reason is given can be applied to a case nobody listed, and a
     * rule without one is followed only in the shapes somebody thought of.
     */
    static String briefing() {
        var b = new StringBuilder("""

                WHAT IS NOT A FEATURE

                Almost everything about this machine can be changed by asking.
                These cannot, and they are not a matter of taste - each one is a
                term on which a device talks to a server at all, and a promise
                to whoever is holding the machine:

                """);
        for (var t : TERMS.values()) {
            b.append("  ").append(t.name()).append(" - ").append(t.what()).append('\n')
             .append("      because ").append(t.why()).append("\n\n");
        }
        b.append("""
                Do not change these, do not work around them, and do not offer
                to. If what somebody is asking for needs one of them weakened,
                say which one and why it cannot be, and then answer the part of
                the request that does not.

                The test, if you meet something not on this list: would somebody
                have been wrong to trust this machine before the change? If yes,
                it belongs here and you should say so rather than making it.

                """);
        return b.toString();
    }
}
