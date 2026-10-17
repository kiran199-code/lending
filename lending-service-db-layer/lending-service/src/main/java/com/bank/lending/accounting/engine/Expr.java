package com.bank.lending.accounting.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The closed expression language used by entry rules ({@code amount_source} and {@code condition_expr}).
 *
 * <p>Grammar (no code execution, no reflection, no I/O):
 * <pre>
 *   expr := or ;  or := and (OR and)* ;  and := not (AND not)* ;  not := NOT not | cmp
 *   cmp  := add [ (=|==|!=|&lt;&gt;|&lt;|&lt;=|&gt;|&gt;=) add | [NOT] IN '(' expr (',' expr)* ')' ]
 *   add  := mul ((+|-) mul)* ;  mul := unary ((*|/) unary)* ;  unary := '-' unary | primary
 *   primary := number | 'string' | TRUE | FALSE | identifier | fn '(' expr (',' expr)* ')' | '(' expr ')'
 *   fn := min | max | abs
 * </pre>
 * Values are {@link BigDecimal}, {@link String} or {@link Boolean}. Identifiers are looked up in a {@link Scope}.
 */
public final class Expr {

    /** Supplies identifier values. Return {@code null} for an unknown name. */
    public interface Scope {
        Object get(String name);
    }

    /** Raised for syntax errors and for evaluation errors (type mismatch, division by zero). */
    public static final class ExprException extends RuntimeException {
        public ExprException(String message) {
            super(message);
        }
    }

    public static final Set<String> FUNCTIONS = Set.of("min", "max", "abs");

    private final Node root;
    private final String source;

    private Expr(String source, Node root) {
        this.source = source;
        this.root = root;
    }

    public static Expr parse(String text) {
        if (text == null || text.isBlank()) {
            throw new ExprException("Expression is empty");
        }
        Parser p = new Parser(text);
        Node n = p.parseExpr();
        p.expectEnd();
        return new Expr(text, n);
    }

    public String source() {
        return source;
    }

    /** The identifiers the expression refers to, in order of first appearance (function names excluded). */
    public Set<String> identifiers() {
        Set<String> out = new LinkedHashSet<>();
        root.collect(out);
        return out;
    }

    public Object eval(Scope scope) {
        return root.eval(scope);
    }

    public BigDecimal evalNumber(Scope scope) {
        Object v = root.eval(scope);
        if (v instanceof BigDecimal b) {
            return b;
        }
        throw new ExprException("Expression '" + source + "' is not numeric (got " + describe(v) + ")");
    }

    public boolean evalBoolean(Scope scope) {
        Object v = root.eval(scope);
        if (v instanceof Boolean b) {
            return b;
        }
        throw new ExprException("Expression '" + source + "' is not a condition (got " + describe(v) + ")");
    }

    private static String describe(Object v) {
        return v == null ? "null" : v.getClass().getSimpleName() + " " + v;
    }

    // ------------------------------------------------------------------ AST

    private interface Node {
        Object eval(Scope s);

        void collect(Set<String> ids);
    }

    private record Num(BigDecimal v) implements Node {
        public Object eval(Scope s) { return v; }
        public void collect(Set<String> ids) { }
    }

    private record Str(String v) implements Node {
        public Object eval(Scope s) { return v; }
        public void collect(Set<String> ids) { }
    }

    private record Bool(boolean v) implements Node {
        public Object eval(Scope s) { return v; }
        public void collect(Set<String> ids) { }
    }

    private record Ident(String name) implements Node {
        public Object eval(Scope s) { return s.get(name); }
        public void collect(Set<String> ids) { ids.add(name); }
    }

    private record Neg(Node a) implements Node {
        public Object eval(Scope s) { return num(a.eval(s), "-").negate(); }
        public void collect(Set<String> ids) { a.collect(ids); }
    }

    private record Not(Node a) implements Node {
        public Object eval(Scope s) { return !bool(a.eval(s), "NOT"); }
        public void collect(Set<String> ids) { a.collect(ids); }
    }

    private record Bin(char op, Node l, Node r) implements Node {
        public Object eval(Scope s) {
            BigDecimal x = num(l.eval(s), String.valueOf(op));
            BigDecimal y = num(r.eval(s), String.valueOf(op));
            return switch (op) {
                case '+' -> x.add(y);
                case '-' -> x.subtract(y);
                case '*' -> x.multiply(y);
                case '/' -> {
                    if (y.signum() == 0) {
                        throw new ExprException("Division by zero");
                    }
                    yield x.divide(y, 10, RoundingMode.HALF_UP);
                }
                default -> throw new ExprException("Unknown operator " + op);
            };
        }
        public void collect(Set<String> ids) { l.collect(ids); r.collect(ids); }
    }

