import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The things the server remembers between one request and the next: accounts,
 * what people have been doing, what several people have agreed on, what is
 * waiting for somebody else to ask for it, and which clouds an account can
 * reach.
 */
class StateTest {

    private static Accounts.Account anAccount(Accounts accounts) {
        return accounts.create("wallet", "AKIAEXAMPLE", "secret", "us-east-1", "password").account();
    }

    @Test
    void anIssuedKeyFindsItsAccountAndNothingElseDoes() {
        var accounts = new Accounts();
        var issued = accounts.create("w", "k", "s", "eu-west-1", "p");
        assertTrue(accounts.byKey(issued.key()).isPresent());
        assertTrue(accounts.byKey("not a key").isEmpty());
        assertTrue(accounts.byKey(null).isEmpty());
        assertEquals("eu-west-1", issued.account().region());
    }

    @Test
    void accountsSurviveARestart(@TempDir Path dir) throws Exception {
        var file = dir.resolve("accounts.tsv");
        var first = new Accounts();
        first.openStore(file);
        var issued = first.create("w", "AKIA", "secret", "us-east-1", "p");
        first.persist();
        assertTrue(Files.exists(file));

        var second = new Accounts();
        second.openStore(file);
        assertTrue(second.byKey(issued.key()).isPresent(),
                "a redeploy that invalidates every device's key is not a redeploy");
    }

    @Test
    void anAccountWithNoWorkingCredentialsIsLentTheBackends(@TempDir Path dir) throws Exception {
        var file = dir.resolve("accounts.tsv");
        var first = new Accounts();
        first.openStore(file);
        var issued = first.create("w", "unset", "unset", "us-east-1", "p");
        first.persist();

        Clouds.house(Map.of("access_key", "HOUSE", "secret_key", "s", "region", "us-east-1"));
        var second = new Accounts();
        second.openStore(file);
        var back = second.byKey(issued.key()).orElseThrow();
        assertEquals("HOUSE", back.awsKey(), "the word 'unset' is not a credential");
        assertTrue(back.clouds().has(Clouds.Provider.AWS));
    }

    @Test
    void aFileThatWillNotParseIsLeftAlone(@TempDir Path dir) throws Exception {
        var file = dir.resolve("accounts.tsv");
        Files.writeString(file, "this is not an account file\n");
        var accounts = new Accounts();
        accounts.openStore(file);
        assertEquals("this is not an account file\n", Files.readString(file),
                "starting empty costs a re-registration; overwriting costs the accounts");
    }

    @Test
    void theJournalRemembersWhatWasDoneAndSaysSo(@TempDir Path dir) {
        var accounts = new Accounts();
        var account = anAccount(accounts);
        var journal = new Journal(dir);
        journal.edits(account, 1, List.of(new Journal.Edit(1L, "typed", 0, "hello")));
        journal.gestures(account, 1, List.of(new Journal.Gesture(2L, "swipe", 0, "word", 1, 0)));
        String b = journal.briefing(account, 1);
        // A summary rather than a transcript: the model is told that something
        // was typed at an offset and that a word was swiped, not handed the
        // bytes back. The screen it already has carries those.
        assertTrue(b.contains("typed"), b);
        assertTrue(b.contains("swiped"), b);
        assertEquals("", journal.briefing(account, 2), "a screen nobody touched has no history");
        journal.close();
    }

    @Test
    void acloudIsBoundAndThenChosenBetween() {
        var c = new Clouds();
        assertFalse(c.has(Clouds.Provider.AWS));
        assertTrue(c.available().isEmpty());
        assertNull(c.choose(false));
        assertTrue(c.briefing().contains("No cloud"));

        c.bind(Clouds.Provider.AWS, Map.of(
                "access_key", "k", "secret_key", "s", "region", "us-east-1"));
        assertTrue(c.has(Clouds.Provider.AWS));
        assertEquals(List.of(Clouds.Provider.AWS), c.available());
        assertEquals(Clouds.Provider.AWS, c.choose(true).provider());
        assertTrue(c.briefing().contains("aws"));
    }

