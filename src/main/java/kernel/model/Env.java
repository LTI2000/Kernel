package kernel.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A first-class environment: a local frame plus an ordered parent list. */
public final class Env implements Obj {
    private final Map<Sym, Obj> frame = new HashMap<>();
    private final List<Env> parents;

    public Env(Env... parents) { this.parents = List.of(parents); }
    public Env(List<Env> parents) { this.parents = List.copyOf(parents); }

    public Optional<Obj> tryLookup(Sym s) {
        Obj local = frame.get(s);
        if (local != null) return Optional.of(local);
        for (Env parent : parents) {
            Optional<Obj> found = parent.tryLookup(s);
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    public Obj lookup(Sym s) {
        return tryLookup(s).orElseThrow(
                () -> new KernelError("unbound symbol: " + s.name()));
    }

    public void define(Sym s, Obj value) { frame.put(s, value); }
}
