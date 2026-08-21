import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;

/**
 * An interpreter for Kernel, John Shutt's Scheme dialect (R^-1RK) in which
 * fexprs ({@code $vau}) and first-class environments replace macros and
 * special forms entirely.
 *
 * <p>Run it:
 * <pre>
 *   java Kernel.java              # REPL
 *   java Kernel.java demo.krn     # run a file
 *   java Kernel.java -e '(+ 1 2)' # evaluate one expression
 * </pre>
 *
 * <p>Design notes:
 * <ul>
 *   <li>The value domain is a {@code sealed interface} hierarchy; the
 *       evaluator and printer use pattern matching over it.</li>
 *   <li>Proper tail calls are guaranteed by a trampoline: primitives that
 *       tail-call (like {@code $if}, {@code eval}, {@code apply}) return a
 *       {@link TailCall} token that the {@link #eval} loop unwinds.</li>
 *   <li>Only a small core is written in Java. The derived operatives
 *       ({@code $lambda}, {@code $let}, {@code $cond}, ...) are bootstrapped
 *       in Kernel itself, from the {@link #PRELUDE} source.</li>
 * </ul>
 */
public final class Kernel {

    private Kernel() {}

    // =====================================================================
    // 1. Object model
    // =====================================================================

    /** Every Kernel value. Sealed, so the evaluator's switches are checked. */
    public sealed interface Obj {}

    public record Sym(String name) implements Obj {
        @Override public String toString() { return name; }
    }

    /** The self-evaluating unique constants. */
    public enum Const implements Obj {
        NIL("()"), TRUE("#t"), FALSE("#f"),
        INERT("#inert"), IGNORE("#ignore"), EOF("#eof");
        private final String text;
        Const(String text) { this.text = text; }
        @Override public String toString() { return text; }
    }

    public record Int(BigInteger value) implements Obj {
        static Int of(long v) { return new Int(BigInteger.valueOf(v)); }
        @Override public String toString() { return value.toString(); }
    }

    public record Real(double value) implements Obj {}
    public record Str(String value) implements Obj {}
    public record Ch(char value) implements Obj {}

    /** Mutable cons cell: {@code set-car!} and {@code set-cdr!} are standard. */
    public static final class Pair implements Obj {
        Obj car, cdr;
        Pair(Obj car, Obj cdr) { this.car = car; this.cdr = cdr; }
    }

    /** A first-class environment: a local frame plus an ordered parent list. */
    public static final class Env implements Obj {
        private final Map<Sym, Obj> frame = new HashMap<>();
        private final List<Env> parents;

        Env(Env... parents) { this.parents = List.of(parents); }
        Env(List<Env> parents) { this.parents = List.copyOf(parents); }

        Optional<Obj> tryLookup(Sym s) {
            Obj local = frame.get(s);
            if (local != null) return Optional.of(local);
            for (Env parent : parents) {
                Optional<Obj> found = parent.tryLookup(s);
                if (found.isPresent()) return found;
            }
            return Optional.empty();
        }

        Obj lookup(Sym s) {
            return tryLookup(s).orElseThrow(
                    () -> new KernelError("unbound symbol: " + s.name()));
        }

        void define(Sym s, Obj value) { frame.put(s, value); }
    }

    public sealed interface Combiner extends Obj {}

    /** Evaluates its operands, then combines them with {@code underlying}. */
    public record Applicative(Combiner underlying) implements Combiner {}

    public sealed interface Operative extends Combiner {}

    @FunctionalInterface
    public interface PrimFn {
        /** @param operands unevaluated operand list; @param env the dynamic environment */
        Obj call(Obj operands, Env env);
    }

    public record Prim(String name, PrimFn fn) implements Operative {}

    /** A compound operative: the result of evaluating {@code ($vau ...)}. */
    public static final class Vau implements Operative {
        final Obj ptree, eformal, body;
        final Env staticEnv;
        String name = "anonymous";
        Vau(Obj ptree, Obj eformal, Obj body, Env staticEnv) {
            this.ptree = ptree; this.eformal = eformal;
            this.body = body; this.staticEnv = staticEnv;
        }
    }

    public static final class Promise implements Obj {
        Obj expr; Env env; Obj value; boolean forced;
        Promise(Obj expr, Env env) { this.expr = expr; this.env = env; }
        static Promise of(Obj value) {
            Promise p = new Promise(null, null);
            p.value = value; p.forced = true;
            return p;
        }
    }

    /** An opaque value produced by {@code make-encapsulation-type}. */
    public static final class Encapsulation implements Obj {
        final Object type; final Obj value;
        Encapsulation(Object type, Obj value) { this.type = type; this.value = value; }
    }

    /**
     * Internal trampoline token. A primitive returns this instead of calling
     * {@link #eval} recursively, which is how tail calls stay flat.
     * It never escapes into user code.
     */
    record TailCall(Obj expr, Env env) implements Obj {}

    // ---- errors and escapes ----------------------------------------------

    public static final class KernelError extends RuntimeException {
        final Obj payload;
        KernelError(String message) { this(message, null); }
        KernelError(String message, Obj payload) {
            super(message);
            this.payload = payload == null ? new Str(message) : payload;
        }
    }

    /** Thrown to unwind to the {@code call/cc} that created the continuation. */
    static final class ContinuationInvoked extends RuntimeException {
        final Object tag; final Obj value;
        ContinuationInvoked(Object tag, Obj value) {
            super(null, null, false, false);
            this.tag = tag; this.value = value;
        }
    }

    // =====================================================================
    // 2. List helpers
    // =====================================================================

    static Pair cons(Obj a, Obj d) { return new Pair(a, d); }

    static Obj list(Obj... items) {
        Obj result = Const.NIL;
        for (int i = items.length - 1; i >= 0; i--) result = cons(items[i], result);
        return result;
    }

    static Obj fromJava(List<Obj> items) {
        Obj result = Const.NIL;
        for (int i = items.size() - 1; i >= 0; i--) result = cons(items.get(i), result);
        return result;
    }

