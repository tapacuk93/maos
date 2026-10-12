import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The other destination for a script body: compiled here, and run here. */
class ScriptVmTest {

    @Test
    void aBodyThatCompilesAnswers() {
        var vm = new ScriptVm();
        var why = new StringBuilder();
        var body = vm.compile("greet", List.of("name"), "return \"hello \" + name;", why);
        assertNotNull(body, why.toString());
        assertEquals("hello world", vm.run(body, Map.of("name", "world")));
    }

    @Test
    void returningNothingIsDeclining() {
        var vm = new ScriptVm();
        var why = new StringBuilder();
        var body = vm.compile("picky", List.of("name"),
                "if (name.equals(\"yes\")) { return \"ok\"; } return null;", why);
        assertNotNull(body, why.toString());
        assertEquals("ok", vm.run(body, Map.of("name", "yes")));
        assertNull(vm.run(body, Map.of("name", "no")));
    }

    @Test
    void aBodyThatReachesOutsideIsRefusedWithAReason() {
        var vm = new ScriptVm();
        var why = new StringBuilder();
        assertNull(vm.compile("bad", List.of(),
                "new java.io.File(\"/etc/passwd\").delete(); return \"\";", why));
        assertFalse(why.isEmpty(), "a refusal nobody can read is a refusal nobody can fix");
    }

    @Test
    void itSaysWhetherItCanCompileAtAll() {
        assertNotNull(ScriptVm.available());
    }
}
