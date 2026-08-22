package kernel.model;

/** A compound operative: the result of evaluating {@code ($vau ...)}. */
public final class Vau implements Operative {
    public final Obj ptree, eformal, body;
    public final Env staticEnv;
    public String name = "anonymous";
    public Vau(Obj ptree, Obj eformal, Obj body, Env staticEnv) {
        this.ptree = ptree; this.eformal = eformal;
        this.body = body; this.staticEnv = staticEnv;
    }
}