    /** Flattens a proper list; Floyd's cycle detection keeps set-cdr! honest. */
    static List<Obj> toJava(Obj o) {
        List<Obj> out = new ArrayList<>();
        Obj slow = o, fast = o;
        while (fast instanceof Pair p) {
            out.add(p.car);
            fast = p.cdr;
            if (!(fast instanceof Pair q)) break;
            out.add(q.car);
            fast = q.cdr;
            slow = ((Pair) slow).cdr;
            if (fast == slow) throw new KernelError("cyclic list");
        }
        if (fast != Const.NIL) throw new KernelError("improper list: " + write(o));
        return out;
    }

    static boolean isList(Obj o) {
        Obj slow = o, fast = o;
        while (fast instanceof Pair p) {
            fast = p.cdr;
            if (!(fast instanceof Pair q)) break;
            fast = q.cdr;
            slow = ((Pair) slow).cdr;
            if (fast == slow) return false;
        }
        return fast == Const.NIL;
    }

    static Obj car(Obj o) {
        if (o instanceof Pair p) return p.car;
        throw new KernelError("car: not a pair: " + write(o));
    }

    static Obj cdr(Obj o) {
        if (o instanceof Pair p) return p.cdr;
        throw new KernelError("cdr: not a pair: " + write(o));
    }

    /** The i-th operand, with a decent error message when it is missing. */
    static Obj arg(Obj operands, int i, String who) {
        Obj cur = operands;
        for (int n = 0; n < i; n++) {
            if (!(cur instanceof Pair p)) break;
            cur = p.cdr;
        }
        if (cur instanceof Pair p) return p.car;
        throw new KernelError(who + ": expected at least " + (i + 1) + " operand(s), got "
                + write(operands));
    }

    static Obj argOr(Obj operands, int i, Obj fallback) {
        Obj cur = operands;
        for (int n = 0; n < i; n++) {
            if (!(cur instanceof Pair p)) return fallback;
            cur = p.cdr;
        }
        return cur instanceof Pair p ? p.car : fallback;
    }

    static Obj bool(boolean b) { return b ? Const.TRUE : Const.FALSE; }

    static boolean truthy(Obj o) {
        if (o == Const.TRUE) return true;
        if (o == Const.FALSE) return false;
        throw new KernelError("expected a boolean, got: " + write(o));
    }

    static Env asEnv(Obj o) {
        if (o instanceof Env e) return e;
        throw new KernelError("expected an environment, got: " + write(o));
    }

    static Combiner asCombiner(Obj o) {
        if (o instanceof Combiner c) return c;
        throw new KernelError("expected a combiner, got: " + write(o));
    }

    static Pair asPair(Obj o) {
        if (o instanceof Pair p) return p;
        throw new KernelError("expected a pair, got: " + write(o));
    }

    static Str asStr(Obj o) {
        if (o instanceof Str s) return s;
        throw new KernelError("expected a string, got: " + write(o));
    }

    // ---- equivalence ------------------------------------------------------

    /** {@code eq?}: identity, except immutable atoms compare by value. */
    static boolean eqv(Obj a, Obj b) {
        if (a == b) return true;
        return switch (a) {
            case Sym s -> a.equals(b);
            case Int i -> a.equals(b);
            case Real r -> a.equals(b);
            case Ch c -> a.equals(b);
            default -> false;   // pairs, strings, environments, combiners: identity
        };
    }

    /** {@code equal?}: structural equivalence. */
    static boolean equalp(Obj a, Obj b) {
        if (eqv(a, b)) return true;
        if (a instanceof Pair x && b instanceof Pair y)
            return equalp(x.car, y.car) && equalp(x.cdr, y.cdr);
        if (a instanceof Str x && b instanceof Str y) return x.value().equals(y.value());
        return false;
    }

    // =====================================================================
    // 3. Printer
    // =====================================================================

    private static final int PRINT_LIMIT = 1000;   // guards against cyclic structure

    public static String write(Obj o) {
        StringBuilder sb = new StringBuilder();
        print(o, sb, true);
        return sb.toString();
    }

    public static String display(Obj o) {
        StringBuilder sb = new StringBuilder();
        print(o, sb, false);
        return sb.toString();
    }

    private static void print(Obj o, StringBuilder sb, boolean readably) {
        switch (o) {
            case Str s -> {
                if (!readably) { sb.append(s.value()); return; }
                sb.append('"');
                for (char c : s.value().toCharArray()) {
                    switch (c) {
                        case '"' -> sb.append("\\\"");
                        case '\\' -> sb.append("\\\\");
                        case '\n' -> sb.append("\\n");
                        case '\t' -> sb.append("\\t");
                        case '\r' -> sb.append("\\r");
                        default -> sb.append(c);
                    }
                }
                sb.append('"');
            }
            case Ch c -> sb.append(readably ? charName(c.value()) : String.valueOf(c.value()));
            case Real r -> sb.append(formatReal(r.value()));
            case Pair p -> {
                sb.append('(');
                Obj cur = p;
                int count = 0;
                while (cur instanceof Pair q) {
                    if (count > 0) sb.append(' ');
                    if (++count > PRINT_LIMIT) { sb.append("..."); cur = Const.NIL; break; }
                    print(q.car, sb, readably);
                    cur = q.cdr;
                }
                if (cur != Const.NIL) { sb.append(" . "); print(cur, sb, readably); }
                sb.append(')');
            }
            case Applicative a -> sb.append("#[applicative ").append(combinerName(a.underlying())).append(']');
            case Prim p -> sb.append("#[operative ").append(p.name()).append(']');
            case Vau v -> sb.append("#[operative ").append(v.name).append(']');
            case Env e -> sb.append("#[environment]");
            case Promise p -> sb.append("#[promise").append(p.forced ? " forced" : "").append(']');
            case Encapsulation e -> sb.append("#[encapsulation]");
            default -> sb.append(o.toString());
        }
    }

    private static String combinerName(Combiner c) {
        return switch (c) {
            case Prim p -> p.name();
            case Vau v -> v.name;
            case Applicative a -> combinerName(a.underlying());
        };
    }

    private static String formatReal(double d) {
        if (Double.isNaN(d)) return "+nan.0";
        if (d == Double.POSITIVE_INFINITY) return "+inf.0";
        if (d == Double.NEGATIVE_INFINITY) return "-inf.0";
        return Double.toString(d);
    }

    private static String charName(char c) {
        return switch (c) {
            case ' ' -> "#\\space";
            case '\n' -> "#\\newline";
            case '\t' -> "#\\tab";
            case '\r' -> "#\\return";
            case '\0' -> "#\\null";
            default -> "#\\" + c;
        };
    }

