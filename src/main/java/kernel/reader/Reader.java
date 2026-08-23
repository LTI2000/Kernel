package kernel.reader;

import kernel.model.KernelError;
import kernel.model.Obj;
import kernel.model.Obj.Ch;
import kernel.model.Obj.Const;
import kernel.model.Obj.Int;
import kernel.model.Obj.Real;
import kernel.model.Obj.Str;
import kernel.model.Obj.Sym;

import java.io.Closeable;
import java.io.IOException;
import java.io.PushbackReader;
import java.io.StringReader;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static kernel.model.Values.cons;
import static kernel.model.Values.list;

/** Parses Kernel source text into {@link Obj} data (s-expressions). */
public final class Reader implements Closeable {
    private final PushbackReader in;

    public Reader(java.io.Reader source) { this.in = new PushbackReader(source, 4); }
    public Reader(String source) { this(new StringReader(source)); }

    @Override public void close() throws IOException { in.close(); }

    private int peek() {
        try {
            int c = in.read();
            if (c != -1) in.unread(c);
            return c;
        } catch (IOException e) { throw new KernelError("read error: " + e.getMessage()); }
    }

    private int next() {
        try { return in.read(); }
        catch (IOException e) { throw new KernelError("read error: " + e.getMessage()); }
    }

    private static boolean isDelimiter(int c) {
        return c == -1 || Character.isWhitespace(c) || c == '(' || c == ')'
                || c == '[' || c == ']' || c == '"' || c == ';' || c == '\'';
    }

    private void skipAtmosphere() {
        while (true) {
            int c = peek();
            if (c == -1) return;
            if (Character.isWhitespace(c)) { next(); continue; }
            if (c == ';') { while (true) { int d = next(); if (d == -1 || d == '\n') break; } continue; }
            if (c == '#') {
                next();
                int d = peek();
                if (d == '|') { next(); skipBlockComment(); continue; }
                if (d == ';') { next(); read(); continue; }   // datum comment
                try { in.unread('#'); } catch (IOException e) { throw new KernelError("read error"); }
                return;
            }
            return;
        }
    }

    private void skipBlockComment() {
        int depth = 1;
        while (depth > 0) {
            int c = next();
            if (c == -1) throw new KernelError("unterminated block comment");
            if (c == '#' && peek() == '|') { next(); depth++; }
            else if (c == '|' && peek() == '#') { next(); depth--; }
        }
    }

    /** @return the next datum, or {@code null} at end of input. */
    public Obj read() {
        skipAtmosphere();
        int c = next();
        if (c == -1) return null;
        switch (c) {
            case '(': case '[': return readListTail(c == '(' ? ')' : ']');
            case ')': case ']': throw new KernelError("unexpected '" + (char) c + "'");
            case '\'': {
                Obj quoted = read();
                if (quoted == null) throw new KernelError("unexpected EOF after quote");
                return list(new Sym("$quote"), quoted);
            }
            case '"': return readString();
            case '#': return readHash();
            default: {
                StringBuilder sb = new StringBuilder().append((char) c);
                while (!isDelimiter(peek())) sb.append((char) next());
                return atom(sb.toString());
            }
        }
    }

    private Obj readListTail(char closing) {
        List<Obj> items = new ArrayList<>();
        Obj tail = Const.NIL;
        while (true) {
            skipAtmosphere();
            int c = peek();
            if (c == -1) throw new KernelError("unterminated list");
            if (c == ')' || c == ']') {
                next();
                if (c != closing) throw new KernelError("mismatched '" + (char) c + "'");
                break;
            }
            if (c == '.') {
                next();
                if (isDelimiter(peek())) {          // dotted tail
                    Obj rest = read();
                    if (rest == null) throw new KernelError("unexpected EOF after '.'");
                    tail = rest;
                    skipAtmosphere();
                    int close = next();
                    if (close != closing) throw new KernelError("expected '" + closing + "'");
                    break;
                }
                StringBuilder sb = new StringBuilder(".");
                while (!isDelimiter(peek())) sb.append((char) next());
                items.add(atom(sb.toString()));
                continue;
            }
            Obj item = read();
            if (item == null) throw new KernelError("unterminated list");
            items.add(item);
        }
        Obj result = tail;
        for (int i = items.size() - 1; i >= 0; i--) result = cons(items.get(i), result);
        return result;
    }

    private Obj readString() {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = next();
            if (c == -1) throw new KernelError("unterminated string");
            if (c == '"') break;
            if (c == '\\') {
                int e = next();
                sb.append(switch (e) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case 'r' -> '\r';
                    case '0' -> '\0';
                    case -1 -> throw new KernelError("unterminated string");
                    default -> (char) e;
                });
            } else sb.append((char) c);
        }
        return new Str(sb.toString());
    }

    private Obj readHash() {
        int c = next();
        if (c == '\\') {                        // character literal
            StringBuilder sb = new StringBuilder();
            sb.append((char) next());
            while (!isDelimiter(peek())) sb.append((char) next());
            String name = sb.toString();
            if (name.length() == 1) return new Ch(name.charAt(0));
            return new Ch(switch (name.toLowerCase(Locale.ROOT)) {
                case "space" -> ' ';
                case "newline", "linefeed" -> '\n';
                case "tab" -> '\t';
                case "return" -> '\r';
                case "null", "nul" -> '\0';
                default -> throw new KernelError("unknown character name: #\\" + name);
            });
        }
        StringBuilder sb = new StringBuilder().append((char) c);
        while (!isDelimiter(peek())) sb.append((char) next());
        String token = sb.toString();
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "t", "true" -> Const.TRUE;
            case "f", "false" -> Const.FALSE;
            case "inert" -> Const.INERT;
            case "ignore" -> Const.IGNORE;
            case "eof" -> Const.EOF;
            default -> throw new KernelError("unknown '#' syntax: #" + token);
        };
    }

    /** Parses a single token as a number if possible, otherwise as a symbol. */
    public static Obj atom(String token) {
        if (token.isEmpty()) throw new KernelError("empty token");
        char c0 = token.charAt(0);
        boolean numeric = Character.isDigit(c0)
                || ((c0 == '-' || c0 == '+' || c0 == '.') && token.length() > 1);
        if (numeric) {
            try { return new Int(new BigInteger(token)); } catch (NumberFormatException ignored) { }
            try { return new Real(Double.parseDouble(token)); } catch (NumberFormatException ignored) { }
        }
        return new Sym(token);
    }
}
