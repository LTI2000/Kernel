package kernel.model;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Every Kernel value. Sealed, so the evaluator's and printer's switches are checked. */
public sealed interface Obj {

    record Sym(String name) implements Obj {
        @Override public String toString() { return name; }
    }

    /** The self-evaluating unique constants. */
    enum Const implements Obj {
        NIL("()"), TRUE("#t"), FALSE("#f"),
        INERT("#inert"), IGNORE("#ignore"), EOF("#eof");
        private final String text;
        Const(String text) { this.text = text; }
        @Override public String toString() { return text; }
    }

    record Int(BigInteger value) implements Obj {
        public static Int of(long v) { return new Int(BigInteger.valueOf(v)); }
        @Override public String toString() { return value.toString(); }
    }

    record Real(double value) implements Obj {}

    record Str(String value) implements Obj {}

    record Ch(char value) implements Obj {}

    /** Mutable cons cell: {@code set-car!} and {@code set-cdr!} are standard. */
    final class Pair implements Obj {
        public Obj car, cdr;
        public Pair(Obj car, Obj cdr) { this.car = car; this.cdr = cdr; }
    }

    /** A first-class environment: a local frame plus an ordered parent list. */
    final class Env implements Obj {
        private final Map<Sym, Obj> frame = new HashMap<>();
        private final List<Env> parents;

        public Env(Env... parents) { this.parents = List.of(parents); }
        public Env(List<Env> parents) { this.parents = List.copyOf(parents); }

        public Optional<Obj> tryLookup(Sym s) {
            Obj local = frame.get(s);
            if (local != null) return Optional.of(local);
            for (Env parent : parents) {
                Optional<Obj> found = parent.tryLookup(s);
                if (found.isPresent()) return found;
            }
            return Optional.empty();
        }

        public Obj lookup(Sym s) {
            return tryLookup(s).orElseThrow(
                    () -> new KernelError("unbound symbol: " + s.name()));
        }

        public void define(Sym s, Obj value) { frame.put(s, value); }
    }

    sealed interface Combiner extends Obj {

        /** Evaluates its operands, then combines them with {@code underlying}. */
        record Applicative(Combiner underlying) implements Combiner {}

        sealed interface Operative extends Combiner {

            record Prim(String name, PrimFn fn) implements Operative {}

            /** A compound operative: the result of evaluating {@code ($vau ...)}. */
            final class Vau implements Operative {
                public final Obj ptree, eformal, body;
                public final Env staticEnv;
                public String name = "anonymous";
                public Vau(Obj ptree, Obj eformal, Obj body, Env staticEnv) {
                    this.ptree = ptree; this.eformal = eformal;
                    this.body = body; this.staticEnv = staticEnv;
                }
            }
        }
    }

    final class Promise implements Obj {
        public sealed interface State {}
        public record Unforced(Obj expr, Env env) implements State {}
        public record Forced(Obj value) implements State {}

        public State state;

        public Promise(Obj expr, Env env) { this.state = new Unforced(expr, env); }
        private Promise(State state) { this.state = state; }

        public static Promise of(Obj value) { return new Promise(new Forced(value)); }
    }

    /** An opaque value produced by {@code make-encapsulation-type}. */
    final class Encapsulation implements Obj {
        public final Object type; public final Obj value;
        public Encapsulation(Object type, Obj value) { this.type = type; this.value = value; }
    }

    /**
     * Internal trampoline token. A primitive returns this instead of calling
     * {@code eval} recursively, which is how tail calls stay flat.
     * It never escapes into user code.
     */
    record TailCall(Obj expr, Env env) implements Obj {}
}
