# Kernel language reference

This describes the syntax and core semantics implemented by this interpreter:
lexical syntax (what the `Reader` accepts), the data types, the evaluation
rule, parameter-tree binding, and every special form / built-in the ground
environment and prelude define. For the bigger picture (why operatives
instead of macros, project layout) see [README.md](README.md).

## 1. Lexical syntax

Kernel source is a sequence of **datums** (s-expressions) separated by
"atmosphere" (whitespace and comments).

### Comments

| Form | Meaning |
|---|---|
| `; ...` | line comment, runs to end of line |
| `#\| ... \|#` | block comment, may nest |
| `#;datum` | datum comment – reads and discards the next datum |

### Delimiters

A token ends at whitespace, `(`, `)`, `[`, `]`, `"`, `;`, `'`, or end of input.
`[` / `]` are accepted as alternate list brackets, purely for readability –
`[a b]` and `(a b)` read identically, and brackets must match in kind.

### Lists and pairs

```
(a b c)          ; proper list: (a . (b . (c . ())))
(a b . c)        ; dotted pair as tail: (a . (b . c))
()               ; the empty list / nil
[a b c]          ; same as (a b c)
```

### Quote sugar

```
'x               ; reads as ($quote x)
```
`$quote` is a convenience added by this implementation; the Kernel report
deliberately omits it in favor of always writing `($quote x)` (or relying on
operatives not evaluating their operands in the first place).

### Booleans and other `#`-constants

| Literal | Value |
|---|---|
| `#t`, `#true` | true |
| `#f`, `#false` | false |
| `#inert` | the inert value, returned by side-effecting forms |
| `#ignore` | the ignore value – used in parameter trees to discard an operand |
| `#eof` | end-of-file object |

(case-insensitive: `#T`, `#True`, … all work)

### Numbers

```
42            ; exact integer (arbitrary precision, java.math.BigInteger)
-7  +3        ; sign prefix allowed
3.14          ; inexact real (IEEE double)
1e10  .5      ; also inexact
```
A token starting with a digit, or with `+`/`-`/`.` followed by more
characters, is tried as a number first (integer, then double) and falls back
to a symbol if it doesn't parse – so `+`, `-`, `...` and `->` are ordinary
symbols, but `-1` and `.5` are numbers.

### Strings

```
"hello\nworld"
```
Escapes: `\n` `\t` `\r` `\0` `\"` `\\`; any other `\x` reads as literal `x`.

### Characters

```
#\a  #\Z  #\(  #\#      ; literal character
#\space #\newline #\tab #\return #\null   ; named characters (also #\linefeed, #\nul)
```

### Symbols

Anything else that isn't a number, string, character, list, or `#`-constant
is read as a symbol, e.g. `foo`, `list->vector`, `$my-if`, `+`, `<?`. Symbols
starting with `$` are simply symbols too – by convention they name
**operatives** (see below), but nothing in the reader enforces that.

## 2. Data types at a glance

| Type | Examples | Predicate |
|---|---|---|
| Boolean | `#t` `#f` | `boolean?` |
| Symbol | `foo` `$if` | `symbol?` |
| Pair | `(1 . 2)` | `pair?` |
| Empty list | `()` | `null?` |
| List | `(1 2 3)` | `list?` |
| Number (exact) | `42` | `number?` `integer?` `exact?` |
| Number (inexact) | `3.14` | `number?` `inexact?` |
| String | `"hi"` | `string?` |
| Character | `#\a` | `char?` |
| Environment | (result of `make-environment`, …) | `environment?` |
| Operative | `$vau`, or any value made with `$vau` | `operative?` |
| Applicative | `car`, `+`, or `(wrap some-operative)` | `applicative?` |
| Combiner | operative or applicative | `combiner?` |
| Promise | result of `$lazy` | `promise?` |
| Inert | `#inert` | `inert?` |
| Ignore | `#ignore` | `ignore?` |
| Eof | `#eof` | `eof-object?` |
| Encapsulation | result of a type from `make-encapsulation-type` | (its own predicate) |

`eq?` tests identity (with numbers/chars compared by value); `equal?` tests
structural equality, recursing into pairs and strings.

## 3. Evaluation rule

There is exactly one rule, applied by `eval` to an expression and an
environment:

1. A **symbol** evaluates by looking itself up in the environment (and its
   parents), or errors if unbound.
