import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** What each model has been like at each kind of work, from what happened. */
class ModelStatsTest {

    @Test
    void theKindOfWorkIsReadOffTheScreen() {
        assertNotNull(ModelStats.classify("aify", "class Thing {}"));
        assertNotNull(ModelStats.classify("agent", "deploy the cluster"));
        assertNotNull(ModelStats.classify("swipe", ""));
        assertNotNull(ModelStats.classify(null, null));
    }

    @Test
    void withNothingRecordedTheOrderGivenDecides() {
        var s = new ModelStats();
        var c = ModelStats.classify("aify", "prose");
        assertEquals("opus", s.best(c, List.of("opus", "sonnet")),
                "every candidate scores the same, so the first one stands");
        assertNull(s.best(c, List.of()), "and no candidates is no answer");
    }

    @Test
    void theOneThatAnswersIsPreferredToTheOneThatFails() {
        var s = new ModelStats();
        var c = ModelStats.classify("aify", "prose");
        for (int i = 0; i < 10; i++) {
            s.called("good", c, 1000, 500);
            s.failed("bad", c);
        }
        assertEquals("good", s.best(c, List.of("bad", "good")));
    }

    @Test
    void beingAskedAgainCountsAgainstWhoeverAnsweredFirst() {
        var s = new ModelStats();
        var c = ModelStats.classify("aify", "prose");
        for (int i = 0; i < 10; i++) {
            s.called("a", c, 1000, 500);
            s.called("b", c, 1000, 500);
        }
        for (int i = 0; i < 10; i++) s.reasked("a", c);
        assertEquals("b", s.best(c, List.of("a", "b")),
                "a second ask about the same screen is not a compliment");
    }

    @Test
    void handingOverIsRecordedOnBothSides() {
        var s = new ModelStats();
        var c = ModelStats.classify("aify", "prose");
        s.handedAway("a", c);
        s.handedTo("b", c);
        assertTrue(s.asJson().contains("a"), s.asJson());
        assertTrue(s.asJson().contains("b"), s.asJson());
    }

    @Test
    void theBriefingSurvivesHavingNothingToSay() {
        var s = new ModelStats();
        assertNotNull(s.briefing(List.of("opus"), ModelStats.classify("aify", "")));
        assertNotNull(s.asJson());
    }
}
