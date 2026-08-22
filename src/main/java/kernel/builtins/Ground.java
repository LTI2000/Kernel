package kernel.builtins;

import kernel.model.Applicative;
import kernel.model.Ch;
import kernel.model.Combiner;
import kernel.model.Const;
import kernel.model.ContinuationInvoked;
import kernel.model.Encapsulation;
import kernel.model.Env;
import kernel.model.Int;
import kernel.model.KernelError;
import kernel.model.Obj;
import kernel.model.Operative;
import kernel.model.Pair;
import kernel.model.Prim;
import kernel.model.PrimFn;
import kernel.model.Promise;
import kernel.model.Real;
import kernel.model.Str;
import kernel.model.Sym;
import kernel.model.TailCall;
import kernel.model.Values;
import kernel.model.Vau;
import kernel.printer.Printer;
import kernel.reader.Reader;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static kernel.builtins.Numbers.add;
import static kernel.builtins.Numbers.asInt;
import static kernel.builtins.Numbers.compare;
import static kernel.builtins.Numbers.divide;
import static kernel.builtins.Numbers.errorValue;
import static kernel.builtins.Numbers.extremum;
import static kernel.builtins.Numbers.floorDiv;
import static kernel.builtins.Numbers.fold;
import static kernel.builtins.Numbers.mul;
import static kernel.builtins.Numbers.roundTo;
import static kernel.builtins.Numbers.sub;
import static kernel.builtins.Numbers.toDouble;
import static kernel.eval.Evaluator.bind;
import static kernel.eval.Evaluator.combine;
import static kernel.eval.Evaluator.eval;
import static kernel.eval.Evaluator.evalString;
import static kernel.eval.Evaluator.loadFile;
import static kernel.eval.Evaluator.sequence;
import static kernel.model.Values.arg;
import static kernel.model.Values.argOr;
import static kernel.model.Values.asCombiner;
import static kernel.model.Values.asEnv;
import static kernel.model.Values.asPair;
import static kernel.model.Values.asStr;
import static kernel.model.Values.bool;
import static kernel.model.Values.car;
import static kernel.model.Values.cdr;
import static kernel.model.Values.cons;
import static kernel.model.Values.equalp;
import static kernel.model.Values.fromJava;
import static kernel.model.Values.list;
import static kernel.model.Values.toJava;
import static kernel.model.Values.truthy;

/** Builds {@link #GROUND}, the standard environment every Kernel program starts in. */
public final class Ground {

    private Ground() {}

    static void op(Env e, String name, PrimFn fn) {
        e.define(new Sym(name), new Prim(name, fn));
    }

    static void ap(Env e, String name, PrimFn fn) {
        e.define(new Sym(name), new Applicative(new Prim(name, fn)));
    }

    /** Kernel type predicates accept any number of arguments. */
    static void pred(Env e, String name, Predicate<Obj> test) {
        ap(e, name, (args, env) -> {
            for (Obj a : toJava(args)) if (!test.test(a)) return Const.FALSE;
            return Const.TRUE;
        });
    }

    interface Rel { boolean test(Obj a, Obj b); }

    /** #t when every adjacent pair of arguments satisfies the relation. */
    static Obj chain(Obj args, Rel rel) {
        List<Obj> xs = toJava(args);
        for (int i = 0; i + 1 < xs.size(); i++)
            if (!rel.test(xs.get(i), xs.get(i + 1))) return Const.FALSE;
        return Const.TRUE;
    }

    public static final Env GROUND = makeGround();