    // =====================================================================
    // 4. Reader
    // =====================================================================

    public static final class Reader implements Closeable {
        private final PushbackReader in;

        public Reader(java.io.Reader source) { this.in = new PushbackReader(source, 4); }
        public Reader(String source) { this(new StringReader(source)); }

        @Override public void close() throws IOException { in.close(); }

        private int peek() {
            try {
                int c = in.read();
                if (c != -1) in.unread(c);
                return c;
            } catch (IOException e) { throw new KernelError("read error: " + e.getMessage()); }
        }

        private int next() {
            try { return in.read(); }
            catch (IOException e) { throw new KernelError("read error: " + e.getMessage()); }
        }

        private static boolean isDelimiter(int c) {
            return c == -1 || Character.isWhitespace(c) || c == '(' || c == ')'
                    || c == '[' || c == ']' || c == '"' || c == ';' || c == '\'';
        }

        private void skipAtmosphere() {
            while (true) {
                int c = peek();
                if (c == -1) return;
                if (Character.isWhitespace(c)) { next(); continue; }
                if (c == ';') { while (true) { int d = next(); if (d == -1 || d == '\n') break; } continue; }
                if (c == '#') {
                    next();
                    int d = peek();
                    if (d == '|') { next(); skipBlockComment(); continue; }
                    if (d == ';') { next(); read(); continue; }   // datum comment
                    try { in.unread('#'); } catch (IOException e) { throw new KernelError("read error"); }
                    return;
                }
                return;
            }
        }

        private void skipBlockComment() {
            int depth = 1;
            while (depth > 0) {
                int c = next();
                if (c == -1) throw new KernelError("unterminated block comment");
                if (c == '#' && peek() == '|') { next(); depth++; }
                else if (c == '|' && peek() == '#') { next(); depth--; }
            }
        }

        /** @return the next datum, or {@code null} at end of input. */
        public Obj read() {
            skipAtmosphere();
            int c = next();
            if (c == -1) return null;
            switch (c) {
                case '(': case '[': return readListTail(c == '(' ? ')' : ']');
                case ')': case ']': throw new KernelError("unexpected '" + (char) c + "'");
                case '\'': {
                    Obj quoted = read();
                    if (quoted == null) throw new KernelError("unexpected EOF after quote");
                    return list(new Sym("$quote"), quoted);
                }
                case '"': return readString();
                case '#': return readHash();
                default: {
                    StringBuilder sb = new StringBuilder().append((char) c);
                    while (!isDelimiter(peek())) sb.append((char) next());
                    return atom(sb.toString());
                }
            }
        }

        private Obj readListTail(char closing) {
            List<Obj> items = new ArrayList<>();
            Obj tail = Const.NIL;
            while (true) {
                skipAtmosphere();
                int c = peek();
                if (c == -1) throw new KernelError("unterminated list");
                if (c == ')' || c == ']') {
                    next();
                    if (c != closing) throw new KernelError("mismatched '" + (char) c + "'");
                    break;
                }
                if (c == '.') {
                    next();
                    if (isDelimiter(peek())) {          // dotted tail
                        Obj rest = read();
                        if (rest == null) throw new KernelError("unexpected EOF after '.'");
                        tail = rest;
                        skipAtmosphere();
                        int close = next();
                        if (close != closing) throw new KernelError("expected '" + closing + "'");
                        break;
                    }
                    StringBuilder sb = new StringBuilder(".");
                    while (!isDelimiter(peek())) sb.append((char) next());
                    items.add(atom(sb.toString()));
                    continue;
                }
                Obj item = read();
                if (item == null) throw new KernelError("unterminated list");
                items.add(item);
            }
            Obj result = tail;
            for (int i = items.size() - 1; i >= 0; i--) result = cons(items.get(i), result);
            return result;
        }

        private Obj readString() {
            StringBuilder sb = new StringBuilder();
            while (true) {
                int c = next();
                if (c == -1) throw new KernelError("unterminated string");
                if (c == '"') break;
                if (c == '\\') {
                    int e = next();
                    sb.append(switch (e) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        case 'r' -> '\r';
                        case '0' -> '\0';
                        case -1 -> throw new KernelError("unterminated string");
                        default -> (char) e;
                    });
                } else sb.append((char) c);
            }
            return new Str(sb.toString());
        }

        private Obj readHash() {
            int c = next();
            if (c == '\\') {                        // character literal
                StringBuilder sb = new StringBuilder();
                sb.append((char) next());
                while (!isDelimiter(peek())) sb.append((char) next());
                String name = sb.toString();
                if (name.length() == 1) return new Ch(name.charAt(0));
                return new Ch(switch (name.toLowerCase(Locale.ROOT)) {
                    case "space" -> ' ';
                    case "newline", "linefeed" -> '\n';
                    case "tab" -> '\t';
                    case "return" -> '\r';
                    case "null", "nul" -> '\0';
                    default -> throw new KernelError("unknown character name: #\\" + name);
                });
            }
            StringBuilder sb = new StringBuilder().append((char) c);
            while (!isDelimiter(peek())) sb.append((char) next());
            String token = sb.toString();
            return switch (token.toLowerCase(Locale.ROOT)) {
                case "t", "true" -> Const.TRUE;
                case "f", "false" -> Const.FALSE;
                case "inert" -> Const.INERT;
                case "ignore" -> Const.IGNORE;
                case "eof" -> Const.EOF;
                default -> throw new KernelError("unknown '#' syntax: #" + token);
            };
        }

