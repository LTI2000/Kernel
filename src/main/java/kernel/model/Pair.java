package kernel.model;

/** Mutable cons cell: {@code set-car!} and {@code set-cdr!} are standard. */
public final class Pair implements Obj {
    public Obj car, cdr;
    public Pair(Obj car, Obj cdr) { this.car = car; this.cdr = cdr; }
}
