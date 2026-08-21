# Kernel-Interpreter in Java

Ein Interpreter für **Kernel** (John Shutts Scheme-Dialekt, R⁻¹RK), in einer einzigen
Java-Datei. Statt Makros und Spezialformen gibt es nur zwei Bausteine: Operative
(`$vau`, bekommen ihre Operanden **unausgewertet** plus die Aufruf-Umgebung) und
Applicative (`wrap` – werten die Operanden aus). Umgebungen sind first-class Werte.

## Voraussetzungen

JDK 21 oder neuer.

## Starten

```bash
java Kernel.java                 # REPL
java Kernel.java demo.krn        # Datei ausführen
java Kernel.java tests.krn       # Testsuite (38 Checks)
java Kernel.java -e '(+ 1 2)'    # einzelner Ausdruck
java Kernel.java demo.krn -i     # Datei laden, dann REPL
```

## Dateien

| Datei | Inhalt |
|---|---|
| `Kernel.java` | Objektmodell, Reader, Printer, Evaluator, Standardumgebung, Prelude, REPL |
| `demo.krn` | Rundgang durch die Sprache (Fexprs, Umgebungen, Streams, Continuations) |
| `tests.krn` | Testsuite, gibt `passed: … failed: …` aus |

## Aufbau

- **Objektmodell** – ein `sealed interface Obj`; Records für die unveränderlichen
  Atome, finale Klassen für `Pair`, `Env`, `Vau`. Evaluator und Printer arbeiten mit
  Pattern Matching darüber.
- **Echte Tail Calls** – `eval` ist eine einzige Schleife. Primitive, die in
  Tail-Position aufrufen (`$if`, `$sequence`, `eval`, `apply`), liefern ein
  `TailCall`-Token zurück, statt sich rekursiv aufzurufen. `(count-down 1000000 0)`
  läuft mit konstantem Stack.
- **Kleiner Java-Kern** – nur `$vau`, `wrap`/`unwrap`, `$define!`, `$if`, `eval` usw.
  sind in Java. `$lambda`, `$let`, `$let*`, `$letrec`, `$cond`, `$and?`, `$set!`
  werden im `PRELUDE`-Textblock in Kernel selbst abgeleitet.
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