    private static Env makeGround() {
        Env g = new Env();

        // ---- booleans ----------------------------------------------------
        pred(g, "boolean?", o -> o == Const.TRUE || o == Const.FALSE);
        ap(g, "not?", (a, e) -> bool(!truthy(arg(a, 0, "not?"))));
        ap(g, "and?", (a, e) -> { for (Obj x : toJava(a)) if (!truthy(x)) return Const.FALSE; return Const.TRUE; });
        ap(g, "or?", (a, e) -> { for (Obj x : toJava(a)) if (truthy(x)) return Const.TRUE; return Const.FALSE; });

        // ---- equivalence -------------------------------------------------
        ap(g, "eq?", (a, e) -> chain(a, Values::eqv));
        ap(g, "equal?", (a, e) -> chain(a, Values::equalp));

        // ---- type predicates ---------------------------------------------
        pred(g, "symbol?", o -> o instanceof Sym);
        pred(g, "inert?", o -> o == Const.INERT);
        pred(g, "ignore?", o -> o == Const.IGNORE);
        pred(g, "eof-object?", o -> o == Const.EOF);
        pred(g, "null?", o -> o == Const.NIL);
        pred(g, "pair?", o -> o instanceof Pair);
        pred(g, "list?", Values::isList);
        pred(g, "environment?", o -> o instanceof Env);
        pred(g, "operative?", o -> o instanceof Operative);
        pred(g, "applicative?", o -> o instanceof Applicative);
        pred(g, "combiner?", o -> o instanceof Combiner);
        pred(g, "number?", o -> o instanceof Int || o instanceof Real);
        pred(g, "integer?", o -> o instanceof Int);
        pred(g, "exact?", o -> o instanceof Int);
        pred(g, "inexact?", o -> o instanceof Real);
        pred(g, "string?", o -> o instanceof Str);
        pred(g, "char?", o -> o instanceof Ch);
        pred(g, "promise?", o -> o instanceof Promise);

        // ---- control -----------------------------------------------------
        op(g, "$if", (a, e) -> truthy(eval(arg(a, 0, "$if"), e))
                ? new TailCall(arg(a, 1, "$if"), e)
                : new TailCall(argOr(a, 2, Const.INERT), e));
        op(g, "$sequence", (a, e) -> sequence(a, e));
        op(g, "$quote", (a, e) -> arg(a, 0, "$quote"));      // convenience, not in R^-1RK
        ap(g, "error", (a, e) -> {
            List<Obj> parts = toJava(a);
            StringBuilder sb = new StringBuilder();
            for (Obj part : parts) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(Printer.display(part));
            }
            throw new KernelError(sb.toString(), a);
        });

        // Escape-only continuations: enough for early exit, generators and
        // exception-style control, but not re-entrant (no full call/cc).
        PrimFn callcc = (a, e) -> {
            Obj f = arg(a, 0, "call/cc");
            Object tag = new Object();
            Obj k = new Applicative(new Prim("continuation", (ka, ke) -> {
                throw new ContinuationInvoked(tag, argOr(ka, 0, Const.INERT));
            }));
            try {
                return combine(f, list(k), e);
            } catch (ContinuationInvoked ci) {
                if (ci.tag == tag) return ci.value;
                throw ci;
            }
        };
        ap(g, "call/cc", callcc);
        ap(g, "call-with-current-continuation", callcc);

