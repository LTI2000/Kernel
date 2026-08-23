package kernel;

import kernel.builtins.Ground;
import kernel.model.Obj.Env;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static kernel.eval.Evaluator.loadFile;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the Kernel-language test suite ({@code tests.krn}) as a JUnit test, so
 * {@code mvn test} exercises the interpreter the same way the project's own
 * checks do.
 */
class KernelTest {

    @Test
    void testsKrnSuitePasses() {
        Env env = new Env(Ground.GROUND);

        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            loadFile("src/test/resources/tests.krn", env);
        } finally {
            System.setOut(originalOut);
        }

        String output = captured.toString(StandardCharsets.UTF_8);
        System.out.print(output);

        assertFalse(output.contains("FAIL"), "tests.krn reported a failing check:\n" + output);
        assertTrue(output.contains("failed: 0"), "tests.krn did not report a clean run:\n" + output);
    }
}
