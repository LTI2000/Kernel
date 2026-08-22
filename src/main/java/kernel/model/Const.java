package kernel.model;

/** The self-evaluating unique constants. */
public enum Const implements Obj {
    NIL("()"), TRUE("#t"), FALSE("#f"),
    INERT("#inert"), IGNORE("#ignore"), EOF("#eof");
    private final String text;
    Const(String text) { this.text = text; }
    @Override public String toString() { return text; }
}