        // ---- pairs and lists ---------------------------------------------
        ap(g, "cons", (a, e) -> cons(arg(a, 0, "cons"), arg(a, 1, "cons")));
        ap(g, "set-car!", (a, e) -> { asPair(arg(a, 0, "set-car!")).car = arg(a, 1, "set-car!"); return Const.INERT; });
        ap(g, "set-cdr!", (a, e) -> { asPair(arg(a, 0, "set-cdr!")).cdr = arg(a, 1, "set-cdr!"); return Const.INERT; });
        for (String path : accessorPaths()) {
            String name = "c" + path + "r";
            ap(g, name, (a, e) -> {
                Obj x = arg(a, 0, name);
                for (int i = path.length() - 1; i >= 0; i--)
                    x = path.charAt(i) == 'a' ? car(x) : cdr(x);
                return x;
            });
        }
        ap(g, "length", (a, e) -> Int.of(toJava(arg(a, 0, "length")).size()));
        ap(g, "append", (a, e) -> {
            List<Obj> lists = toJava(a);
            if (lists.isEmpty()) return Const.NIL;
            Obj result = lists.get(lists.size() - 1);
            for (int i = lists.size() - 2; i >= 0; i--) {
                List<Obj> items = toJava(lists.get(i));
                for (int j = items.size() - 1; j >= 0; j--) result = cons(items.get(j), result);
            }
            return result;
        });
        ap(g, "reverse", (a, e) -> {
            Obj result = Const.NIL;
            for (Obj x : toJava(arg(a, 0, "reverse"))) result = cons(x, result);
            return result;
        });
        ap(g, "list-tail", (a, e) -> {
            Obj x = arg(a, 0, "list-tail");
            for (BigInteger n = asInt(arg(a, 1, "list-tail")); n.signum() > 0; n = n.subtract(BigInteger.ONE))
                x = cdr(x);
            return x;
        });
        ap(g, "list-ref", (a, e) -> {
            Obj x = arg(a, 0, "list-ref");
            for (BigInteger n = asInt(arg(a, 1, "list-ref")); n.signum() > 0; n = n.subtract(BigInteger.ONE))
                x = cdr(x);
            return car(x);
        });
        ap(g, "map", (a, e) -> {
            List<Obj> as = toJava(a);
            if (as.size() < 2) throw new KernelError("map: expected an applicative and at least one list");
            List<List<Obj>> lists = new ArrayList<>();
            for (int i = 1; i < as.size(); i++) lists.add(toJava(as.get(i)));
            int n = lists.get(0).size();
            for (List<Obj> l : lists)
                if (l.size() != n) throw new KernelError("map: lists differ in length");
            List<Obj> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                List<Obj> callArgs = new ArrayList<>(lists.size());
                for (List<Obj> l : lists) callArgs.add(l.get(i));
                out.add(combine(as.get(0), fromJava(callArgs), e));
            }
            return fromJava(out);
        });
        ap(g, "filter", (a, e) -> {
            Obj f = arg(a, 0, "filter");
            List<Obj> out = new ArrayList<>();
            for (Obj x : toJava(arg(a, 1, "filter")))
                if (truthy(combine(f, list(x), e))) out.add(x);
            return fromJava(out);
        });
        ap(g, "reduce", (a, e) -> {                 // (reduce list binary identity)
            List<Obj> items = toJava(arg(a, 0, "reduce"));
            Obj binary = arg(a, 1, "reduce");
            if (items.isEmpty()) return arg(a, 2, "reduce");
            Obj acc = items.get(0);
            for (int i = 1; i < items.size(); i++) acc = combine(binary, list(acc, items.get(i)), e);
            return acc;
        });
        ap(g, "assoc", (a, e) -> {
            Obj key = arg(a, 0, "assoc");
            for (Obj entry : toJava(arg(a, 1, "assoc")))
                if (entry instanceof Pair p && equalp(key, p.car)) return p;
            return Const.NIL;
        });
        ap(g, "member?", (a, e) -> {
            Obj key = arg(a, 0, "member?");
            for (Obj x : toJava(arg(a, 1, "member?"))) if (equalp(key, x)) return Const.TRUE;
            return Const.FALSE;
        });

        // ---- combiners ---------------------------------------------------
        op(g, "$vau", (a, e) -> {
            Obj ptree = arg(a, 0, "$vau");
            Obj eformal = arg(a, 1, "$vau");
            if (!(eformal instanceof Sym) && eformal != Const.IGNORE)
                throw new KernelError("$vau: environment parameter must be a symbol or #ignore");
            return new Vau(ptree, eformal, cdr(cdr(a)), e);
        });
        ap(g, "wrap", (a, e) -> new Applicative(asCombiner(arg(a, 0, "wrap"))));
        ap(g, "unwrap", (a, e) -> {
            if (arg(a, 0, "unwrap") instanceof Applicative app) return app.underlying();
            throw new KernelError("unwrap: not an applicative: " + Printer.write(arg(a, 0, "unwrap")));
        });
        // (apply applicative arg-list [env]) == (eval (cons (unwrap applicative) arg-list) env)
        ap(g, "apply", (a, e) -> {
            Obj f = arg(a, 0, "apply");
            Obj args = argOr(a, 1, Const.NIL);
            Env env = argOr(a, 2, null) instanceof Env given ? given : new Env();
            if (!(f instanceof Applicative app))
                throw new KernelError("apply: not an applicative: " + Printer.write(f));
            return new TailCall(cons(app.underlying(), args), env);
        });

        // ---- environments --------------------------------------------------
        ap(g, "eval", (a, e) -> new TailCall(arg(a, 0, "eval"), asEnv(arg(a, 1, "eval"))));
        ap(g, "make-environment", (a, e) -> {
            List<Env> parents = new ArrayList<>();
            for (Obj p : toJava(a)) parents.add(asEnv(p));
            return new Env(parents);
        });
        ap(g, "get-current-environment", (a, e) -> e);
        ap(g, "make-kernel-standard-environment", (a, e) -> new Env(GROUND));
        op(g, "$define!", (a, e) -> {
            Obj ptree = arg(a, 0, "$define!");
            Obj value = eval(arg(a, 1, "$define!"), e);
            nameIfAnonymous(ptree, value);
            bind(ptree, value, e);
            return Const.INERT;
        });
        op(g, "$binds?", (a, e) -> {
            Env target = asEnv(eval(arg(a, 0, "$binds?"), e));
            for (Obj s : toJava(cdr(a))) {
                if (!(s instanceof Sym sym)) throw new KernelError("$binds?: expected a symbol");
                if (target.tryLookup(sym).isEmpty()) return Const.FALSE;
            }
            return Const.TRUE;
        });

        // ---- numbers -------------------------------------------------------
        ap(g, "+", (a, e) -> fold(a, Int.of(0), Numbers::add));
        ap(g, "*", (a, e) -> fold(a, Int.of(1), Numbers::mul));
        ap(g, "-", (a, e) -> {
            List<Obj> xs = toJava(a);
            if (xs.isEmpty()) throw new KernelError("-: expected at least one argument");
            if (xs.size() == 1) return sub(Int.of(0), xs.get(0));
            Obj acc = xs.get(0);
            for (int i = 1; i < xs.size(); i++) acc = sub(acc, xs.get(i));
            return acc;
        });
        ap(g, "/", (a, e) -> {
            List<Obj> xs = toJava(a);
            if (xs.isEmpty()) throw new KernelError("/: expected at least one argument");
            Obj acc = xs.size() == 1 ? Int.of(1) : xs.get(0);
            for (int i = xs.size() == 1 ? 0 : 1; i < xs.size(); i++) acc = divide(acc, xs.get(i));
            return acc;
        });
        ap(g, "=?", (a, e) -> chain(a, (x, y) -> compare(x, y) == 0));
        ap(g, "<?", (a, e) -> chain(a, (x, y) -> compare(x, y) < 0));
        ap(g, "<=?", (a, e) -> chain(a, (x, y) -> compare(x, y) <= 0));
        ap(g, ">?", (a, e) -> chain(a, (x, y) -> compare(x, y) > 0));
        ap(g, ">=?", (a, e) -> chain(a, (x, y) -> compare(x, y) >= 0));
        pred(g, "zero?", o -> compare(o, Int.of(0)) == 0);
        pred(g, "positive?", o -> compare(o, Int.of(0)) > 0);
        pred(g, "negative?", o -> compare(o, Int.of(0)) < 0);
        pred(g, "odd?", o -> asInt(o).testBit(0));
        pred(g, "even?", o -> !asInt(o).testBit(0));
        ap(g, "min", (a, e) -> extremum(a, "min", -1));
        ap(g, "max", (a, e) -> extremum(a, "max", 1));
        ap(g, "abs", (a, e) -> arg(a, 0, "abs") instanceof Int i
                ? new Int(i.value().abs()) : new Real(Math.abs(toDouble(arg(a, 0, "abs")))));
        ap(g, "div", (a, e) -> new Int(floorDiv(asInt(arg(a, 0, "div")), asInt(arg(a, 1, "div")))));
        ap(g, "mod", (a, e) -> {
            BigInteger n = asInt(arg(a, 0, "mod")), d = asInt(arg(a, 1, "mod"));
            return new Int(n.subtract(floorDiv(n, d).multiply(d)));
        });
        ap(g, "gcd", (a, e) -> {
            BigInteger acc = BigInteger.ZERO;
            for (Obj x : toJava(a)) acc = acc.gcd(asInt(x));
            return new Int(acc);
        });
        ap(g, "expt", (a, e) -> {
            Obj base = arg(a, 0, "expt"), power = arg(a, 1, "expt");
            if (base instanceof Int b && power instanceof Int p && p.value().signum() >= 0)
                return new Int(b.value().pow(p.value().intValueExact()));
            return new Real(Math.pow(toDouble(base), toDouble(power)));
        });
        ap(g, "sqrt", (a, e) -> new Real(Math.sqrt(toDouble(arg(a, 0, "sqrt")))));
        ap(g, "exact->inexact", (a, e) -> new Real(toDouble(arg(a, 0, "exact->inexact"))));
        ap(g, "inexact->exact", (a, e) -> arg(a, 0, "inexact->exact") instanceof Real r
                ? new Int(BigInteger.valueOf(Math.round(r.value()))) : arg(a, 0, "inexact->exact"));
        ap(g, "floor", (a, e) -> roundTo(arg(a, 0, "floor"), Math::floor));
        ap(g, "ceiling", (a, e) -> roundTo(arg(a, 0, "ceiling"), Math::ceil));
        ap(g, "round", (a, e) -> roundTo(arg(a, 0, "round"), Math::rint));
        ap(g, "truncate", (a, e) -> roundTo(arg(a, 0, "truncate"), d -> (double) (long) d));

        // ---- strings, symbols, characters -----------------------------------
        ap(g, "symbol->string", (a, e) -> arg(a, 0, "symbol->string") instanceof Sym s
                ? new Str(s.name()) : errorValue("symbol->string", arg(a, 0, "symbol->string")));
        ap(g, "string->symbol", (a, e) -> new Sym(asStr(arg(a, 0, "string->symbol")).value()));
        ap(g, "string-append", (a, e) -> {
            StringBuilder sb = new StringBuilder();
            for (Obj x : toJava(a)) sb.append(asStr(x).value());
            return new Str(sb.toString());
        });
        ap(g, "string-length", (a, e) -> Int.of(asStr(arg(a, 0, "string-length")).value().length()));
        ap(g, "substring", (a, e) -> new Str(asStr(arg(a, 0, "substring")).value().substring(
                asInt(arg(a, 1, "substring")).intValueExact(),
                asInt(arg(a, 2, "substring")).intValueExact())));
        ap(g, "string->number", (a, e) -> Reader.atom(asStr(arg(a, 0, "string->number")).value()));
        ap(g, "number->string", (a, e) -> new Str(Printer.write(arg(a, 0, "number->string"))));
        ap(g, "char->integer", (a, e) -> arg(a, 0, "char->integer") instanceof Ch c
                ? Int.of(c.value()) : errorValue("char->integer", arg(a, 0, "char->integer")));
        ap(g, "integer->char", (a, e) -> new Ch((char) asInt(arg(a, 0, "integer->char")).intValueExact()));

        // ---- input / output ---------------------------------------------------
        ap(g, "display", (a, e) -> { System.out.print(Printer.display(arg(a, 0, "display"))); return Const.INERT; });
        ap(g, "write", (a, e) -> { System.out.print(Printer.write(arg(a, 0, "write"))); return Const.INERT; });
        ap(g, "newline", (a, e) -> { System.out.println(); return Const.INERT; });
        ap(g, "load", (a, e) -> { loadFile(asStr(arg(a, 0, "load")).value(), e); return Const.INERT; });

        // ---- promises -----------------------------------------------------------
        op(g, "$lazy", (a, e) -> new Promise(arg(a, 0, "$lazy"), e));
        ap(g, "memoize", (a, e) -> Promise.of(arg(a, 0, "memoize")));
        ap(g, "force", (a, e) -> {
            Obj x = arg(a, 0, "force");
            if (!(x instanceof Promise p)) return x;
            while (!p.forced) {
                Obj v = eval(p.expr, p.env);
                if (p.forced) break;
                if (v instanceof Promise inner) {        // a promise yielding a promise
                    if (inner.forced) { p.value = inner.value; p.forced = true; break; }
                    p.expr = inner.expr;
                    p.env = inner.env;
                } else { p.value = v; p.forced = true; p.env = null; p.expr = null; }
            }
            return p.value;
        });

        // ---- encapsulated types --------------------------------------------------
        ap(g, "make-encapsulation-type", (a, e) -> {
            Object type = new Object();
            Obj encapsulate = new Applicative(new Prim("encapsulate",
                    (xa, xe) -> new Encapsulation(type, arg(xa, 0, "encapsulate"))));
            Obj predicate = new Applicative(new Prim("encapsulated?", (xa, xe) -> {
                for (Obj x : toJava(xa))
                    if (!(x instanceof Encapsulation enc) || enc.type != type) return Const.FALSE;
                return Const.TRUE;
            }));
            Obj decapsulate = new Applicative(new Prim("decapsulate", (xa, xe) -> {
                if (arg(xa, 0, "decapsulate") instanceof Encapsulation enc && enc.type == type)
                    return enc.value;
                throw new KernelError("decapsulate: wrong encapsulation type");
            }));
            return list(encapsulate, predicate, decapsulate);
        });

        evalString(Prelude.SOURCE, g);
        return g;
    }

    /** All of caar ... cddddr. */
    private static List<String> accessorPaths() {
        List<String> level = List.of("a", "d");
        List<String> paths = new ArrayList<>(level);
        List<String> current = new ArrayList<>(level);
        for (int depth = 2; depth <= 4; depth++) {
            List<String> next = new ArrayList<>();
            for (String p : current) for (String c : level) next.add(p + c);
            paths.addAll(next);
            current = next;
        }
        return paths;
    }

    /** Gives {@code ($define! foo ($lambda ...))} a printable name. */
    private static void nameIfAnonymous(Obj ptree, Obj value) {
        if (!(ptree instanceof Sym s)) return;
        Combiner c = value instanceof Combiner k ? k : null;
        while (c instanceof Applicative a) c = a.underlying();
        if (c instanceof Vau v && v.name.equals("anonymous")) v.name = s.name();
    }
}
