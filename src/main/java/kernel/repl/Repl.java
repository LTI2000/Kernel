package kernel.repl;

import kernel.model.Const;
import kernel.model.ContinuationInvoked;
import kernel.model.Env;
import kernel.model.KernelError;
import kernel.model.Obj;
import kernel.printer.Printer;
import kernel.reader.Reader;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static kernel.eval.Evaluator.eval;

/** The interactive read-eval-print loop. */
public final class Repl {

    private Repl() {}

    public static void run(Env env) {
        System.out.println("Kernel (R^-1RK subset) on Java " + Runtime.version().feature()
                + " -- ctrl-D to exit");
        Reader reader = new Reader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            System.out.print("kernel> ");
            System.out.flush();
            Obj form;
            try {
                form = reader.read();
            } catch (KernelError e) {
                System.out.println("; read error: " + e.getMessage());
                continue;
            }
            if (form == null) { System.out.println(); return; }
            try {
                Obj value = eval(form, env);
                if (value != Const.INERT) System.out.println(Printer.write(value));
            } catch (KernelError e) {
                System.out.println("; error: " + e.getMessage());
            } catch (ContinuationInvoked e) {
                System.out.println("; error: continuation invoked outside its extent");
            } catch (StackOverflowError | OutOfMemoryError e) {
                System.out.println("; error: recursion too deep -- "
                        + "non-tail calls exhausted the stack");
            }
        }
    }
}
