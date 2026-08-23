package kernel.printer;

import kernel.model.Obj;
import kernel.model.Obj.Ch;
import kernel.model.Obj.Combiner;
import kernel.model.Obj.Combiner.Applicative;
import kernel.model.Obj.Combiner.Operative.Prim;
import kernel.model.Obj.Combiner.Operative.Vau;
import kernel.model.Obj.Const;
import kernel.model.Obj.Encapsulation;
import kernel.model.Obj.Env;
import kernel.model.Obj.Pair;
import kernel.model.Obj.Promise;
import kernel.model.Obj.Real;
import kernel.model.Obj.Str;

/** Renders {@link Obj} values back to Kernel-readable ({@code write}) or plain ({@code display}) text. */
public final class Printer {

    private Printer() {}

    private static final int PRINT_LIMIT = 1000;   // guards against cyclic structure

    public static String write(Obj o) {
        StringBuilder sb = new StringBuilder();
        print(o, sb, true);
        return sb.toString();
    }

    public static String display(Obj o) {
        StringBuilder sb = new StringBuilder();
        print(o, sb, false);
        return sb.toString();
    }

    private static void print(Obj o, StringBuilder sb, boolean readably) {
        switch (o) {
            case Str s -> {
                if (!readably) { sb.append(s.value()); return; }
                sb.append('"');
                for (char c : s.value().toCharArray()) {
                    switch (c) {
                        case '"' -> sb.append("\\\"");
                        case '\\' -> sb.append("\\\\");
                        case '\n' -> sb.append("\\n");
                        case '\t' -> sb.append("\\t");
                        case '\r' -> sb.append("\\r");
                        default -> sb.append(c);
                    }
                }
                sb.append('"');
            }
            case Ch c -> sb.append(readably ? charName(c.value()) : String.valueOf(c.value()));
            case Real r -> sb.append(formatReal(r.value()));
            case Pair p -> {
                sb.append('(');
                Obj cur = p;
                int count = 0;
                while (cur instanceof Pair q) {
                    if (count > 0) sb.append(' ');
                    if (++count > PRINT_LIMIT) { sb.append("..."); cur = Const.NIL; break; }
                    print(q.car, sb, readably);
                    cur = q.cdr;
                }
                if (cur != Const.NIL) { sb.append(" . "); print(cur, sb, readably); }
                sb.append(')');
            }
            case Applicative a -> sb.append("#[applicative ").append(combinerName(a.underlying())).append(']');
            case Prim p -> sb.append("#[operative ").append(p.name()).append(']');
            case Vau v -> sb.append("#[operative ").append(v.name).append(']');
            case Env e -> sb.append("#[environment]");
            case Promise p -> sb.append("#[promise").append(p.state instanceof Promise.Forced ? " forced" : "").append(']');
            case Encapsulation e -> sb.append("#[encapsulation]");
            default -> sb.append(o.toString());
        }
    }

    private static String combinerName(Combiner c) {
        return switch (c) {
            case Prim p -> p.name();
            case Vau v -> v.name;
            case Applicative a -> combinerName(a.underlying());
        };
    }

    private static String formatReal(double d) {
        if (Double.isNaN(d)) return "+nan.0";
        if (d == Double.POSITIVE_INFINITY) return "+inf.0";
        if (d == Double.NEGATIVE_INFINITY) return "-inf.0";
        return Double.toString(d);
    }

    private static String charName(char c) {
        return switch (c) {
            case ' ' -> "#\\space";
            case '\n' -> "#\\newline";
            case '\t' -> "#\\tab";
            case '\r' -> "#\\return";
            case '\0' -> "#\\null";
            default -> "#\\" + c;
        };
    }
}
