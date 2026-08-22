package kernel.model;

import java.math.BigInteger;

public record Int(BigInteger value) implements Obj {
    public static Int of(long v) { return new Int(BigInteger.valueOf(v)); }
    @Override public String toString() { return value.toString(); }
}
