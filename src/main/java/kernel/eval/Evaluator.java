package kernel.eval;

import kernel.model.Applicative;
import kernel.model.Combiner;
import kernel.model.Const;
import kernel.model.Env;
import kernel.model.KernelError;
import kernel.model.Obj;
import kernel.model.Pair;
import kernel.model.Prim;
import kernel.model.Sym;
import kernel.model.TailCall;
import kernel.model.Vau;
import kernel.printer.Printer;
import kernel.reader.Reader;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static kernel.model.Values.asCombiner;
import static kernel.model.Values.car;
import static kernel.model.Values.cdr;
import static kernel.model.Values.cons;

/**
 * The whole language, in one loop, plus the handful of operations
 * ($vau bodies, operand evaluation, tail-call sequencing) it depends on directly.
 */
public final class Evaluator {

    private Evaluator() {}

    /**
     * Symbols look up; non-pairs are self-evaluating; a pair evaluates its
     * car to a combiner and combines. Applicatives evaluate their operands and
     * re-dispatch on the underlying combiner; operatives receive the operands
     * as they were written, plus the dynamic environment. The loop, rather
     * than recursion, is what makes tail calls proper.
     */
    public static Obj eval(Obj expr, Env env) {
        while (true) {
            if (expr instanceof Sym s) return env.lookup(s);
            if (!(expr instanceof Pair p)) return expr;

            Obj combiner = eval(p.car, env);
            Obj operands = p.cdr;

            while (combiner instanceof Applicative a) {
                operands = evalOperands(operands, env);
                combiner = a.underlying();
            }

            if (combiner instanceof Prim prim) {
                Obj result = prim.fn().call(operands, env);
                if (result instanceof TailCall tc) { expr = tc.expr(); env = tc.env(); continue; }
                return result;
            }
            if (combiner instanceof Vau vau) {
                Env local = new Env(vau.staticEnv);
                bind(vau.ptree, operands, local);
                if (vau.eformal instanceof Sym s) local.define(s, env);
                Obj body = vau.body;
                if (body == Const.NIL) return Const.INERT;
                while (cdr(body) != Const.NIL) { eval(car(body), local); body = cdr(body); }
                expr = car(body);
                env = local;
                continue;
            }
            throw new KernelError("not a combiner: " + Printer.write(combiner));
        }
    }

    private static Obj evalOperands(Obj operands, Env env) {
        if (operands == Const.NIL) return Const.NIL;
        if (!(operands instanceof Pair first))
            throw new KernelError("operand list is improper: " + Printer.write(operands));
        Pair head = cons(eval(first.car, env), Const.NIL);
        Pair last = head;
        Obj rest = first.cdr;
        while (rest instanceof Pair q) {
            Pair cell = cons(eval(q.car, env), Const.NIL);
            last.cdr = cell;
            last = cell;
            rest = q.cdr;
        }
        if (rest != Const.NIL) throw new KernelError("operand list is improper: " + Printer.write(operands));
        return head;
    }

    /** Destructures a parameter tree against an argument object. */
    public static void bind(Obj ptree, Obj value, Env env) {
        if (ptree instanceof Sym s) { env.define(s, value); return; }
        if (ptree == Const.IGNORE) return;
        if (ptree == Const.NIL) {
            if (value != Const.NIL)
                throw new KernelError("too many arguments: expected (), got " + Printer.write(value));
            return;
        }
        if (ptree instanceof Pair pt) {
            if (!(value instanceof Pair pv))
                throw new KernelError("argument mismatch: " + Printer.write(ptree) + " vs " + Printer.write(value));
            bind(pt.car, pv.car, env);
            bind(pt.cdr, pv.cdr, env);
            return;
        }
        throw new KernelError("bad parameter tree: " + Printer.write(ptree));
    }

    /** Evaluates all but the last expression, then tail-calls the last. */
    public static Obj sequence(Obj body, Env env) {
        if (body == Const.NIL) return Const.INERT;
        while (cdr(body) != Const.NIL) { eval(car(body), env); body = cdr(body); }
        return new TailCall(car(body), env);
    }

    /** Combines a value with an already-evaluated argument list. */
    public static Obj combine(Obj combiner, Obj args, Env env) {
        Combiner c = asCombiner(combiner);
        Obj target = c instanceof Applicative a ? a.underlying() : c;
        return eval(cons(target, args), env);
    }

    // ---- loading source text ------------------------------------------------

    public static Obj evalString(String source, Env env) {
        Obj last = Const.INERT;
        try (Reader reader = new Reader(source)) {
            Obj form;
            while ((form = reader.read()) != null) last = eval(form, env);
        } catch (IOException e) {
            throw new KernelError("i/o error: " + e.getMessage());
        }
        return last;
    }

    public static Obj loadFile(String path, Env env) {
        try (java.io.Reader in = new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8);
             Reader reader = new Reader(in)) {
            Obj last = Const.INERT;
            Obj form;
            while ((form = reader.read()) != null) last = eval(form, env);
            return last;
        } catch (FileNotFoundException e) {
            throw new KernelError("cannot open file: " + path);
        } catch (IOException e) {
            throw new KernelError("i/o error: " + e.getMessage());
        }
    }
}