2. Anything that is **not a pair** (numbers, strings, booleans, `#inert`, a
   combiner, an environment, …) is **self-evaluating** – it evaluates to
   itself.
3. A **pair** `(op . operands)` is a combination: `op` is evaluated to get a
   **combiner**, which is then combined with `operands`:
   - If the combiner is an **applicative**, its operands are evaluated first
     (left to right), and the *underlying* combiner is combined with the
     evaluated operand list. Applicatives can wrap applicatives; unwrapping
     repeats until an operative is reached.
   - If the combiner is an **operative**, it receives the operand list
     **exactly as written** (unevaluated), plus the caller's dynamic
     environment. What it does with them – evaluate, rewrite, ignore – is up
     to the operative.

This is the whole language: no macro phase, no special-form table baked into
`eval`. `$if`, `$define!`, `$vau` etc. are simply operatives bound to those
names in the ground environment; `$lambda`, `$let`, `$cond` and friends are
ordinary operatives defined *in Kernel* (see §6).

### Making combiners

```
($vau ptree eformal . body)
```
Evaluates to an **operative**. When combined with operands `O` in dynamic
environment `E`:
- `ptree` is matched against `O` (see §4) in a fresh local environment.
- if `eformal` is a symbol, it is bound to `E` in that local environment (use
  `#ignore` to discard it).
- the forms in `body` are evaluated in order in the local environment; the
  value of the last one (in tail position) is the result.

```
(wrap combiner)      ; -> an applicative around combiner
(unwrap applicative)  ; -> the underlying operative
```
`wrap`/`unwrap` are how you cross between "operands evaluated" and "operands
raw" – there is no other mechanism, and no distinction between "function"
and "special form" in the object model.

## 4. Parameter trees (formals)

Both `$vau`'s `ptree` and `$lambda`'s formals are **parameter trees**:
patterns matched structurally against the operand list, binding symbols as
they're encountered (`bind` in the evaluator):

| Pattern | Matches | Effect |
|---|---|---|
| a symbol `s` | anything | binds `s` to the whole value |
| `#ignore` | anything | discards the value |
| `()` | only `()` | asserts no more operands remain |
| `(p1 . p2)` | a pair | matches `p1` against the car, `p2` against the cdr |

Because the pattern is just a pair tree, all of these fall out for free:

```
(a b c)        ; exactly three operands, bound to a, b, c
(a . rest)     ; a bound to the first operand, rest to the remaining list
args           ; the entire operand list bound to args (fully variadic)
(a #ignore c)  ; three operands, the second discarded
()             ; no operands at all
```

## 5. Built-in special forms (ground environment, `kernel.builtins.Ground`)

These are implemented directly in Java; everything else in §6 is built from
them.

| Form | Syntax | Behavior |
|---|---|---|
| `$if` | `($if test then [else])` | evaluates `test`; tail-evaluates `then` or `else` (default `#inert`) |
| `$sequence` | `($sequence expr ...)` | evaluates each expr in order, tail-position for the last |
| `$quote` | `($quote datum)` | returns `datum` unevaluated (implementation convenience) |
| `$vau` | `($vau ptree eformal . body)` | makes an operative, see §3 |
| `$define!` | `($define! ptree expr)` | evaluates `expr`, binds it against `ptree` in the current environment |
| `$binds?` | `($binds? env-expr sym ...)` | `#t` if `env-expr`'s environment (or an ancestor) binds every `sym` |
| `$lazy` | `($lazy expr)` | makes a promise that evaluates `expr` in the current environment on first `force` |

Plus applicatives for control flow: `eval`, `apply`, `call/cc` /
`call-with-current-continuation`, and `error`.

## 6. Derived forms (prelude, written in Kernel itself, `kernel.builtins.Prelude`)

