package kernel.model;

public final class KernelError extends RuntimeException {
    public final Obj payload;
    public KernelError(String message) { this(message, null); }
    public KernelError(String message, Obj payload) {
        super(message);
        this.payload = payload == null ? new Str(message) : payload;
    }
}