        private static Obj atom(String token) {
            if (token.isEmpty()) throw new KernelError("empty token");
            char c0 = token.charAt(0);
            boolean numeric = Character.isDigit(c0)
                    || ((c0 == '-' || c0 == '+' || c0 == '.') && token.length() > 1);
            if (numeric) {
                try { return new Int(new BigInteger(token)); } catch (NumberFormatException ignored) { }
                try { return new Real(Double.parseDouble(token)); } catch (NumberFormatException ignored) { }
            }
            return new Sym(token);
        }
    }

    // =====================================================================
    // 5. Evaluator
    // =====================================================================

    /**
     * The whole language, in one loop.
     *
     * <p>Symbols look up; non-pairs are self-evaluating; a pair evaluates its
     * car to a combiner and combines. Applicatives evaluate their operands and
     * re-dispatch on the underlying combiner; operatives receive the operands
     * as they were written, plus the dynamic environment. The loop, rather
     * than recursion, is what makes tail calls proper.
     */
    public static Obj eval(Obj expr, Env env) {
        while (true) {
            if (expr instanceof Sym s) return env.lookup(s);
            if (!(expr instanceof Pair p)) return expr;

            Obj combiner = eval(p.car, env);
            Obj operands = p.cdr;

            while (combiner instanceof Applicative a) {
                operands = evalOperands(operands, env);
                combiner = a.underlying();
            }

            if (combiner instanceof Prim prim) {
                Obj result = prim.fn().call(operands, env);
                if (result instanceof TailCall tc) { expr = tc.expr(); env = tc.env(); continue; }
                return result;
            }
            if (combiner instanceof Vau vau) {
                Env local = new Env(vau.staticEnv);
                bind(vau.ptree, operands, local);
                if (vau.eformal instanceof Sym s) local.define(s, env);
                Obj body = vau.body;
                if (body == Const.NIL) return Const.INERT;
                while (cdr(body) != Const.NIL) { eval(car(body), local); body = cdr(body); }
                expr = car(body);
                env = local;
                continue;
            }
            throw new KernelError("not a combiner: " + write(combiner));
        }
    }

    private static Obj evalOperands(Obj operands, Env env) {
        if (operands == Const.NIL) return Const.NIL;
        if (!(operands instanceof Pair first))
            throw new KernelError("operand list is improper: " + write(operands));
        Pair head = cons(eval(first.car, env), Const.NIL);
        Pair last = head;
        Obj rest = first.cdr;
        while (rest instanceof Pair q) {
            Pair cell = cons(eval(q.car, env), Const.NIL);
            last.cdr = cell;
            last = cell;
            rest = q.cdr;
        }
        if (rest != Const.NIL) throw new KernelError("operand list is improper: " + write(operands));
        return head;
    }

    /** Destructures a parameter tree against an argument object. */
    static void bind(Obj ptree, Obj value, Env env) {
        if (ptree instanceof Sym s) { env.define(s, value); return; }
        if (ptree == Const.IGNORE) return;
        if (ptree == Const.NIL) {
            if (value != Const.NIL)
                throw new KernelError("too many arguments: expected (), got " + write(value));
            return;
        }
        if (ptree instanceof Pair pt) {
            if (!(value instanceof Pair pv))
                throw new KernelError("argument mismatch: " + write(ptree) + " vs " + write(value));
            bind(pt.car, pv.car, env);
            bind(pt.cdr, pv.cdr, env);
            return;
        }
        throw new KernelError("bad parameter tree: " + write(ptree));
    }

    /** Evaluates all but the last expression, then tail-calls the last. */
    static Obj sequence(Obj body, Env env) {
        if (body == Const.NIL) return Const.INERT;
        while (cdr(body) != Const.NIL) { eval(car(body), env); body = cdr(body); }
        return new TailCall(car(body), env);
    }

    /** Combines a value with an already-evaluated argument list. */
    static Obj combine(Obj combiner, Obj args, Env env) {
        Combiner c = asCombiner(combiner);
        Obj target = c instanceof Applicative a ? a.underlying() : c;
        return eval(cons(target, args), env);
    }

    // =====================================================================
    // 6. Ground environment
    // =====================================================================

    static void op(Env e, String name, PrimFn fn) {
        e.define(new Sym(name), new Prim(name, fn));
    }

    static void ap(Env e, String name, PrimFn fn) {
        e.define(new Sym(name), new Applicative(new Prim(name, fn)));
    }

    /** Kernel type predicates accept any number of arguments. */
    static void pred(Env e, String name, Predicate<Obj> test) {
        ap(e, name, (args, env) -> {
            for (Obj a : toJava(args)) if (!test.test(a)) return Const.FALSE;
            return Const.TRUE;
        });
    }

    interface Rel { boolean test(Obj a, Obj b); }

    /** #t when every adjacent pair of arguments satisfies the relation. */
    static Obj chain(Obj args, Rel rel) {
        List<Obj> xs = toJava(args);
        for (int i = 0; i + 1 < xs.size(); i++)
            if (!rel.test(xs.get(i), xs.get(i + 1))) return Const.FALSE;
        return Const.TRUE;
    }

    public static final Env GROUND = makeGround();

    private static Env makeGround() {
        Env g = new Env();

        // ---- booleans ----------------------------------------------------
        pred(g, "boolean?", o -> o == Const.TRUE || o == Const.FALSE);
        ap(g, "not?", (a, e) -> bool(!truthy(arg(a, 0, "not?"))));
        ap(g, "and?", (a, e) -> { for (Obj x : toJava(a)) if (!truthy(x)) return Const.FALSE; return Const.TRUE; });
        ap(g, "or?", (a, e) -> { for (Obj x : toJava(a)) if (truthy(x)) return Const.TRUE; return Const.FALSE; });

        // ---- equivalence -------------------------------------------------
        ap(g, "eq?", (a, e) -> chain(a, Kernel::eqv));
        ap(g, "equal?", (a, e) -> chain(a, Kernel::equalp));

        // ---- type predicates ---------------------------------------------
        pred(g, "symbol?", o -> o instanceof Sym);
        pred(g, "inert?", o -> o == Const.INERT);
        pred(g, "ignore?", o -> o == Const.IGNORE);
        pred(g, "eof-object?", o -> o == Const.EOF);
        pred(g, "null?", o -> o == Const.NIL);
        pred(g, "pair?", o -> o instanceof Pair);
        pred(g, "list?", Kernel::isList);
        pred(g, "environment?", o -> o instanceof Env);
        pred(g, "operative?", o -> o instanceof Operative);
        pred(g, "applicative?", o -> o instanceof Applicative);
        pred(g, "combiner?", o -> o instanceof Combiner);
        pred(g, "number?", o -> o instanceof Int || o instanceof Real);
        pred(g, "integer?", o -> o instanceof Int);
        pred(g, "exact?", o -> o instanceof Int);
        pred(g, "inexact?", o -> o instanceof Real);
        pred(g, "string?", o -> o instanceof Str);
        pred(g, "char?", o -> o instanceof Ch);
        pred(g, "promise?", o -> o instanceof Promise);

        // ---- control -----------------------------------------------------
        op(g, "$if", (a, e) -> truthy(eval(arg(a, 0, "$if"), e))
                ? new TailCall(arg(a, 1, "$if"), e)
                : new TailCall(argOr(a, 2, Const.INERT), e));
        op(g, "$sequence", (a, e) -> sequence(a, e));
        op(g, "$quote", (a, e) -> arg(a, 0, "$quote"));      // convenience, not in R^-1RK
        ap(g, "error", (a, e) -> {
            List<Obj> parts = toJava(a);
            StringBuilder sb = new StringBuilder();
            for (Obj part : parts) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(display(part));
            }
            throw new KernelError(sb.toString(), a);
        });

        // Escape-only continuations: enough for early exit, generators and
        // exception-style control, but not re-entrant (no full call/cc).
        PrimFn callcc = (a, e) -> {
            Obj f = arg(a, 0, "call/cc");
            Object tag = new Object();
            Obj k = new Applicative(new Prim("continuation", (ka, ke) -> {
                throw new ContinuationInvoked(tag, argOr(ka, 0, Const.INERT));
            }));
            try {
                return combine(f, list(k), e);
            } catch (ContinuationInvoked ci) {
                if (ci.tag == tag) return ci.value;
                throw ci;
            }
        };
        ap(g, "call/cc", callcc);
        ap(g, "call-with-current-continuation", callcc);

        // ---- pairs and lists ---------------------------------------------
        ap(g, "cons", (a, e) -> cons(arg(a, 0, "cons"), arg(a, 1, "cons")));
        ap(g, "set-car!", (a, e) -> { asPair(arg(a, 0, "set-car!")).car = arg(a, 1, "set-car!"); return Const.INERT; });
        ap(g, "set-cdr!", (a, e) -> { asPair(arg(a, 0, "set-cdr!")).cdr = arg(a, 1, "set-cdr!"); return Const.INERT; });
        for (String path : accessorPaths()) {
            String name = "c" + path + "r";
            ap(g, name, (a, e) -> {
                Obj x = arg(a, 0, name);
                for (int i = path.length() - 1; i >= 0; i--)
                    x = path.charAt(i) == 'a' ? car(x) : cdr(x);
                return x;
            });
        }
        ap(g, "length", (a, e) -> Int.of(toJava(arg(a, 0, "length")).size()));
        ap(g, "append", (a, e) -> {
            List<Obj> lists = toJava(a);
            if (lists.isEmpty()) return Const.NIL;
            Obj result = lists.get(lists.size() - 1);
            for (int i = lists.size() - 2; i >= 0; i--) {
                List<Obj> items = toJava(lists.get(i));
                for (int j = items.size() - 1; j >= 0; j--) result = cons(items.get(j), result);
            }
            return result;
        });
        ap(g, "reverse", (a, e) -> {
            Obj result = Const.NIL;
            for (Obj x : toJava(arg(a, 0, "reverse"))) result = cons(x, result);
            return result;
        });
        ap(g, "list-tail", (a, e) -> {
            Obj x = arg(a, 0, "list-tail");
            for (BigInteger n = asInt(arg(a, 1, "list-tail")); n.signum() > 0; n = n.subtract(BigInteger.ONE))
                x = cdr(x);
            return x;
        });
        ap(g, "list-ref", (a, e) -> {
            Obj x = arg(a, 0, "list-ref");
            for (BigInteger n = asInt(arg(a, 1, "list-ref")); n.signum() > 0; n = n.subtract(BigInteger.ONE))
                x = cdr(x);
            return car(x);
        });
        ap(g, "map", (a, e) -> {
            List<Obj> as = toJava(a);
            if (as.size() < 2) throw new KernelError("map: expected an applicative and at least one list");
            List<List<Obj>> lists = new ArrayList<>();
            for (int i = 1; i < as.size(); i++) lists.add(toJava(as.get(i)));
            int n = lists.get(0).size();
            for (List<Obj> l : lists)
                if (l.size() != n) throw new KernelError("map: lists differ in length");
            List<Obj> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                List<Obj> callArgs = new ArrayList<>(lists.size());
                for (List<Obj> l : lists) callArgs.add(l.get(i));
                out.add(combine(as.get(0), fromJava(callArgs), e));
            }
            return fromJava(out);
        });
        ap(g, "filter", (a, e) -> {
            Obj f = arg(a, 0, "filter");
            List<Obj> out = new ArrayList<>();
            for (Obj x : toJava(arg(a, 1, "filter")))
                if (truthy(combine(f, list(x), e))) out.add(x);
            return fromJava(out);
        });
        ap(g, "reduce", (a, e) -> {                 // (reduce list binary identity)
            List<Obj> items = toJava(arg(a, 0, "reduce"));
            Obj binary = arg(a, 1, "reduce");
            if (items.isEmpty()) return arg(a, 2, "reduce");
            Obj acc = items.get(0);
            for (int i = 1; i < items.size(); i++) acc = combine(binary, list(acc, items.get(i)), e);
            return acc;
        });
        ap(g, "assoc", (a, e) -> {
            Obj key = arg(a, 0, "assoc");
            for (Obj entry : toJava(arg(a, 1, "assoc")))
                if (entry instanceof Pair p && equalp(key, p.car)) return p;
            return Const.NIL;
        });
        ap(g, "member?", (a, e) -> {
            Obj key = arg(a, 0, "member?");
            for (Obj x : toJava(arg(a, 1, "member?"))) if (equalp(key, x)) return Const.TRUE;
            return Const.FALSE;
        });

        // ---- combiners ---------------------------------------------------
        op(g, "$vau", (a, e) -> {
            Obj ptree = arg(a, 0, "$vau");
            Obj eformal = arg(a, 1, "$vau");
            if (!(eformal instanceof Sym) && eformal != Const.IGNORE)
                throw new KernelError("$vau: environment parameter must be a symbol or #ignore");
            return new Vau(ptree, eformal, cdr(cdr(a)), e);
        });
        ap(g, "wrap", (a, e) -> new Applicative(asCombiner(arg(a, 0, "wrap"))));
        ap(g, "unwrap", (a, e) -> {
            if (arg(a, 0, "unwrap") instanceof Applicative app) return app.underlying();
            throw new KernelError("unwrap: not an applicative: " + write(arg(a, 0, "unwrap")));
        });
        // (apply applicative arg-list [env]) == (eval (cons (unwrap applicative) arg-list) env)
        ap(g, "apply", (a, e) -> {
            Obj f = arg(a, 0, "apply");
            Obj args = argOr(a, 1, Const.NIL);
            Env env = argOr(a, 2, null) instanceof Env given ? given : new Env();
            if (!(f instanceof Applicative app))
                throw new KernelError("apply: not an applicative: " + write(f));
            return new TailCall(cons(app.underlying(), args), env);
        });

        // ---- environments --------------------------------------------------
        ap(g, "eval", (a, e) -> new TailCall(arg(a, 0, "eval"), asEnv(arg(a, 1, "eval"))));
        ap(g, "make-environment", (a, e) -> {
            List<Env> parents = new ArrayList<>();
            for (Obj p : toJava(a)) parents.add(asEnv(p));
            return new Env(parents);
        });
        ap(g, "get-current-environment", (a, e) -> e);
        ap(g, "make-kernel-standard-environment", (a, e) -> new Env(GROUND));
        op(g, "$define!", (a, e) -> {
            Obj ptree = arg(a, 0, "$define!");
            Obj value = eval(arg(a, 1, "$define!"), e);
            nameIfAnonymous(ptree, value);
            bind(ptree, value, e);
            return Const.INERT;
        });
        op(g, "$binds?", (a, e) -> {
            Env target = asEnv(eval(arg(a, 0, "$binds?"), e));
            for (Obj s : toJava(cdr(a))) {
                if (!(s instanceof Sym sym)) throw new KernelError("$binds?: expected a symbol");
                if (target.tryLookup(sym).isEmpty()) return Const.FALSE;
            }
            return Const.TRUE;
        });

        // ---- numbers -------------------------------------------------------
        ap(g, "+", (a, e) -> fold(a, Int.of(0), Kernel::add));
        ap(g, "*", (a, e) -> fold(a, Int.of(1), Kernel::mul));
        ap(g, "-", (a, e) -> {
            List<Obj> xs = toJava(a);
            if (xs.isEmpty()) throw new KernelError("-: expected at least one argument");
            if (xs.size() == 1) return sub(Int.of(0), xs.get(0));
            Obj acc = xs.get(0);
            for (int i = 1; i < xs.size(); i++) acc = sub(acc, xs.get(i));
            return acc;
        });
        ap(g, "/", (a, e) -> {
            List<Obj> xs = toJava(a);
            if (xs.isEmpty()) throw new KernelError("/: expected at least one argument");
            Obj acc = xs.size() == 1 ? Int.of(1) : xs.get(0);
            for (int i = xs.size() == 1 ? 0 : 1; i < xs.size(); i++) acc = divide(acc, xs.get(i));
            return acc;
        });
        ap(g, "=?", (a, e) -> chain(a, (x, y) -> compare(x, y) == 0));
        ap(g, "<?", (a, e) -> chain(a, (x, y) -> compare(x, y) < 0));
        ap(g, "<=?", (a, e) -> chain(a, (x, y) -> compare(x, y) <= 0));
        ap(g, ">?", (a, e) -> chain(a, (x, y) -> compare(x, y) > 0));
        ap(g, ">=?", (a, e) -> chain(a, (x, y) -> compare(x, y) >= 0));
        pred(g, "zero?", o -> compare(o, Int.of(0)) == 0);
        pred(g, "positive?", o -> compare(o, Int.of(0)) > 0);
        pred(g, "negative?", o -> compare(o, Int.of(0)) < 0);
        pred(g, "odd?", o -> asInt(o).testBit(0));
        pred(g, "even?", o -> !asInt(o).testBit(0));
        ap(g, "min", (a, e) -> extremum(a, "min", -1));
        ap(g, "max", (a, e) -> extremum(a, "max", 1));
        ap(g, "abs", (a, e) -> arg(a, 0, "abs") instanceof Int i
                ? new Int(i.value().abs()) : new Real(Math.abs(toDouble(arg(a, 0, "abs")))));
        ap(g, "div", (a, e) -> new Int(floorDiv(asInt(arg(a, 0, "div")), asInt(arg(a, 1, "div")))));
        ap(g, "mod", (a, e) -> {
            BigInteger n = asInt(arg(a, 0, "mod")), d = asInt(arg(a, 1, "mod"));
            return new Int(n.subtract(floorDiv(n, d).multiply(d)));
        });
        ap(g, "gcd", (a, e) -> {
            BigInteger acc = BigInteger.ZERO;
            for (Obj x : toJava(a)) acc = acc.gcd(asInt(x));
            return new Int(acc);
        });
        ap(g, "expt", (a, e) -> {
            Obj base = arg(a, 0, "expt"), power = arg(a, 1, "expt");
            if (base instanceof Int b && power instanceof Int p && p.value().signum() >= 0)
                return new Int(b.value().pow(p.value().intValueExact()));
            return new Real(Math.pow(toDouble(base), toDouble(power)));
        });
        ap(g, "sqrt", (a, e) -> new Real(Math.sqrt(toDouble(arg(a, 0, "sqrt")))));
        ap(g, "exact->inexact", (a, e) -> new Real(toDouble(arg(a, 0, "exact->inexact"))));
        ap(g, "inexact->exact", (a, e) -> arg(a, 0, "inexact->exact") instanceof Real r
                ? new Int(BigInteger.valueOf(Math.round(r.value()))) : arg(a, 0, "inexact->exact"));
        ap(g, "floor", (a, e) -> roundTo(arg(a, 0, "floor"), Math::floor));
        ap(g, "ceiling", (a, e) -> roundTo(arg(a, 0, "ceiling"), Math::ceil));
        ap(g, "round", (a, e) -> roundTo(arg(a, 0, "round"), Math::rint));
        ap(g, "truncate", (a, e) -> roundTo(arg(a, 0, "truncate"), d -> (double) (long) d));

        // ---- strings, symbols, characters -----------------------------------
        ap(g, "symbol->string", (a, e) -> arg(a, 0, "symbol->string") instanceof Sym s
                ? new Str(s.name()) : errorValue("symbol->string", arg(a, 0, "symbol->string")));
        ap(g, "string->symbol", (a, e) -> new Sym(asStr(arg(a, 0, "string->symbol")).value()));
        ap(g, "string-append", (a, e) -> {
            StringBuilder sb = new StringBuilder();
            for (Obj x : toJava(a)) sb.append(asStr(x).value());
            return new Str(sb.toString());
        });
        ap(g, "string-length", (a, e) -> Int.of(asStr(arg(a, 0, "string-length")).value().length()));
        ap(g, "substring", (a, e) -> new Str(asStr(arg(a, 0, "substring")).value().substring(
                asInt(arg(a, 1, "substring")).intValueExact(),
                asInt(arg(a, 2, "substring")).intValueExact())));
        ap(g, "string->number", (a, e) -> Reader.atom(asStr(arg(a, 0, "string->number")).value()));
        ap(g, "number->string", (a, e) -> new Str(write(arg(a, 0, "number->string"))));
        ap(g, "char->integer", (a, e) -> arg(a, 0, "char->integer") instanceof Ch c
                ? Int.of(c.value()) : errorValue("char->integer", arg(a, 0, "char->integer")));
        ap(g, "integer->char", (a, e) -> new Ch((char) asInt(arg(a, 0, "integer->char")).intValueExact()));

        // ---- input / output ---------------------------------------------------
        ap(g, "display", (a, e) -> { System.out.print(display(arg(a, 0, "display"))); return Const.INERT; });
        ap(g, "write", (a, e) -> { System.out.print(write(arg(a, 0, "write"))); return Const.INERT; });
        ap(g, "newline", (a, e) -> { System.out.println(); return Const.INERT; });
        ap(g, "load", (a, e) -> { loadFile(asStr(arg(a, 0, "load")).value(), e); return Const.INERT; });

        // ---- promises -----------------------------------------------------------
        op(g, "$lazy", (a, e) -> new Promise(arg(a, 0, "$lazy"), e));
        ap(g, "memoize", (a, e) -> Promise.of(arg(a, 0, "memoize")));
        ap(g, "force", (a, e) -> {
            Obj x = arg(a, 0, "force");
            if (!(x instanceof Promise p)) return x;
            while (!p.forced) {
                Obj v = eval(p.expr, p.env);
                if (p.forced) break;
                if (v instanceof Promise inner) {        // a promise yielding a promise
                    if (inner.forced) { p.value = inner.value; p.forced = true; break; }
                    p.expr = inner.expr;
                    p.env = inner.env;
                } else { p.value = v; p.forced = true; p.env = null; p.expr = null; }
            }
            return p.value;
        });

        // ---- encapsulated types --------------------------------------------------
        ap(g, "make-encapsulation-type", (a, e) -> {
            Object type = new Object();
            Obj encapsulate = new Applicative(new Prim("encapsulate",
                    (xa, xe) -> new Encapsulation(type, arg(xa, 0, "encapsulate"))));
            Obj predicate = new Applicative(new Prim("encapsulated?", (xa, xe) -> {
                for (Obj x : toJava(xa))
                    if (!(x instanceof Encapsulation enc) || enc.type != type) return Const.FALSE;
                return Const.TRUE;
            }));
            Obj decapsulate = new Applicative(new Prim("decapsulate", (xa, xe) -> {
                if (arg(xa, 0, "decapsulate") instanceof Encapsulation enc && enc.type == type)
                    return enc.value;
                throw new KernelError("decapsulate: wrong encapsulation type");
            }));
            return list(encapsulate, predicate, decapsulate);
        });

        evalString(PRELUDE, g);
        return g;
    }

    // ---- numeric helpers ------------------------------------------------------

    static BigInteger asInt(Obj o) {
        if (o instanceof Int i) return i.value();
        if (o instanceof Real r && r.value() == Math.rint(r.value()))
            return BigInteger.valueOf((long) r.value());
        throw new KernelError("expected an integer, got: " + write(o));
    }

    static double toDouble(Obj o) {
        return switch (o) {
            case Int i -> i.value().doubleValue();
            case Real r -> r.value();
            default -> throw new KernelError("expected a number, got: " + write(o));
        };
    }

    static Obj add(Obj a, Obj b) {
        return a instanceof Int x && b instanceof Int y
                ? new Int(x.value().add(y.value())) : new Real(toDouble(a) + toDouble(b));
    }

    static Obj sub(Obj a, Obj b) {
        return a instanceof Int x && b instanceof Int y
                ? new Int(x.value().subtract(y.value())) : new Real(toDouble(a) - toDouble(b));
    }

    static Obj mul(Obj a, Obj b) {
        return a instanceof Int x && b instanceof Int y
                ? new Int(x.value().multiply(y.value())) : new Real(toDouble(a) * toDouble(b));
    }

    static Obj divide(Obj a, Obj b) {
        if (a instanceof Int x && b instanceof Int y) {
            if (y.value().signum() == 0) throw new KernelError("/: division by zero");
            BigInteger[] qr = x.value().divideAndRemainder(y.value());
            if (qr[1].signum() == 0) return new Int(qr[0]);     // stay exact when we can
            return new Real(x.value().doubleValue() / y.value().doubleValue());
        }
        return new Real(toDouble(a) / toDouble(b));
    }

    static int compare(Obj a, Obj b) {
        if (a instanceof Int x && b instanceof Int y) return x.value().compareTo(y.value());
        return Double.compare(toDouble(a), toDouble(b));
    }

    static BigInteger floorDiv(BigInteger n, BigInteger d) {
        if (d.signum() == 0) throw new KernelError("div: division by zero");
        BigInteger[] qr = n.divideAndRemainder(d);
        if (qr[1].signum() != 0 && qr[1].signum() != d.signum())
            return qr[0].subtract(BigInteger.ONE);
        return qr[0];
    }

    interface DoubleRounder { double round(double d); }

    static Obj roundTo(Obj x, DoubleRounder f) {
        return x instanceof Int ? x : new Real(f.round(toDouble(x)));
    }

    interface BinOp { Obj apply(Obj a, Obj b); }

    static Obj fold(Obj args, Obj identity, BinOp op) {
        Obj acc = identity;
        for (Obj x : toJava(args)) acc = op.apply(acc, x);
        return acc;
    }

    static Obj extremum(Obj args, String who, int sign) {
        List<Obj> xs = toJava(args);
        if (xs.isEmpty()) throw new KernelError(who + ": expected at least one argument");
        Obj best = xs.get(0);
        for (Obj x : xs) if (Integer.signum(compare(x, best)) == sign) best = x;
        return best;
    }

    static Obj errorValue(String who, Obj got) {
        throw new KernelError(who + ": bad argument: " + write(got));
    }

    /** All of caar ... cddddr. */
    static List<String> accessorPaths() {
        List<String> level = List.of("a", "d");
        List<String> paths = new ArrayList<>(level);
        List<String> current = new ArrayList<>(level);
        for (int depth = 2; depth <= 4; depth++) {
            List<String> next = new ArrayList<>();
            for (String p : current) for (String c : level) next.add(p + c);
            paths.addAll(next);
            current = next;
        }
        return paths;
    }

    /** Gives {@code ($define! foo ($lambda ...))} a printable name. */
    static void nameIfAnonymous(Obj ptree, Obj value) {
        if (!(ptree instanceof Sym s)) return;
        Combiner c = value instanceof Combiner k ? k : null;
        while (c instanceof Applicative a) c = a.underlying();
        if (c instanceof Vau v && v.name.equals("anonymous")) v.name = s.name();
    }

    // =====================================================================
    // 7. The prelude: derived operatives, written in Kernel itself
    // =====================================================================

    /**
     * Everything here could have been written in Java, and in a Lisp with
     * macros most of it would have to be built into the expander. In Kernel
     * these are ordinary values, derived from $vau, wrap and eval.
     */
    static final String PRELUDE = """
            ; ($lambda formals . body) == (wrap ($vau formals #ignore . body))
            ($define! $lambda
              ($vau (formals . body) env
                (wrap (eval (cons $vau (cons formals (cons #ignore body))) env))))

            ; An applicative that returns its (already evaluated) argument list.
            ($define! list (wrap ($vau args #ignore args)))

            ($define! list*
              ($lambda (head . tail)
                ($if (null? tail)
                     head
                     (cons head (apply list* tail)))))

            ($define! $let
              ($vau (bindings . body) env
                (eval (cons (list* $lambda (map car bindings) body)
                            (map cadr bindings))
                      env)))

            ($define! $cond
              ($vau clauses env
                ($if (null? clauses)
                     #inert
                     ($let ((clause (car clauses)))
                       ($if (eval (car clause) env)
                            (eval (cons $sequence (cdr clause)) env)
                            (eval (cons $cond (cdr clauses)) env))))))

            ($define! $when
              ($vau (test . body) env
                ($if (eval test env) (eval (cons $sequence body) env) #inert)))

            ($define! $unless
              ($vau (test . body) env
                ($if (eval test env) #inert (eval (cons $sequence body) env))))

            ($define! $and?
              ($vau clauses env
                ($cond ((null? clauses)       #t)
                       ((null? (cdr clauses)) (eval (car clauses) env))
                       ((eval (car clauses) env) (eval (cons $and? (cdr clauses)) env))
                       (#t                    #f))))

            ($define! $or?
              ($vau clauses env
                ($cond ((null? clauses)       #f)
                       ((null? (cdr clauses)) (eval (car clauses) env))
                       ((eval (car clauses) env) #t)
                       (#t                    (eval (cons $or? (cdr clauses)) env)))))

            ($define! $let*
              ($vau (bindings . body) env
                (eval ($if (null? bindings)
                           (list* $let bindings body)
                           (list $let
                                 (list (car bindings))
                                 (list* $let* (cdr bindings) body)))
                      env)))

            ($define! $letrec
              ($vau (bindings . body) env
                (eval (list* $let ()
                             (list $define!
                                   (map car bindings)
                                   (cons list (map cadr bindings)))
                             body)
                      env)))

            ($define! $letrec*
              ($vau (bindings . body) env
                (eval ($if (null? bindings)
                           (list* $letrec bindings body)
                           (list $letrec
                                 (list (car bindings))
                                 (list* $letrec* (cdr bindings) body)))
                      env)))

            ; ($set! env-expr ptree value-expr): define in a remote environment.
            ($define! $set!
              ($vau (env-expr ptree value-expr) env
                (eval (list $define! ptree (list (unwrap eval) value-expr env))
                      (eval env-expr env))))

            ($define! for-each ($lambda args (apply map args) #inert))
            """;

    // =====================================================================
    // 8. Loading, REPL, entry point
    // =====================================================================

    public static Obj evalString(String source, Env env) {
        Obj last = Const.INERT;
        try (Reader reader = new Reader(source)) {
            Obj form;
            while ((form = reader.read()) != null) last = eval(form, env);
        } catch (IOException e) {
            throw new KernelError("i/o error: " + e.getMessage());
        }
        return last;
    }

    public static Obj loadFile(String path, Env env) {
        try (java.io.Reader in = new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8);
             Reader reader = new Reader(in)) {
            Obj last = Const.INERT;
            Obj form;
            while ((form = reader.read()) != null) last = eval(form, env);
            return last;
        } catch (FileNotFoundException e) {
            throw new KernelError("cannot open file: " + path);
        } catch (IOException e) {
            throw new KernelError("i/o error: " + e.getMessage());
        }
    }

    static void repl(Env env) {
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
                if (value != Const.INERT) System.out.println(write(value));
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

    private static void run(String[] args) {
        Env env = new Env(GROUND);
        boolean startRepl = args.length == 0;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "-e", "--eval" -> {
                        if (++i == args.length) throw new KernelError("-e needs an expression");
                        Obj value = evalString(args[i], env);
                        if (value != Const.INERT) System.out.println(write(value));
                    }
                    case "-i", "--repl" -> startRepl = true;
                    case "-h", "--help" -> {
                        System.out.println("usage: java Kernel.java [-e EXPR] [-i] [FILE ...]");
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
        if (startRepl) repl(env);
    }

    public static void main(String[] args) throws InterruptedException {
        // Non-tail Kernel recursion consumes Java stack, so give it room --
        // but not so much that a runaway recursion exhausts the heap instead.
        Thread main = new Thread(null, () -> run(args), "kernel", 64L * 1024 * 1024);
        main.start();
        main.join();
    }
}