    private record Logic(boolean and, Node l, Node r) implements Node {
        public Object eval(Scope s) {
            boolean x = bool(l.eval(s), and ? "AND" : "OR");
            if (and ? !x : x) {
                return x; // short circuit
            }
            return bool(r.eval(s), and ? "AND" : "OR");
        }
        public void collect(Set<String> ids) { l.collect(ids); r.collect(ids); }
    }

    private record Cmp(String op, Node l, Node r) implements Node {
        public Object eval(Scope s) {
            Object x = l.eval(s);
            Object y = r.eval(s);
            if (op.equals("=") || op.equals("!=")) {
                boolean eq = equal(x, y);
                return op.equals("=") ? eq : !eq;
            }
            if (x == null || y == null) {
                return false;
            }
            int c;
            if (x instanceof BigDecimal a && y instanceof BigDecimal b) {
                c = a.compareTo(b);
            } else if (x instanceof String a && y instanceof String b) {
                c = a.compareTo(b);
            } else {
                throw new ExprException("Cannot compare " + describe(x) + " with " + describe(y));
            }
            return switch (op) {
                case "<" -> c < 0;
                case "<=" -> c <= 0;
                case ">" -> c > 0;
                case ">=" -> c >= 0;
                default -> throw new ExprException("Unknown comparison " + op);
            };
        }
        public void collect(Set<String> ids) { l.collect(ids); r.collect(ids); }
    }

    private record In(boolean negate, Node l, List<Node> options) implements Node {
        public Object eval(Scope s) {
            Object x = l.eval(s);
            boolean found = false;
            for (Node o : options) {
                if (equal(x, o.eval(s))) {
                    found = true;
                    break;
                }
            }
            return negate != found;
        }
        public void collect(Set<String> ids) { l.collect(ids); options.forEach(o -> o.collect(ids)); }
    }

    private record Call(String fn, List<Node> args) implements Node {
        public Object eval(Scope s) {
            List<BigDecimal> v = new ArrayList<>();
            for (Node a : args) {
                v.add(num(a.eval(s), fn));
            }
            return switch (fn) {
                case "abs" -> v.get(0).abs();
                case "min" -> v.stream().reduce(BigDecimal::min).orElseThrow();
                case "max" -> v.stream().reduce(BigDecimal::max).orElseThrow();
                default -> throw new ExprException("Unknown function " + fn);
            };
        }
        public void collect(Set<String> ids) { args.forEach(a -> a.collect(ids)); }
    }

    private static boolean equal(Object x, Object y) {
        if (x == null || y == null) {
            return x == y;
        }
        if (x instanceof BigDecimal a && y instanceof BigDecimal b) {
            return a.compareTo(b) == 0;
        }
        return x.equals(y);
    }

    private static BigDecimal num(Object v, String ctx) {
        if (v instanceof BigDecimal b) {
            return b;
        }
        throw new ExprException("Operator " + ctx + " needs a number but got " + describe(v));
    }

    private static boolean bool(Object v, String ctx) {
        if (v instanceof Boolean b) {
            return b;
        }
        throw new ExprException("Operator " + ctx + " needs a condition but got " + describe(v));
    }

    // --------------------------------------------------------------- parser

    private static final class Parser {
        private final String t;
        private int i = 0;

        Parser(String text) {
            this.t = text;
        }

        void expectEnd() {
            skip();
            if (i < t.length()) {
                throw new ExprException("Unexpected '" + t.charAt(i) + "' at position " + i + " in: " + t);
            }
        }

        Node parseExpr() {
            return parseOr();
        }

        private Node parseOr() {
            Node n = parseAnd();
            while (keyword("OR")) {
                n = new Logic(false, n, parseAnd());
            }
            return n;
        }

        private Node parseAnd() {
            Node n = parseNot();
            while (keyword("AND")) {
                n = new Logic(true, n, parseNot());
            }
            return n;
        }

        private Node parseNot() {
            if (keyword("NOT")) {
                return new Not(parseNot());
            }
            return parseCmp();
        }