| Form | Syntax | Notes |
|---|---|---|
| `$lambda` | `($lambda formals . body)` | `(wrap ($vau formals #ignore . body))` – a function is an operative that evaluates its operands, with the calling environment discarded |
| `list` | `(list expr ...)` | applicative; returns the (evaluated) argument list |
| `list*` | `(list* head ... tail)` | like `list`, but the last argument is spliced in as the tail |
| `$let` | `($let ((name val) ...) . body)` | binds each `name` to `val`, evaluated in the *outer* environment, then runs `body` |
| `$let*` | `($let* ((name val) ...) . body)` | like `$let`, but each `val` sees the previous bindings |
| `$letrec` | `($letrec ((name val) ...) . body)` | all `name`s are in scope while every `val` is evaluated (mutual recursion) |
| `$letrec*` | `($letrec* ((name val) ...) . body)` | like `$letrec`, but bindings are evaluated in order, each seeing the earlier ones |
| `$cond` | `($cond (test expr ...) ...)` | first clause whose `test` is true has its body evaluated (as a `$sequence`); `#inert` if none match |
| `$when` | `($when test . body)` | evaluates `body` only if `test` is true |
| `$unless` | `($unless test . body)` | evaluates `body` only if `test` is false |
| `$and?` | `($and? expr ...)` | short-circuits on the first false value |
| `$or?` | `($or? expr ...)` | short-circuits on the first true value |
| `$set!` | `($set! env-expr ptree value-expr)` | evaluates `value-expr` in the *caller's* environment, defines it against `ptree` in the (already-evaluated) environment `env-expr` – used to mutate a captured environment |
| `for-each` | `(for-each fn list ...)` | like `map`, but discards the result and returns `#inert` |

## 7. Ground environment reference

Predicates accept any number of arguments and require all of them to satisfy
the test (e.g. `(zero? 0 0 0)` is `#t`); comparison operators (`<?`, `=?`, …)
are true when every adjacent pair satisfies the relation, Kernel-report
style: `(<? 1 2 3)` checks `1 < 2` and `2 < 3`.

- **Booleans** – `boolean?`, `not?`, `and?`, `or?` (applicative, non-short-circuiting – use `$and?`/`$or?` to short-circuit)
- **Equivalence** – `eq?`, `equal?`
- **Type predicates** – `symbol?`, `inert?`, `ignore?`, `eof-object?`, `null?`, `pair?`, `list?`, `environment?`, `operative?`, `applicative?`, `combiner?`, `number?`, `integer?`, `exact?`, `inexact?`, `string?`, `char?`, `promise?`
- **Pairs and lists** – `cons`, `set-car!`, `set-cdr!`, all `caar`…`cddddr` (every 2-, 3-, 4-deep combination of `a`/`d`), `length`, `append`, `reverse`, `list-tail`, `list-ref`, `map`, `filter`, `reduce`, `assoc`, `member?`
- **Combiners** – `$vau`, `wrap`, `unwrap`, `apply`
- **Environments** – `eval`, `make-environment`, `get-current-environment`, `make-kernel-standard-environment`, `$define!`, `$binds?`
- **Numbers** – `+`, `-`, `*`, `/`, `=?`, `<?`, `<=?`, `>?`, `>=?`, `zero?`, `positive?`, `negative?`, `odd?`, `even?`, `min`, `max`, `abs`, `div`, `mod`, `gcd`, `expt`, `sqrt`, `exact->inexact`, `inexact->exact`, `floor`, `ceiling`, `round`, `truncate`
- **Strings / symbols / characters** – `symbol->string`, `string->symbol`, `string-append`, `string-length`, `substring`, `string->number`, `number->string`, `char->integer`, `integer->char`
- **I/O** – `display`, `write`, `newline`, `load`
- **Promises** – `$lazy`, `force`, `memoize`
- **Encapsulation** – `make-encapsulation-type` (returns `(constructor predicate accessor)` for a fresh opaque type)
- **Continuations** – `call/cc` / `call-with-current-continuation` (escape-only, see §8)

## 8. Notes and deviations from the Kernel report (R⁻¹RK)

- **Continuations are escape-only.** `call/cc` implements early exit via a
  Java exception; the captured continuation cannot be re-entered after its
  extent has been left, and there is no `guard-dynamic-extent`.
- **No exact rationals.** `/` produces an inexact (double) result whenever
  the division doesn't come out even.
- **Cyclic lists are rejected**, not measured – there is no
  `get-list-metrics`.
- **`$quote` exists** (and `'x` sugar for it) purely for convenience, even
  though Shutt's report leaves it out on principle.
- **Proper tail calls are guaranteed.** `$if`, `$sequence`, `eval`, `apply`,
  and a `Vau`'s body all return through a trampoline (`TailCall`) instead of
  recursing, so tail-recursive Kernel code runs in constant Java stack.
  Non-tail calls still consume JVM stack; exhausting it is reported as a
  Kernel error rather than a raw `StackOverflowError`.
