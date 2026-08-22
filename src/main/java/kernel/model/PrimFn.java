package kernel.model;

@FunctionalInterface
public interface PrimFn {
    /** @param operands unevaluated operand list; @param env the dynamic environment */
    Obj call(Obj operands, Env env);
}
