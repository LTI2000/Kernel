package kernel.model;

/** Thrown to unwind to the {@code call/cc} that created the continuation. */
public final class ContinuationInvoked extends RuntimeException {
    public final Object tag;
    public final Obj value;
    public ContinuationInvoked(Object tag, Obj value) {
        super(null, null, false, false);
        this.tag = tag; this.value = value;
    }
}