        private Node parseCmp() {
            Node left = parseAdd();
            skip();
            String op = null;
            for (String c : new String[]{"<=", ">=", "<>", "!=", "==", "=", "<", ">"}) {
                if (t.startsWith(c, i)) {
                    op = c;
                    break;
                }
            }
            if (op != null) {
                i += op.length();
                Node right = parseAdd();
                String norm = op.equals("==") ? "=" : op.equals("<>") ? "!=" : op;
                return new Cmp(norm, left, right);
            }
            int save = i;
            boolean negate = keyword("NOT");
            if (keyword("IN")) {
                expect('(');
                List<Node> options = new ArrayList<>();
                options.add(parseExpr());
                while (accept(',')) {
                    options.add(parseExpr());
                }
                expect(')');
                return new In(negate, left, options);
            }
            i = save;
            return left;
        }

        private Node parseAdd() {
            Node n = parseMul();
            while (true) {
                skip();
                if (accept('+')) {
                    n = new Bin('+', n, parseMul());
                } else if (accept('-')) {
                    n = new Bin('-', n, parseMul());
                } else {
                    return n;
                }
            }
        }

        private Node parseMul() {
            Node n = parseUnary();
            while (true) {
                skip();
                if (accept('*')) {
                    n = new Bin('*', n, parseUnary());
                } else if (accept('/')) {
                    n = new Bin('/', n, parseUnary());
                } else {
                    return n;
                }
            }
        }

        private Node parseUnary() {
            skip();
            if (accept('-')) {
                return new Neg(parseUnary());
            }
            return parsePrimary();
        }

        private Node parsePrimary() {
            skip();
            if (i >= t.length()) {
                throw new ExprException("Unexpected end of expression: " + t);
            }
            char c = t.charAt(i);
            if (c == '(') {
                i++;
                Node n = parseExpr();
                expect(')');
                return n;
            }
            if (c == '\'') {
                int end = t.indexOf('\'', i + 1);
                if (end < 0) {
                    throw new ExprException("Unterminated string in: " + t);
                }
                String s = t.substring(i + 1, end);
                i = end + 1;
                return new Str(s);
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < t.length() && Character.isDigit(t.charAt(i + 1)))) {
                int start = i;
                while (i < t.length() && (Character.isDigit(t.charAt(i)) || t.charAt(i) == '.')) {
                    i++;
                }
                try {
                    return new Num(new BigDecimal(t.substring(start, i)));
                } catch (NumberFormatException e) {
                    throw new ExprException("Bad number '" + t.substring(start, i) + "' in: " + t);
                }
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < t.length() && (Character.isLetterOrDigit(t.charAt(i)) || t.charAt(i) == '_')) {
                    i++;
                }
                String word = t.substring(start, i);
                String upper = word.toUpperCase(Locale.ROOT);
                if (upper.equals("TRUE")) {
                    return new Bool(true);
                }
                if (upper.equals("FALSE")) {
                    return new Bool(false);
                }
                skip();
                if (i < t.length() && t.charAt(i) == '(') {
                    String fn = word.toLowerCase(Locale.ROOT);
                    if (!FUNCTIONS.contains(fn)) {
                        throw new ExprException("Unknown function '" + word + "' in: " + t);
                    }
                    i++;
                    List<Node> args = new ArrayList<>();
                    args.add(parseExpr());
                    while (accept(',')) {
                        args.add(parseExpr());
                    }
                    expect(')');
                    if (fn.equals("abs") && args.size() != 1) {
                        throw new ExprException("abs() takes one argument in: " + t);
                    }
                    return new Call(fn, args);
                }
                return new Ident(word);
            }
            throw new ExprException("Unexpected '" + c + "' at position " + i + " in: " + t);
        }

        private void skip() {
            while (i < t.length() && Character.isWhitespace(t.charAt(i))) {
                i++;
            }
        }

        private boolean accept(char c) {
            skip();
            if (i < t.length() && t.charAt(i) == c) {
                i++;
                return true;
            }
            return false;
        }

        private void expect(char c) {
            if (!accept(c)) {
                throw new ExprException("Expected '" + c + "' at position " + i + " in: " + t);
            }
        }

        /** Consumes a whole-word, case-insensitive keyword if it is next. */
        private boolean keyword(String kw) {
            skip();
            int end = i + kw.length();
            if (end <= t.length() && t.regionMatches(true, i, kw, 0, kw.length())
                    && (end == t.length() || !(Character.isLetterOrDigit(t.charAt(end)) || t.charAt(end) == '_'))) {
                i = end;
                return true;
            }
            return false;
        }
    }
}
