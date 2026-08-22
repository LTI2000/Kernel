package kernel.model;

public record Sym(String name) implements Obj {
    @Override public String toString() { return name; }
}
