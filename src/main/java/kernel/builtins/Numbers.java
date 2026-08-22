package kernel.builtins;

import kernel.model.Int;
import kernel.model.KernelError;
import kernel.model.Obj;
import kernel.model.Real;
import kernel.printer.Printer;

import java.math.BigInteger;
import java.util.List;

import static kernel.model.Values.toJava;

/** Numeric coercions and arithmetic shared by the ground environment's number primitives. */
final class Numbers {

    private Numbers() {}

    static BigInteger asInt(Obj o) {
        if (o instanceof Int i) return i.value();
        if (o instanceof Real r && r.value() == Math.rint(r.value()))
            return BigInteger.valueOf((long) r.value());
        throw new KernelError("expected an integer, got: " + Printer.write(o));
    }

    static double toDouble(Obj o) {
        return switch (o) {
            case Int i -> i.value().doubleValue();
            case Real r -> r.value();
            default -> throw new KernelError("expected a number, got: " + Printer.write(o));
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
        throw new KernelError(who + ": bad argument: " + Printer.write(got));
    }
}
