import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main extends JFrame {

    // ==== Типы токенов ====
    enum TokenType { Identifier, Number, StringLiteral, CharLiteral, Operator }

    static class Token {
        final TokenType type;
        final String value;
        Token(TokenType t, String v) { type = t; value = v; }
        @Override public String toString() { return type + "(" + value + ")"; }
    }

    // ==== Состояние Джилба ====
    private int absoluteComplexity = 0;
    private int maxNesting = 0;
    private final Map<String, Integer> branchCounts = new LinkedHashMap<>();

    // ==== Состояние Холстеда (только операторы) ====
    private final Map<String, Integer> operators = new LinkedHashMap<>();
    private final Set<String> userTypes = new HashSet<>();
    private final Set<String> procedures = new HashSet<>();
    private final Set<String> returningFunctions = new HashSet<>();

    // ==== UI ====
    private final JTextField filePathField = new JTextField();
    private final JTextArea metricsArea = new JTextArea(14, 80);
    private final DefaultTableModel branchesModel  = new DefaultTableModel(new Object[]{"Ветвление", "Кол-во"}, 0);
    private final DefaultTableModel operatorsModel = new DefaultTableModel(new Object[]{"Оператор", "Кол-во"}, 0);

    // ==== Множества (ключевые слова Kotlin) ====
    private static final Set<String> KEYWORD_OPERATORS = new HashSet<>(Arrays.asList(
            "return", "break", "continue", "throw"
    ));

    private static final Set<String> COMPOUND_PART_KEYWORDS = new HashSet<>(Arrays.asList(
            "else", "catch", "finally"
    ));

    private static final Set<String> COMPOUND_STARTERS = new HashSet<>(Arrays.asList(
            "if", "when", "for", "while", "do", "try"
    ));

    private static final Set<String> SKIP_KEYWORDS = new HashSet<>(Arrays.asList(
            "package", "import", "class", "interface", "object", "enum",
            "fun", "val", "var", "typealias", "constructor", "init",
            "companion", "data", "sealed", "open", "abstract", "final",
            "override", "operator", "infix", "inline", "noinline",
            "crossinline", "reified", "suspend", "tailrec", "external",
            "annotation", "const", "lateinit", "inner",
            "public", "private", "protected", "internal",
            "true", "false", "null", "this", "super",
            "is", "as", "in", "out", "by", "where",
            "Unit", "Nothing", "Any",
            "get", "set", "field", "property", "receiver", "param",
            "setparam", "delegate", "file", "expect", "actual"
    ));

    private static final Set<String> TYPE_KEYWORDS = new HashSet<>(Arrays.asList(
            "Int", "Long", "Short", "Byte", "Float", "Double", "Boolean",
            "Char", "String", "Unit", "Any", "Nothing", "Number",
            "List", "MutableList", "Set", "MutableSet", "Map", "MutableMap",
            "Array", "IntArray", "LongArray", "DoubleArray", "BooleanArray",
            "CharArray", "ByteArray", "ShortArray", "FloatArray",
            "Pair", "Triple", "Sequence", "Iterable", "Collection",
            "Comparable", "Runnable", "Throwable", "Exception",
            "Error", "Function", "CharSequence"
    ));

    private static final String[] MULTI_CHAR_OPS = new String[]{
            "===", "!==", "<<=", ">>=", "..<",
            "==", "!=", ">=", "<=", "&&", "||", "<<", ">>",
            "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=",
            "++", "--", "->", "::", "?.", "?:", "!!", ".."
    };

    public Main() {
        super("Парсер Kotlin — метрики Джилба + операторы Холстеда");
        initUI();
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1200, 750);
        setLocationRelativeTo(null);
    }

    private void initUI() {
        JPanel top = new JPanel(new BorderLayout(6, 6));
        JButton openBtn = new JButton("Открыть файл…");
        filePathField.setEditable(false);
        top.add(openBtn, BorderLayout.WEST);
        top.add(filePathField, BorderLayout.CENTER);
        top.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JScrollPane brSp = new JScrollPane(new JTable(branchesModel));
        brSp.setBorder(BorderFactory.createTitledBorder("Ветвления"));
        JScrollPane opSp = new JScrollPane(new JTable(operatorsModel));
        opSp.setBorder(BorderFactory.createTitledBorder("Операторы"));

        JSplitPane tablesSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, brSp, opSp);
        tablesSplit.setResizeWeight(0.5);

        metricsArea.setEditable(false);
        metricsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JScrollPane metSp = new JScrollPane(metricsArea);
        metSp.setBorder(BorderFactory.createTitledBorder("Метрики"));

        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tablesSplit, metSp);
        mainSplit.setResizeWeight(0.55);

        setLayout(new BorderLayout());
        add(top, BorderLayout.NORTH);
        add(mainSplit, BorderLayout.CENTER);

        openBtn.addActionListener(e -> chooseFile());
    }

    private void chooseFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Выберите файл с кодом Kotlin");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "Kotlin Source (*.kt;*.kts)", "kt", "kts"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            analyzeFile(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    // ================== Анализ ==================

    public void analyzeFile(String path) {
        File f = new File(path);
        if (!f.exists()) {
            JOptionPane.showMessageDialog(this, "Файл не найден: " + path);
            return;
        }

        absoluteComplexity = 0;
        maxNesting = 0;
        branchCounts.clear();

        operators.clear();
        userTypes.clear();
        procedures.clear();
        returningFunctions.clear();

        String raw;
        try {
            raw = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Ошибка чтения: " + ex.getMessage());
            return;
        }

        filePathField.setText(path);

        String code = removeCommentsAndPackage(raw);

        // Готовим данные для обеих метрик одинаково
        collectUserTypes(code);
        classifyFunctions(code);

        List<String> bodies = extractFunctionBodies(code);
        String analysisCode = String.join("\n", bodies);

        // --- Джилба: по телам функций ---
        List<Token> jilbTokens = tokenize(analysisCode);
        analyzeBranchesRange(jilbTokens, 0, jilbTokens.size(), 0);

        // --- Холстед (только операторы): по телам функций ---
        List<Token> holstedTokens = tokenize(analysisCode);
        analyzeTokens(holstedTokens);

        displayResults();
    }

    private String removeCommentsAndPackage(String code) {
        StringBuilder out = new StringBuilder(code.length());
        int i = 0, n = code.length();
        int depth = 0;

        while (i < n) {
            char c = code.charAt(i);
            if (c == '/' && i + 1 < n && code.charAt(i + 1) == '*') { depth++; i += 2; continue; }
            if (depth > 0) {
                if (c == '*' && i + 1 < n && code.charAt(i + 1) == '/') { depth--; i += 2; }
                else i++;
                continue;
            }
            if (c == '/' && i + 1 < n && code.charAt(i + 1) == '/') {
                while (i < n && code.charAt(i) != '\n') i++;
                continue;
            }
            out.append(c);
            i++;
        }

        String[] lines = out.toString().split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String ln : lines) {
            String trimmed = ln.trim();
            if (trimmed.startsWith("package ") || trimmed.startsWith("import ")) sb.append("\n");
            else sb.append(ln).append('\n');
        }
        return sb.toString();
    }

    private void collectUserTypes(String code) {
        Pattern p = Pattern.compile(
                "\\b(?:data\\s+|sealed\\s+|open\\s+|abstract\\s+|internal\\s+|private\\s+|public\\s+|protected\\s+|annotation\\s+|enum\\s+)*" +
                        "(?:class|interface|object)\\s+([A-Za-z_]\\w*)");
        Matcher m = p.matcher(code);
        while (m.find()) userTypes.add(m.group(1));
    }

    private void classifyFunctions(String code) {
        Pattern p = Pattern.compile(
                "\\bfun\\b(?:\\s+<[^>]*>)?\\s+(?:[A-Za-z_]\\w*\\.)?([A-Za-z_]\\w*)\\s*\\(",
                Pattern.DOTALL);
        Matcher m = p.matcher(code);

        while (m.find()) {
            String name = m.group(1);
            if (SKIP_KEYWORDS.contains(name)) continue;

            int parenOpen = code.indexOf('(', m.end() - 1);
            if (parenOpen < 0) continue;
            int parenClose = findMatchingDelim(code, parenOpen, '(', ')');
            if (parenClose < 0) continue;

            int k = parenClose + 1;
            while (k < code.length() && Character.isWhitespace(code.charAt(k))) k++;

            if (k >= code.length()) { procedures.add(name); continue; }

            char c = code.charAt(k);

            if (c == '{') {
                procedures.add(name);
            } else if (c == '=') {
                returningFunctions.add(name);
            } else if (c == ':') {
                int t = k + 1;
                while (t < code.length() && Character.isWhitespace(code.charAt(t))) t++;
                int typeStart = t;
                while (t < code.length()) {
                    char tc = code.charAt(t);
                    if (Character.isLetterOrDigit(tc) || tc == '_' || tc == '.'
                            || tc == '<' || tc == '>' || tc == '?' || tc == ','
                            || tc == ' ') {
                        t++;
                    } else break;
                }
                String retType = code.substring(typeStart, t).trim();
                int lt = retType.indexOf('<');
                String baseType = lt > 0 ? retType.substring(0, lt).trim() : retType;
                if (baseType.endsWith("?")) baseType = baseType.substring(0, baseType.length() - 1);

                if (baseType.equals("Unit") || baseType.isEmpty()) {
                    procedures.add(name);
                } else {
                    returningFunctions.add(name);
                }
            } else {
                procedures.add(name);
            }
        }
    }

    private List<String> extractFunctionBodies(String code) {
        List<String> result = new ArrayList<>();
        Pattern p = Pattern.compile("\\bfun\\b[^\\n{;=]*?\\(", Pattern.MULTILINE);
        Matcher m = p.matcher(code);

        List<int[]> used = new ArrayList<>();
        while (m.find()) {
            int parenOpen = code.indexOf('(', m.start());
            if (parenOpen < 0) continue;
            int parenClose = findMatchingDelim(code, parenOpen, '(', ')');
            if (parenClose < 0) continue;

            int k = parenClose + 1;
            while (k < code.length()) {
                char c = code.charAt(k);
                if (c == '{' || c == '=') break;
                if (c == ';') { k = -1; break; }
                k++;
            }
            if (k < 0 || k >= code.length()) continue;

            if (code.charAt(k) == '{') {
                int braceEnd = findMatchingDelim(code, k, '{', '}');
                if (braceEnd > k) {
                    boolean skip = false;
                    for (int[] r : used) {
                        if (k >= r[0] && k <= r[1]) { skip = true; break; }
                    }
                    if (skip) continue;
                    used.add(new int[]{k, braceEnd});
                    result.add(code.substring(k + 1, braceEnd));
                }
            } else {
                int end = code.indexOf('\n', k);
                if (end < 0) end = code.length();
                result.add(code.substring(k + 1, end));
            }
        }
        return result;
    }

    private int findMatchingDelim(String code, int openPos, char open, char close) {
        int depth = 0;
        for (int i = openPos; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    // ================== Токенизация ==================

    private List<Token> tokenize(String code) {
        List<Token> tokens = new ArrayList<>();
        int i = 0, n = code.length();
        while (i < n) {
            char c = code.charAt(i);

            if (Character.isWhitespace(c)) { i++; continue; }

            if (c == '"' && i + 2 < n && code.charAt(i + 1) == '"' && code.charAt(i + 2) == '"') {
                int start = i;
                i += 3;
                while (i + 2 < n && !(code.charAt(i) == '"' && code.charAt(i + 1) == '"' && code.charAt(i + 2) == '"')) i++;
                i = Math.min(i + 3, n);
                tokens.add(new Token(TokenType.StringLiteral, code.substring(start, i)));
                continue;
            }
            if (c == '"') {
                int start = i; i++;
                while (i < n && code.charAt(i) != '"') {
                    if (code.charAt(i) == '\\' && i + 1 < n) i++;
                    i++;
                }
                if (i < n) i++;
                tokens.add(new Token(TokenType.StringLiteral, code.substring(start, i)));
                continue;
            }
            if (c == '\'') {
                int start = i; i++;
                while (i < n && code.charAt(i) != '\'') {
                    if (code.charAt(i) == '\\' && i + 1 < n) i++;
                    i++;
                }
                if (i < n) i++;
                tokens.add(new Token(TokenType.CharLiteral, code.substring(start, i)));
                continue;
            }
            if (Character.isDigit(c)) {
                int start = i;
                while (i < n) {
                    char ci = code.charAt(i);
                    if (Character.isLetterOrDigit(ci) || ci == '.' || ci == '_') i++;
                    else break;
                }
                tokens.add(new Token(TokenType.Number, code.substring(start, i)));
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(code.charAt(i)) || code.charAt(i) == '_')) i++;
                tokens.add(new Token(TokenType.Identifier, code.substring(start, i)));
                continue;
            }

            boolean matched = false;
            for (String op : MULTI_CHAR_OPS) {
                if (i + op.length() <= n && code.startsWith(op, i)) {
                    tokens.add(new Token(TokenType.Operator, op));
                    i += op.length();
                    matched = true;
                    break;
                }
            }
            if (matched) continue;

            tokens.add(new Token(TokenType.Operator, String.valueOf(c)));
            i++;
        }
        return tokens;
    }

    // ================== Джилба ==================

    private void analyzeBranchesRange(List<Token> tokens, int from, int to, int startNesting) {
        Deque<Integer> nestingStack = new ArrayDeque<>();
        int currentNesting = startNesting;

        for (int i = from; i < to; i++) {
            Token t = tokens.get(i);

            if (t.type == TokenType.Operator) {
                if (t.value.equals("{")) {
                    nestingStack.push(currentNesting);
                    currentNesting = currentNesting + 1;
                } else if (t.value.equals("}")) {
                    if (!nestingStack.isEmpty()) currentNesting = nestingStack.pop();
                } else if (t.value.equals("?:")) {
                    addBranch("?:");
                    absoluteComplexity++;
                    maxNesting = Math.max(maxNesting, currentNesting);
                }
                continue;
            }

            if (t.type != TokenType.Identifier) continue;

            switch (t.value) {
                case "else": break;
                case "when": i = processWhenCases(tokens, i, currentNesting); break;
                case "for":
                    addBranch("for"); absoluteComplexity++;
                    maxNesting = Math.max(maxNesting, currentNesting); break;
                case "if":
                    addBranch("if"); absoluteComplexity++;
                    maxNesting = Math.max(maxNesting, currentNesting); break;
                case "while":
                    if (isWhileOfDoWhile(tokens, i)) break;
                    addBranch("while"); absoluteComplexity++;
                    maxNesting = Math.max(maxNesting, currentNesting); break;
                case "do":
                    addBranch("do-while"); absoluteComplexity++;
                    maxNesting = Math.max(maxNesting, currentNesting); break;
                case "try": break;
            }
        }
    }

    private int processWhenCases(List<Token> tokens, int whenIdx, int baseNesting) {
        int n = tokens.size();
        int j = whenIdx + 1;

        if (j < n && isOp(tokens.get(j), "(")) {
            int close = findCloseParen(tokens, j);
            if (close > 0) j = close + 1;
        }
        while (j < n && !isOp(tokens.get(j), "{")) j++;
        if (j >= n) return whenIdx;
        int closeBrace = findCloseBraceTokens(tokens, j);
        if (closeBrace < 0) return whenIdx;

        // when стоит на уровне baseNesting. Его case'ы идут подряд:
        // case[0] — на baseNesting, case[1] — на baseNesting+1, и т.д.
        int caseBaseLevel = baseNesting;

        int caseIndex = 0;
        int k = j + 1;
        while (k < closeBrace) {
            Token tk = tokens.get(k);
            if (tk.type == TokenType.Operator && tk.value.equals("->")) {
                boolean isElse = false;
                if (k > j + 1) {
                    Token prev = tokens.get(k - 1);
                    if (prev.type == TokenType.Identifier && prev.value.equals("else")) isElse = true;
                }

                int caseStart = k + 1;
                int caseEnd = closeBrace;
                int depth = 0;
                for (int m = caseStart; m < closeBrace; m++) {
                    Token tm = tokens.get(m);
                    if (tm.type == TokenType.Operator) {
                        if (tm.value.equals("{") || tm.value.equals("(") || tm.value.equals("[")) depth++;
                        else if (tm.value.equals("}") || tm.value.equals(")") || tm.value.equals("]")) depth--;
                        else if (tm.value.equals("->") && depth == 0) { caseEnd = m; break; }
                    }
                }

                if (isElse) {
                    int elseLevel = (caseIndex > 0)
                            ? caseBaseLevel + caseIndex - 1
                            : caseBaseLevel;
                    analyzeBranchesRange(tokens, caseStart, caseEnd, elseLevel);
                } else {
                    int caseLevel = caseBaseLevel + caseIndex;
                    addBranch("when-case");
                    absoluteComplexity++;
                    maxNesting = Math.max(maxNesting, caseLevel);
                    analyzeBranchesRange(tokens, caseStart, caseEnd, caseLevel + 1);
                    caseIndex++;
                }
                k = caseEnd;
            } else {
                k++;
            }
        }
        return closeBrace;
    }

    private boolean isWhileOfDoWhile(List<Token> tokens, int whileIdx) {
        if (whileIdx <= 0) return false;
        int depth = 0;
        for (int m = whileIdx - 1; m >= 0; m--) {
            Token tm = tokens.get(m);
            if (isOp(tm, "}")) depth++;
            else if (isOp(tm, "{")) {
                depth--;
                if (depth < 0) {
                    if (m > 0 && tokens.get(m - 1).type == TokenType.Identifier
                            && tokens.get(m - 1).value.equals("do")) return true;
                    return false;
                }
            } else if (depth == 0 && tm.type == TokenType.Identifier
                    && tm.value.equals("do")) {
                return true;
            } else if (depth == 0 && isOp(tm, ";")) {
                return false;
            }
        }
        return false;
    }

    // ================== Холстед: только операторы ==================

    private void analyzeTokens(List<Token> tokens) {
        int i = 0;
        while (i < tokens.size()) {
            Token t = tokens.get(i);

            if (t.type == TokenType.Identifier) {
                switch (t.value) {
                    case "if":
                        if (hasElseAfter(tokens, i)) addOperator("if ... else");
                        else addOperator("if");
                        i++; continue;
                    case "when":
                        addOperator("when ... else");
                        i++; continue;
                    case "for":
                        addOperator("for()");
                        i++; continue;
                    case "while":
                        addOperator("while()");
                        i++; continue;
                    case "do":
                        addOperator("do ... while()");
                        i++; continue;
                    case "try":
                        addOperator("try ... catch ... finally");
                        i++; continue;
                }

                if (COMPOUND_PART_KEYWORDS.contains(t.value)) { i++; continue; }
                if (KEYWORD_OPERATORS.contains(t.value)) { addOperator(t.value); i++; continue; }
                if (SKIP_KEYWORDS.contains(t.value)) { i++; continue; }

                if (isFunctionCall(tokens, i) || isTrailingLambdaCall(tokens, i)) {
                    addOperator(t.value + "()");
                    i++;
                    if (i < tokens.size() && isOp(tokens.get(i), "(")) i++;
                    continue;
                }

                i++;
                continue;
            }

            if (t.type == TokenType.Number
                    || t.type == TokenType.StringLiteral
                    || t.type == TokenType.CharLiteral) {
                i++;
                continue;
            }

            if (t.type == TokenType.Operator) {
                handleOperatorToken(tokens, i);
                i++;
                continue;
            }

            i++;
        }
    }

    private boolean isFunctionCall(List<Token> tokens, int idx) {
        int j = idx + 1;
        if (j >= tokens.size()) return false;

        if (isOp(tokens.get(j), "<")) {
            int depth = 0;
            while (j < tokens.size()) {
                Token tk = tokens.get(j);
                if (tk.type == TokenType.Operator && tk.value.equals("<")) depth++;
                else if (tk.type == TokenType.Operator && tk.value.equals(">")) {
                    depth--;
                    if (depth == 0) { j++; break; }
                } else if (tk.type == TokenType.Operator && tk.value.equals(";")) {
                    return false;
                }
                j++;
            }
        }
        return j < tokens.size() && isOp(tokens.get(j), "(");
    }

    private boolean isTrailingLambdaCall(List<Token> tokens, int idx) {
        int j = idx + 1;
        return j < tokens.size() && isOp(tokens.get(j), "{");
    }

    private boolean hasElseAfter(List<Token> tokens, int ifIdx) {
        int openParen = ifIdx + 1;
        if (openParen >= tokens.size()) return false;
        if (!isOp(tokens.get(openParen), "(")) return false;
        int closeParen = findCloseParen(tokens, openParen);
        if (closeParen < 0) return false;

        int j = closeParen + 1;
        if (j >= tokens.size()) return false;

        if (isOp(tokens.get(j), "{")) {
            int end = findCloseBraceTokens(tokens, j);
            if (end < 0) return false;
            j = end + 1;
        } else {
            int depthBrace = 0, depthParen = 0;
            while (j < tokens.size()) {
                Token tk = tokens.get(j);
                if (tk.type == TokenType.Operator) {
                    switch (tk.value) {
                        case "{": depthBrace++; break;
                        case "}": if (depthBrace == 0) { j = -1; } else depthBrace--; break;
                        case "(": depthParen++; break;
                        case ")": depthParen--; break;
                        case ";": if (depthBrace == 0 && depthParen == 0) { j++; } break;
                    }
                    if (j < 0) break;
                }
                if (j >= 0 && j < tokens.size()) {
                    Token c = tokens.get(j);
                    if (c.type == TokenType.Operator && c.value.equals(";") && depthBrace == 0 && depthParen == 0) {
                        j++;
                        break;
                    }
                }
                j++;
            }
        }

        return j >= 0 && j < tokens.size()
                && tokens.get(j).type == TokenType.Identifier
                && tokens.get(j).value.equals("else");
    }

    private boolean isOp(Token t, String v) {
        return t.type == TokenType.Operator && t.value.equals(v);
    }

    private int findCloseParen(List<Token> tokens, int openIdx) {
        if (openIdx >= tokens.size() || !isOp(tokens.get(openIdx), "(")) return -1;
        int depth = 0;
        for (int i = openIdx; i < tokens.size(); i++) {
            if (isOp(tokens.get(i), "(")) depth++;
            else if (isOp(tokens.get(i), ")")) { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    private int findCloseBraceTokens(List<Token> tokens, int openIdx) {
        if (openIdx >= tokens.size() || !isOp(tokens.get(openIdx), "{")) return -1;
        int depth = 0;
        for (int i = openIdx; i < tokens.size(); i++) {
            if (isOp(tokens.get(i), "{")) depth++;
            else if (isOp(tokens.get(i), "}")) { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    private void handleOperatorToken(List<Token> tokens, int idx) {
        String v = tokens.get(idx).value;

        if (v.equals(")") || v.equals("}") || v.equals("]")) return;

        if (v.equals("(")) {
            if (isTypeCast(tokens, idx)) addOperator("(type)");
            else addOperator("()");
            return;
        }
        if (v.equals("{")) { addOperator("{}"); return; }
        if (v.equals("[")) { addOperator("[]"); return; }

        if (v.equals("?:")) { addOperator("?:"); return; }
        if (v.equals(":"))  { addOperator(":");  return; }

        if (v.equals("-")) { addOperator(isUnary(tokens, idx) ? "-(unary)" : "-"); return; }
        if (v.equals("+")) { addOperator(isUnary(tokens, idx) ? "+(unary)" : "+"); return; }
        if (v.equals("!")) { addOperator("!"); return; }

        if (v.equals(",")) { addOperator(","); return; }
        if (v.equals(";")) { addOperator(";"); return; }
        if (v.equals(".")) { addOperator("."); return; }
        if (v.equals("..")) { addOperator(".."); return; }
        if (v.equals("..<")) { addOperator("..<"); return; }
        if (v.equals("->")) { addOperator("->"); return; }
        if (v.equals("::")) { addOperator("::"); return; }
        if (v.equals("?.")) { addOperator("?."); return; }
        if (v.equals("!!")) { addOperator("!!"); return; }

        addOperator(v);
    }

    private boolean isUnary(List<Token> tokens, int idx) {
        if (idx == 0) return true;
        Token prev = tokens.get(idx - 1);
        if (prev.type == TokenType.Operator) {
            String pv = prev.value;
            if (pv.equals(")") || pv.equals("]") || pv.equals("}")) return false;
            if (pv.equals("++") || pv.equals("--")) return false;
            return true;
        }
        if (prev.type == TokenType.Identifier) {
            String pv = prev.value;
            if (pv.equals("return") || pv.equals("throw") || pv.equals("in")
                    || pv.equals("is") || pv.equals("as")) return true;
            return false;
        }
        return false;
    }

    private boolean isTypeCast(List<Token> tokens, int openIdx) {
        if (openIdx + 2 >= tokens.size()) return false;
        int k = openIdx + 1;

        Token first = tokens.get(k);
        if (first.type != TokenType.Identifier) return false;

        String typeName = first.value;
        boolean known = TYPE_KEYWORDS.contains(typeName)
                || userTypes.contains(typeName)
                || (!typeName.isEmpty() && Character.isUpperCase(typeName.charAt(0)));
        if (!known) return false;
        k++;

        if (k < tokens.size() && isOp(tokens.get(k), "?")) k++;

        return k < tokens.size() && isOp(tokens.get(k), ")");
    }

    // ================== Накопление ==================

    private void addBranch(String name) {
        branchCounts.merge(name, 1, Integer::sum);
    }

    private void addOperator(String name) {
        operators.merge(name, 1, Integer::sum);
    }

    // ================== Вывод ==================

    private void displayResults() {
        // --- Ветвления (Джилба) ---
        branchesModel.setRowCount(0);
        List<Map.Entry<String, Integer>> bl = new ArrayList<>(branchCounts.entrySet());
        bl.sort((a, b) -> {
            int c = Integer.compare(b.getValue(), a.getValue());
            return c != 0 ? c : a.getKey().compareTo(b.getKey());
        });
        for (Map.Entry<String, Integer> e : bl)
            branchesModel.addRow(new Object[]{e.getKey(), e.getValue()});
        branchesModel.addRow(new Object[]{"ИТОГО", absoluteComplexity});

        // --- Операторы (Холстед) ---
        List<Map.Entry<String, Integer>> opList = new ArrayList<>(operators.entrySet());
        opList.sort((a, b) -> {
            int c = Integer.compare(b.getValue(), a.getValue());
            return c != 0 ? c : a.getKey().compareTo(b.getKey());
        });

        int n1 = opList.size();
        int N1 = opList.stream().mapToInt(Map.Entry::getValue).sum();

        operatorsModel.setRowCount(0);
        for (Map.Entry<String, Integer> e : opList)
            operatorsModel.addRow(new Object[]{e.getKey(), e.getValue()});
        operatorsModel.addRow(new Object[]{"ИТОГО  η1 = " + n1, N1});

        // --- Метрики ---
        StringBuilder sb = new StringBuilder();
        sb.append("МЕТРИКИ ДЖИЛБА\n");
        sb.append("─────────────────────────────────────────────\n");
        sb.append(String.format("  Абсолютная сложность (abs)        = %d%n", absoluteComplexity));
        sb.append(String.format("  Количество операторов (ops)       = %d%n", N1));
        double relative = N1 > 0 ? (double) absoluteComplexity / N1 : 0.0;
        sb.append(String.format("  Относительная сложность (rel)     = %.4f%n", relative));
        sb.append(String.format("  Максимальный уровень вложенности  = %d%n", maxNesting));
        sb.append('\n');
        sb.append("ОПЕРАТОРЫ (Холстед)\n");
        sb.append(String.format("  η1  — словарь операторов          = %d%n", n1));
        sb.append(String.format("  N1  — всего операторов            = %d%n", N1));

        metricsArea.setText(sb.toString());
    }

    // ================== Точка входа ==================

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            Main frame = new Main();
            frame.setVisible(true);

            File f = new File("..\\SPO_2A\\src\\test1.kt");
            if (f.exists()) frame.analyzeFile(f.getAbsolutePath());
        });
    }
}