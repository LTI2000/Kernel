package kernel.model;

/** Evaluates its operands, then combines them with {@code underlying}. */
public record Applicative(Combiner underlying) implements Combiner {}
