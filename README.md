# Kernel Interpreter in Java

An interpreter for **Kernel** (John Shutt's Scheme dialect, R⁻¹RK), split into
a small package weave. Instead of macros and special forms there are only two
building blocks: operatives (`$vau`, receive their operands **unevaluated**
plus the calling environment) and applicatives (`wrap` – evaluate their
operands). Environments are first-class values.

## Prerequisites

JDK 21 or newer, Maven 3.9 or newer.

## Building and running

```bash
mvn package                                                        # builds target/kernel-interpreter-1.0-SNAPSHOT.jar
java -jar target/kernel-interpreter-1.0-SNAPSHOT.jar                               # REPL
java -jar target/kernel-interpreter-1.0-SNAPSHOT.jar src/main/resources/demo.krn    # run a file
java -jar target/kernel-interpreter-1.0-SNAPSHOT.jar src/test/resources/tests.krn   # test suite (38 checks)
java -jar target/kernel-interpreter-1.0-SNAPSHOT.jar -e '(+ 1 2)'                   # single expression
java -jar target/kernel-interpreter-1.0-SNAPSHOT.jar src/main/resources/demo.krn -i # load a file, then REPL

mvn test                                                           # runs tests.krn as a JUnit test
mvn exec:java -Dexec.args="src/main/resources/demo.krn"            # without building a package first
```

## Files

| File | Contents |
|---|---|
| `pom.xml` | Maven project definition (build, tests, runnable jar) |
| `src/main/java/kernel/Main.java` | Entry point: CLI arguments, wires up the ground environment and REPL |
| `src/main/java/kernel/model/` | Object model (`Obj` hierarchy: `Sym`, `Pair`, `Env`, `Vau`, …), `Values` helper functions |
| `src/main/java/kernel/reader/` | `Reader` – parses Kernel source text into `Obj` data |
| `src/main/java/kernel/printer/` | `Printer` – renders `Obj` data back to text (`write`/`display`) |
| `src/main/java/kernel/eval/` | `Evaluator` – the trampolined evaluation loop |
| `src/main/java/kernel/builtins/` | `Ground` (standard environment), `Numbers`, `Prelude` (operatives written in Kernel itself) |
| `src/main/java/kernel/repl/` | `Repl` – the interactive read-eval-print loop |
| `src/test/java/kernel/KernelTest.java` | JUnit test that runs `tests.krn` and checks for `failed: 0` |
| `src/main/resources/demo.krn` | A tour of the language (fexprs, environments, streams, continuations) |
| `src/test/resources/tests.krn` | Test suite, prints `passed: … failed: …` |

## Design

- **Package structure** – `kernel.model` (values), `kernel.reader`, `kernel.printer`,
  `kernel.eval` (evaluation loop) and `kernel.builtins` (standard environment plus
  prelude) are cleanly separated; `kernel.repl` and `kernel.Main` form the outer
  layer. The split follows the phases of an interpreter: read, evaluate, print,
  plus the values they all operate on.
- **Object model** (`kernel.model`) – a `sealed interface Obj`; records for the
  immutable atoms, final classes for `Pair`, `Env`, `Vau`. The evaluator and
  printer work over it with pattern matching.
- **Real tail calls** – `Evaluator.eval` is a single loop. Primitives that call
  in tail position (`$if`, `$sequence`, `eval`, `apply`) return a `TailCall`
  token instead of recursing. `(count-down 1000000 0)` runs with constant stack.
- **Small Java core** – only `$vau`, `wrap`/`unwrap`, `$define!`, `$if`, `eval`
  and so on are in Java (`kernel.builtins.Ground`). `$lambda`, `$let`, `$let*`,
  `$letrec`, `$cond`, `$and?`, `$set!` are derived in the `Prelude` text block
  in Kernel itself.
- Non-tail-recursive calls consume JVM stack, so `main` runs in a thread with a
  64 MB stack; exhaustion is reported as an error message, not a stack trace.

## Scope

Booleans, equivalence (`eq?`, `equal?`), symbols, pairs and lists (incl. `caar`…`cddddr`,
`map`, `filter`, `reduce`, `assoc`), combiners (`$vau`, `wrap`, `unwrap`, `apply`),
environments (`make-environment`, `$binds?`, `get-current-environment`,
`make-kernel-standard-environment`), numbers (BigInteger + floating point), strings,
characters, I/O, promises (`$lazy`, `force`, `memoize`), encapsulations
(`make-encapsulation-type`), `call/cc`.

## Deviations from the report

- Continuations are **escape-only** (exception-based): no re-entry, no
  `guard-dynamic-extent`.
- No exact rationals – `/` yields an inexact result when division doesn't
  come out even.
- Cyclic lists are detected and rejected instead of measured via
  `get-list-metrics`.
- `$quote` is included as a convenience (`'x` → `($quote x)`), even though
  Shutt deliberately leaves it out.
