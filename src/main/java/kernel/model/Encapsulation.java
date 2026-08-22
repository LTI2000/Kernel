package kernel.model;

/** An opaque value produced by {@code make-encapsulation-type}. */
public final class Encapsulation implements Obj {
    public final Object type; public final Obj value;
    public Encapsulation(Object type, Obj value) { this.type = type; this.value = value; }
}
