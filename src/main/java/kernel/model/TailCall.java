package kernel.model;

/**
 * Internal trampoline token. A primitive returns this instead of calling
 * {@code eval} recursively, which is how tail calls stay flat.
 * It never escapes into user code.
 */
public record TailCall(Obj expr, Env env) implements Obj {}
