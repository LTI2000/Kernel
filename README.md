# Kernel-Interpreter in Java

Ein Interpreter für **Kernel** (John Shutts Scheme-Dialekt, R⁻¹RK), aufgeteilt in
ein kleines Paket-Geflecht. Statt Makros und Spezialformen gibt es nur zwei
Bausteine: Operative (`$vau`, bekommen ihre Operanden **unausgewertet** plus die
Aufruf-Umgebung) und Applicative (`wrap` – werten die Operanden aus). Umgebungen
sind first-class Werte.

## Voraussetzungen

JDK 21 oder neuer, Maven 3.9 oder neuer.

## Bauen und starten

```bash
mvn package                                          # baut target/kernel-interpreter-1.0-SNAPSHOT.jar
java -jar target/kernel-interpreter-1.0-SNAPSHOT.jar                 # REPL
java -jar target/kernel-interpreter-1.0-SNAPSHOT.jar demo.krn        # Datei ausführen
java -jar target/kernel-interpreter-1.0-SNAPSHOT.jar tests.krn       # Testsuite (38 Checks)
java -jar target/kernel-interpreter-1.0-SNAPSHOT.jar -e '(+ 1 2)'    # einzelner Ausdruck
java -jar target/kernel-interpreter-1.0-SNAPSHOT.jar demo.krn -i     # Datei laden, dann REPL

mvn test                                              # führt tests.krn als JUnit-Test aus
mvn exec:java -Dexec.args="demo.krn"                  # ohne vorheriges Package bauen
```

## Dateien

| Datei | Inhalt |
|---|---|
| `pom.xml` | Maven-Projektdefinition (Build, Tests, ausführbares Jar) |
| `src/main/java/kernel/Main.java` | Einstiegspunkt: CLI-Argumente, verkabelt Ground-Umgebung und REPL |
| `src/main/java/kernel/model/` | Objektmodell (`Obj`-Hierarchie: `Sym`, `Pair`, `Env`, `Vau`, …), `Values`-Hilfsfunktionen |
| `src/main/java/kernel/reader/` | `Reader` – parst Kernel-Quelltext zu `Obj`-Daten |
| `src/main/java/kernel/printer/` | `Printer` – rendert `Obj`-Daten zurück zu Text (`write`/`display`) |
| `src/main/java/kernel/eval/` | `Evaluator` – die Trampolin-Auswertungsschleife |
| `src/main/java/kernel/builtins/` | `Ground` (Standardumgebung), `Numbers`, `Prelude` (in Kernel selbst geschriebene Operative) |
| `src/main/java/kernel/repl/` | `Repl` – die interaktive Read-Eval-Print-Loop |
| `src/test/java/kernel/KernelTest.java` | JUnit-Test, der `tests.krn` ausführt und auf `failed: 0` prüft |
| `demo.krn` | Rundgang durch die Sprache (Fexprs, Umgebungen, Streams, Continuations) |
| `tests.krn` | Testsuite, gibt `passed: … failed: …` aus |

## Aufbau

- **Paketstruktur** – `kernel.model` (Werte), `kernel.reader`, `kernel.printer`,
  `kernel.eval` (Auswertungsschleife) und `kernel.builtins` (Standardumgebung samt
  Prelude) sind sauber getrennt; `kernel.repl` und `kernel.Main` bilden die
  Außenseite. Die Trennung folgt den Phasen eines Interpreters: lesen, auswerten,
  drucken, plus die Werte, mit denen sie alle arbeiten.
- **Objektmodell** (`kernel.model`) – ein `sealed interface Obj`; Records für die
  unveränderlichen Atome, finale Klassen für `Pair`, `Env`, `Vau`. Evaluator und
  Printer arbeiten mit Pattern Matching darüber.
- **Echte Tail Calls** – `Evaluator.eval` ist eine einzige Schleife. Primitive, die
  in Tail-Position aufrufen (`$if`, `$sequence`, `eval`, `apply`), liefern ein
  `TailCall`-Token zurück, statt sich rekursiv aufzurufen. `(count-down 1000000 0)`
  läuft mit konstantem Stack.
- **Kleiner Java-Kern** – nur `$vau`, `wrap`/`unwrap`, `$define!`, `$if`, `eval` usw.
  sind in Java (`kernel.builtins.Ground`). `$lambda`, `$let`, `$let*`, `$letrec`,
  `$cond`, `$and?`, `$set!` werden im `Prelude`-Textblock in Kernel selbst
  abgeleitet.
- Nicht-tail-rekursive Aufrufe verbrauchen JVM-Stack, deshalb läuft `main` in einem
  Thread mit 64 MB Stack; Erschöpfung wird als Fehlermeldung gemeldet, nicht als
  Stacktrace.

## Umfang

Booleans, Äquivalenz (`eq?`, `equal?`), Symbole, Paare und Listen (inkl. `caar`…`cddddr`,
`map`, `filter`, `reduce`, `assoc`), Kombinatoren (`$vau`, `wrap`, `unwrap`, `apply`),
Umgebungen (`make-environment`, `$binds?`, `get-current-environment`,
`make-kernel-standard-environment`), Zahlen (BigInteger + Gleitkomma), Strings, Zeichen,
I/O, Promises (`$lazy`, `force`, `memoize`), Encapsulations
(`make-encapsulation-type`), `call/cc`.

## Abweichungen vom Report

- Continuations nur als **Escape** (exception-basiert): kein Wiedereintritt, kein
  `guard-dynamic-extent`.
- Keine exakten Rationalzahlen – `/` liefert bei nicht aufgehender Division ein
  Inexact-Ergebnis.
- Zyklische Listen werden erkannt und abgelehnt statt via `get-list-metrics` vermessen.
- `$quote` ist als Bequemlichkeit enthalten (`'x` → `($quote x)`), obwohl Shutt es
  bewusst weglässt.
