package kernel;

import kernel.builtins.Ground;
import kernel.model.ContinuationInvoked;
import kernel.model.KernelError;
import kernel.model.Obj;
import kernel.model.Obj.Const;
import kernel.model.Obj.Env;
import kernel.printer.Printer;
import kernel.repl.Repl;

import static kernel.eval.Evaluator.evalString;
import static kernel.eval.Evaluator.loadFile;

/**
 * Entry point for an interpreter of Kernel, John Shutt's Scheme dialect
 * (R^-1RK), in which fexprs ({@code $vau}) and first-class environments
 * replace macros and special forms entirely.
 *
 * <p>Run it:
 * <pre>
 *   java -jar target/kernel-interpreter-1.0-SNAPSHOT.jar              # REPL
 *   java -jar target/kernel-interpreter-1.0-SNAPSHOT.jar demo.krn     # run a file
 *   java -jar target/kernel-interpreter-1.0-SNAPSHOT.jar -e '(+ 1 2)' # evaluate one expression
 * </pre>
 *
 * <p>The interpreter itself is spread across a handful of packages:
 * <ul>
 *   <li>{@code kernel.model} -- the {@code sealed interface Obj} value hierarchy
 *       (pairs, symbols, numbers, environments, combiners, ...) plus the free
 *       {@link kernel.model.Values} helpers (list plumbing, equivalence).</li>
 *   <li>{@code kernel.reader} -- parses source text into {@code Obj} data.</li>
 *   <li>{@code kernel.printer} -- renders {@code Obj} data back to text.</li>
 *   <li>{@code kernel.eval} -- the trampolined evaluator. Primitives that
 *       tail-call (like {@code $if}, {@code eval}, {@code apply}) return a
 *       {@code TailCall} token that the eval loop unwinds, guaranteeing
 *       proper tail calls.</li>
 *   <li>{@code kernel.builtins} -- the ground environment: only {@code $vau},
 *       {@code wrap}/{@code unwrap}, {@code $define!}, {@code $if}, {@code eval}
 *       and friends are written in Java. Derived operatives ({@code $lambda},
 *       {@code $let}, {@code $cond}, ...) are bootstrapped in Kernel itself,
 *       from the {@code Prelude} source.</li>
 *   <li>{@code kernel.repl} -- the interactive read-eval-print loop.</li>
 * </ul>
 */
public final class Main {

    private Main() {}

    private static void run(String[] args) {
        Env env = new Env(Ground.GROUND);
        boolean startRepl = args.length == 0;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "-e", "--eval" -> {
                        if (++i == args.length) throw new KernelError("-e needs an expression");
                        Obj value = evalString(args[i], env);
                        if (value != Const.INERT) System.out.println(Printer.write(value));
                    }
                    case "-i", "--repl" -> startRepl = true;
                    case "-h", "--help" -> {
                        System.out.println("usage: java -jar kernel-interpreter.jar [-e EXPR] [-i] [FILE ...]");
                        return;
                    }
                    default -> loadFile(args[i], env);
                }
            }
        } catch (KernelError e) {
            System.err.println("; error: " + e.getMessage());
            System.exit(1);
        } catch (ContinuationInvoked e) {
            System.err.println("; error: continuation invoked outside its dynamic extent");
            System.exit(1);
        } catch (StackOverflowError | OutOfMemoryError e) {
            System.err.println("; error: recursion too deep -- non-tail calls exhausted the stack");
            System.exit(1);
        }
        if (startRepl) Repl.run(env);
    }

    public static void main(String[] args) throws InterruptedException {
        // Non-tail Kernel recursion consumes Java stack, so give it room --
        // but not so much that a runaway recursion exhausts the heap instead.
        Thread main = new Thread(null, () -> run(args), "kernel", 64L * 1024 * 1024);
        main.start();
        main.join();
    }
}
