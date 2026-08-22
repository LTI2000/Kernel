package kernel.model;

public final class Promise implements Obj {
    public Obj expr; public Env env; public Obj value; public boolean forced;
    public Promise(Obj expr, Env env) { this.expr = expr; this.env = env; }
    public static Promise of(Obj value) {
        Promise p = new Promise(null, null);
        p.value = value; p.forced = true;
        return p;
    }
}
