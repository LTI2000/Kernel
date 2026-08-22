package kernel.model;

/** Every Kernel value. Sealed, so the evaluator's and printer's switches are checked. */
public sealed interface Obj
        permits Sym, Const, Int, Real, Str, Ch, Pair, Env, Combiner, Promise, Encapsulation, TailCall {
}
