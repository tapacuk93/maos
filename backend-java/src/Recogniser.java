import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "cls" is "clear", and only something that reads language knows that.
 *
 * {@link Cache} matches bytes and {@link Scripts} matches shapes. Both are
 * exact by construction, and the thing they are exact about is not what a
 * person typed - it is what a person meant. Somebody who wants an empty screen
 * writes "clear the screen", or "clear", or "cls", or "wipe this", and the
 * server has an answer for the first one and pays a model forty seconds and
 * several cents for each of the others. Three of those requests are the same
 * request. Nothing in a pattern matcher can see it.
 *
 * <h2>A small model, on a small question</h2>
 *
 * So this asks - but it asks the quickest provider rather than the best one,
 * because the question is not the work. "Is this another way of saying one of
 * these?" has a right answer that a fast model finds as reliably as a slow one,
 * and the person is sitting in front of a caret while it is being found. A
 * second spent here saves forty; forty spent here would have saved nothing.
 *
 * <h2>Asked once per phrasing, ever</h2>
 *
 * What comes back is not used and discarded. A recognised phrase is written
 * into {@link Scripts} as another pattern for the script it turned out to be,
 * so the second person to type "cls" is answered by the pattern matcher with
 * no model involved at all - and the phrases nobody recognises are remembered
 * too, in {@link #asked}, so an unrecognised phrase costs one fast call rather
 * than one per person who types it.
 *
 * That is the shape of the whole system in miniature: a model is asked once,
 * what it worked out is kept, and the next occurrence is free. This is the same
 * idea one level down from scripts - scripts remember an answer, and this
 * remembers what a question was.
 *
 * <h2>What it may not do</h2>
 *
 * It may only say "this is that", never "here is an answer". The reply from the
 * fast model is a pattern that must already exist; anything else is discarded.
 * So the worst a wrong answer can do is serve somebody a canned reply to a
 * different request - a bad answer, visible immediately, at the same cost as
 * the pattern matcher being wrong, which it can also be. It cannot invent text,
 * it cannot run anything, and it cannot teach the server anything new.
 *
 * <p>And it declines long requests without asking. "clear" is a phrasing.
 * "clear the array and rebuild it from the config file" is somebody's own work,
 * and the fact that it starts with the same word is exactly the trap this would
 * otherwise walk into.
 */
final class Recogniser {

    /**
     * Past this many words a request is not a phrasing of a common one.
     *
     * The requests worth recognising are the ones people type dozens of times
     * a day and never think about: clear, undo, run it, make it faster. They
     * are short because they are habitual. A long sentence is somebody
     * describing their own situation, and the more of it there is the more of
     * it is about them - so length is a good proxy for "this is not the
     * general case", and a cheap one.
     */
    private static final int MAX_WORDS = 8;

    /** And the phrasings already asked about, so nobody pays for the same question twice. */
    private static final int MAX_REMEMBERED = 4000;

    private final Aicoin aicoin;
    private final Scripts scripts;

    /**
     * What each phrase turned out to be: a pattern, or the empty string for
     * "nothing here".
     *
     * The negative answers are the ones that matter. A phrase that is not a
     * known request is going to be typed again by somebody else, and without
     * this it would cost a call every time - a fast call, but on the path of
     * every single request that misses the cache, which is most of them.
     */
    private final Map<String, String> asked = new ConcurrentHashMap<>();

    Recogniser(Aicoin aicoin, Scripts scripts) {
        this.aicoin = aicoin;
        this.scripts = scripts;
    }

    /**
     * The script this instruction is another way of asking for, or null.
     *
     * On success the phrase has been added to {@link Scripts} as a pattern, so
     * the caller's next {@code lookup} finds it by matching rather than by
     * asking. That is deliberate: serving a script - binding its variables,
     * checking its conditions, running its code - stays in one place, and this
     * never becomes a second way of answering.
     */
    Scripts.Script recognise(String wallet, String instruction) {
        String phrase = Scripts.normalise(instruction);
        if (phrase.isBlank() || phrase.split(" ").length > MAX_WORDS) return null;
        if (scripts.size() == 0) return null;

        String known = asked.get(phrase);
        if (known != null) {
            return known.isEmpty() ? null : scripts.byPattern(known);
        }

        String answer;
        try {
            answer = aicoin.text(wallet, prompt(phrase), null, true);
        } catch (Exception x) {
            // Not remembered: this is a failure to ask, not an answer of "no",
            // and a proxy that was down for a minute should not decide that a
            // phrase is unrecognisable for the rest of the process's life.
            System.out.printf("sue: could not ask what \"%s\" means: %s%n", phrase, x.getMessage());
            return null;
        }

        String said = Scripts.normalise(answer.lines().findFirst().orElse(""));
        var found = said.isBlank() || said.equals("none") ? null : scripts.byPattern(said);
        if (found == null) {
            remember(phrase, "");
            return null;
        }
        // It named something real. The phrase becomes a pattern for it, and
        // every future occurrence is matched rather than asked about.
        var added = scripts.alias(found, phrase);
        remember(phrase, added == null ? "" : added.pattern());
        if (added == null) return null;
        System.out.printf("sue: \"%s\" is another way of saying \"%s\" (%s)%n",
                phrase, found.pattern(), found.name());
        return added;
    }

    private void remember(String phrase, String pattern) {
        // Not an eviction policy so much as a ceiling. What lands here is one
        // entry per distinct short phrase anybody has ever typed, which grows
        // like vocabulary rather than like traffic - slowly, and then not at
        // all. Clearing it wholesale when it gets large costs one fast call per
        // phrase to rebuild and cannot leak.
        if (asked.size() >= MAX_REMEMBERED) asked.clear();
        asked.put(phrase, pattern);
    }

    /**
     * The question, with the catalogue in it.
     *
     * Written to be answered wrongly in the safe direction. Every instruction
     * about the person's own text is a "none", and saying so is free - the
     * request goes on to a real model, which is where it was going anyway. A
     * false match, on the other hand, hands somebody an answer to a question
     * they did not ask, so the prompt spends most of its length on the cases
     * that are not matches.
     */
    private String prompt(String phrase) {
        return """
                A person typed this into a text editor:

                    %s

                This server already has a stored answer for each of these
                requests, listed as: name :: pattern

                %s
                Is what they typed another way of saying exactly one of those,
                such that the stored answer would be the right answer for it?

                If so, reply with that entry's pattern, copied exactly, alone on
                one line and with nothing else.

                Otherwise reply: none

                Reply "none" whenever there is any doubt at all. In particular:

                - If it is about their own text - their file, their function,
                  their variable - it is not one of these, whatever words it
                  shares with them. "clear" is a request to clear the screen.
                  "clear the results array" is somebody's program.
                - If it is more specific than the stored request, or asks for
                  something extra, or asks for the same thing about a different
                  subject, it is not the same request.
                - If it is a question about how to do the thing, rather than a
                  request to do it, it is not the same request.
                - A pattern containing {braces} takes an argument. Only say it
                  matches if what they typed supplies that argument.

                Answering "none" costs a few seconds. Answering wrongly gives
                somebody a canned reply to a question they did not ask, in the
                middle of their document.
                """.formatted(phrase, scripts.inventory());
    }

    /** For the status page: how much of this the server no longer has to ask about. */
    String asJson() {
        int recognised = 0;
        for (var v : asked.values()) if (!v.isEmpty()) recognised++;
        return Json.obj("phrases", asked.size(), "recognised", recognised,
                "unrecognised", asked.size() - recognised);
    }
}