    @Test
    void halfACredentialIsNoCredential() {
        var c = new Clouds();
        c.bind(Clouds.Provider.AWS, Map.of("access_key", "k"));
        assertFalse(c.has(Clouds.Provider.AWS), "a key with no secret cannot sign anything");
        assertNotNull(c.get(Clouds.Provider.AWS), "it is bound, it is just not complete");
    }

    @Test
    void theCheapestOneThatCanActuallyBeBroughtUpWins() {
        var c = new Clouds();
        c.bind(Clouds.Provider.AWS, Map.of(
                "access_key", "k", "secret_key", "s", "region", "us-east-1"));
        c.bind(Clouds.Provider.HETZNER, Map.of("api_token", "t"));
        assertEquals(Clouds.Provider.HETZNER, c.choose(false).provider());
        assertEquals(Clouds.Provider.AWS, c.choose(true).provider(),
                "work that names AWS resources has to run where they are");
    }

    @Test
    void aProviderIsFoundByItsIdAndNothingElse() {
        assertEquals(Clouds.Provider.AWS, Clouds.Provider.of("aws"));
        assertEquals(Clouds.Provider.GCP, Clouds.Provider.of("GCP"));
        assertNull(Clouds.Provider.of("nobody"));
        assertNull(Clouds.Provider.of(null));
    }

    @Test
    void theHouseCredentialIsOnlyThereWhenItIsComplete() {
        Clouds.house(Map.of("access_key", "k", "secret_key", "s", "region", "us-east-1"));
        assertNotNull(Clouds.house());
        Clouds.house(Map.of("access_key", "k"));
        assertNull(Clouds.house(), "half a credential lent out is a failure four layers down");
    }

    @Test
    void onePersonIsEnoughToBeConsideredAndNobodyIsEnoughToShip() {
        // The threshold here is one, deliberately: the first person to want
        // something could never have been the third, so a higher bar made the
        // first operation on any subject unreachable. The real gate is the
        // consortium, which is asked afterwards and told how many people this
        // was. See Consensus's own notes.
        var c = new Consensus();
        var settled = c.record("a", "", "tetris game", "here is tetris");
        assertNotNull(settled);
        assertEquals(1, settled.people());
        assertTrue(Consensus.promotionPrompt(settled).contains("tetris game"));
        assertTrue(c.asJson().contains("agreed"));
        assertNotNull(c.pending());
    }

    @Test
    void theSamePersonTwiceIsStillOnePerson() {
        var c = new Consensus();
        c.record("a", "", "tetris game", "here is tetris");
        var again = c.record("a", "", "tetris game", "here is tetris");
        assertTrue(again == null || again.people() == 1);
    }

    @Test
    void aMachineSizeHasANameOnEveryCloudThatCanRunOne() {
        for (var p : Clouds.Provider.values()) {
            assertNotNull(Machines.spec(p, Machines.Size.SMALLEST), p.id);
        }
        assertTrue(Machines.briefing(List.of(Clouds.Provider.AWS)).contains("smallest"));
    }

    @Test
    void aRestartIsOwedOnlyForTheThingsThatNeedOne() {
        var r = new Release();
        assertFalse(r.rebootPending());
        assertEquals("", r.takeNotice());
        r.note("an operation", false, "no restart needed");
        assertFalse(r.rebootPending());
        r.note("the boot code", true, "this one needs one");
        assertTrue(r.rebootPending());
        assertFalse(r.takeNotice().isEmpty());
        assertEquals("", r.takeNotice(), "said once, and then not again");
        assertEquals(2, r.recent().size());
    }

    @Test
    void whichPathsNeedARestart() {
        assertTrue(Release.needsReboot("kernel/arch/aarch64/boot.S"));
        assertFalse(Release.needsReboot("ops/set-colour.bin"));
        assertFalse(Release.needsReboot(null));
    }
}
