package kernel.model;

import kernel.model.Obj.Ch;
import kernel.model.Obj.Combiner;
import kernel.model.Obj.Const;
import kernel.model.Obj.Env;
import kernel.model.Obj.Int;
import kernel.model.Obj.Pair;
import kernel.model.Obj.Real;
import kernel.model.Obj.Str;
import kernel.model.Obj.Sym;
import kernel.printer.Printer;

import java.util.ArrayList;
import java.util.List;

/**
 * Free-standing operations on {@link Obj} values: list plumbing, argument
 * extraction, type coercions and the two flavors of equivalence.
 */
public final class Values {

    private Values() {}

    // ---- list construction and destructuring -------------------------------

    public static Pair cons(Obj a, Obj d) { return new Pair(a, d); }

    public static Obj list(Obj... items) {
        Obj result = Const.NIL;
        for (int i = items.length - 1; i >= 0; i--) result = cons(items[i], result);
        return result;
    }

    public static Obj fromJava(List<Obj> items) {
        Obj result = Const.NIL;
        for (int i = items.size() - 1; i >= 0; i--) result = cons(items.get(i), result);
        return result;
    }

    /** Flattens a proper list; Floyd's cycle detection keeps set-cdr! honest. */
    public static List<Obj> toJava(Obj o) {
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
        if (fast != Const.NIL) throw new KernelError("improper list: " + Printer.write(o));
        return out;
    }

    public static boolean isList(Obj o) {
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

    public static Obj car(Obj o) {
        if (o instanceof Pair p) return p.car;
        throw new KernelError("car: not a pair: " + Printer.write(o));
    }

    public static Obj cdr(Obj o) {
        if (o instanceof Pair p) return p.cdr;
        throw new KernelError("cdr: not a pair: " + Printer.write(o));
    }

    /** The i-th operand, with a decent error message when it is missing. */
    public static Obj arg(Obj operands, int i, String who) {
        Obj cur = operands;
        for (int n = 0; n < i; n++) {
            if (!(cur instanceof Pair p)) break;
            cur = p.cdr;
        }
        if (cur instanceof Pair p) return p.car;
        throw new KernelError(who + ": expected at least " + (i + 1) + " operand(s), got "
                + Printer.write(operands));
    }

    public static Obj argOr(Obj operands, int i, Obj fallback) {
        Obj cur = operands;
        for (int n = 0; n < i; n++) {
            if (!(cur instanceof Pair p)) return fallback;
            cur = p.cdr;
        }
        return cur instanceof Pair p ? p.car : fallback;
    }

    public static Obj bool(boolean b) { return b ? Const.TRUE : Const.FALSE; }

    public static boolean truthy(Obj o) {
        if (o == Const.TRUE) return true;
        if (o == Const.FALSE) return false;
        throw new KernelError("expected a boolean, got: " + Printer.write(o));
    }

    public static Env asEnv(Obj o) {
        if (o instanceof Env e) return e;
        throw new KernelError("expected an environment, got: " + Printer.write(o));
    }

    public static Combiner asCombiner(Obj o) {
        if (o instanceof Combiner c) return c;
        throw new KernelError("expected a combiner, got: " + Printer.write(o));
    }

    public static Pair asPair(Obj o) {
        if (o instanceof Pair p) return p;
        throw new KernelError("expected a pair, got: " + Printer.write(o));
    }

    public static Str asStr(Obj o) {
        if (o instanceof Str s) return s;
        throw new KernelError("expected a string, got: " + Printer.write(o));
    }

    // ---- equivalence ------------------------------------------------------

    /** {@code eq?}: identity, except immutable atoms compare by value. */
    public static boolean eqv(Obj a, Obj b) {
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
    public static boolean equalp(Obj a, Obj b) {
        if (eqv(a, b)) return true;
        if (a instanceof Pair x && b instanceof Pair y)
            return equalp(x.car, y.car) && equalp(x.cdr, y.cdr);
        if (a instanceof Str x && b instanceof Str y) return x.value().equals(y.value());
        return false;
    }
}
