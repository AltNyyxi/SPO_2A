import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.List;

public class Main extends JFrame {

    // ==== Типы токенов ====
    enum TokenType { Identifier, Number, StringLiteral, CharLiteral, Operator }

    static class Token {
        final TokenType type;
        final String value;
        Token(TokenType t, String v) { type = t; value = v; }
        @Override public String toString() { return type + "(" + value + ")"; }
    }

    // ==== Состояние ====
    private int absoluteComplexity = 0;
    private int operatorCount = 0;
    private int maxNesting = 0;
    private final Map<String, Integer> branchCounts = new LinkedHashMap<>();

    // ==== UI ====
    private final JTextField filePathField = new JTextField();
    private final JTextArea metricsArea = new JTextArea(14, 60);
    private final DefaultTableModel branchesModel =
            new DefaultTableModel(new Object[]{"Ветвление", "Кол-во"}, 0);

    // ==== Многосимвольные операторы ====
    private static final String[] MULTI_CHAR_OPS = new String[]{
            "===", "!==", "<<=", ">>=", "..<",
            "==", "!=", ">=", "<=", "&&", "||", "<<", ">>",
            "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=",
            "++", "--", "->", "::", "?.", "?:", "!!", ".."
    };

    public Main() {
        super("Парсер Kotlin — метрики Джилба");
        initUI();
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1100, 700);
        setLocationRelativeTo(null);
    }

    private void initUI() {
        JPanel top = new JPanel(new BorderLayout(6, 6));
        JButton openBtn = new JButton("Открыть файл…");
        filePathField.setEditable(false);
        top.add(openBtn, BorderLayout.WEST);
        top.add(filePathField, BorderLayout.CENTER);
        top.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JTable branchesTable = new JTable(branchesModel);
        JScrollPane brSp = new JScrollPane(branchesTable);
        brSp.setBorder(BorderFactory.createTitledBorder("Ветвления"));

        metricsArea.setEditable(false);
        metricsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JScrollPane metSp = new JScrollPane(metricsArea);
        metSp.setBorder(BorderFactory.createTitledBorder("Метрики Джилба"));

        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, brSp, metSp);
        mainSplit.setResizeWeight(0.5);

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
        operatorCount = 0;
        maxNesting = 0;
        branchCounts.clear();

        String raw;
        try {
            raw = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Ошибка чтения: " + ex.getMessage());
            return;
        }

        filePathField.setText(path);

        String code = removeCommentsAndPackage(raw);
        List<Token> tokens = tokenize(code);

        // Подсчёт операторов (все Operator-токены, кроме ';')
        for (Token t : tokens) {
            if (t.type == TokenType.Operator && !t.value.equals(";")) {
                operatorCount++;
            }
        }

        // Анализ ветвлений
        analyzeBranchesRange(tokens, 0, tokens.size(), 0);

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

    // ================== Токенизация ==================

    private List<Token> tokenize(String code) {
        List<Token> tokens = new ArrayList<>();
        int i = 0, n = code.length();
        while (i < n) {
            char c = code.charAt(i);

            if (Character.isWhitespace(c)) { i++; continue; }

            if (c == '"' && i + 2 < n && code.charAt(i + 1) == '"' && code.charAt(i + 2) == '"') {
                int start = i; i += 3;
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

    // ================== Анализ ветвлений ==================

    /**
     * Линейный обход диапазона [from, to) с начальной вложенностью startNesting.
     *
     * Правила:
     *  - if (в т.ч. else if)     → "if",        +1 к abs
     *      сам if на уровне currentNesting, тело — currentNesting+1,
     *      else if / else — тот же уровень (currentNesting)
     *  - when                    → НЕ считается (ни строки, ни abs)
     *      case[i] на уровне base+i, тело case — base+i+1,
     *      else -> (default) НЕ считается и НЕ занимает позицию
     *  - for                     → "for",       +1 к abs, тело +1
     *  - while                   → "while",     +1 к abs, тело +1
     *      while от do-while пропускается
     *  - do-while                → "do-while",  +1 к abs, тело +1
     *  - ?: (Элвис)              → "?:",        +1 к abs,
     *      правая часть — на уровень глубже
     *  - catch                   → НЕ считается
     *  - try / finally / else    → не считаются
     */
    private void analyzeBranchesRange(List<Token> tokens, int from, int to, int startNesting) {
        Deque<Integer> nestingStack = new ArrayDeque<>();
        int currentNesting = startNesting;

        for (int i = from; i < to; i++) {
            Token t = tokens.get(i);

            if (t.type == TokenType.Operator) {
                if (t.value.equals("{")) {
                    nestingStack.push(currentNesting);
                } else if (t.value.equals("}")) {
                    if (!nestingStack.isEmpty()) currentNesting = nestingStack.pop();
                } else if (t.value.equals("?:")) {
                    // Элвис-оператор — ветвление: если слева null, идём вправо.
                    addBranch("?:");
                    absoluteComplexity++;
                    // Правая часть — как тело if — на уровень глубже.
                    maxNesting = Math.max(maxNesting, currentNesting + 1);
                }
                continue;
            }

            if (t.type != TokenType.Identifier) continue;

            switch (t.value) {
                case "else": {
                    // else / else if — не увеличивают вложенность.
                    break;
                }
                case "when": {
                    i = processWhenCases(tokens, i, currentNesting);
                    break;
                }
                case "for": {
                    addBranch("for");
                    absoluteComplexity++;
                    maxNesting = Math.max(maxNesting, currentNesting);
                    currentNesting = currentNesting + 1;   // <-- инкремент на for
                    break;
                }
                case "if": {
                    addBranch("if");
                    absoluteComplexity++;
                    maxNesting = Math.max(maxNesting, currentNesting);
                    currentNesting = currentNesting + 1;   // <-- инкремент на if
                    break;
                }
                case "while": {
                    if (isWhileOfDoWhile(tokens, i)) break;
                    addBranch("while");
                    absoluteComplexity++;
                    maxNesting = Math.max(maxNesting, currentNesting);
                    maxNesting = Math.max(maxNesting, currentNesting + 1);
                    break;
                }
                case "do": {
                    addBranch("do-while");
                    absoluteComplexity++;
                    maxNesting = Math.max(maxNesting, currentNesting);
                    maxNesting = Math.max(maxNesting, currentNesting + 1);
                    break;
                }
                case "try": {
                    // try — не ветвление; вложенность не растёт.
                    break;
                }
            }
        }
    }

    /**
     * when: каждый -> (кроме else ->) — это вложенный if.
     * case[0] на уровне baseNesting, case[1] на baseNesting+1, и т.д.
     * else -> (default) НЕ считается и НЕ занимает позицию в цепочке.
     * Тело case[i] — на уровне baseNesting + i + 1.
     * Возвращает индекс закрывающей } (чтобы внешний цикл продолжил с неё).
     */
    private int processWhenCases(List<Token> tokens, int whenIdx, int baseNesting) {
        int n = tokens.size();
        int j = whenIdx + 1;
        // Пропускаем (subject), если есть
        if (j < n && isOp(tokens.get(j), "(")) {
            int close = findCloseParen(tokens, j, n);
            if (close > 0) j = close + 1;
        }
        // Ищем {
        while (j < n && !isOp(tokens.get(j), "{")) j++;
        if (j >= n) return whenIdx;
        int closeBrace = findCloseBraceTokens(tokens, j, n);
        if (closeBrace < 0) return whenIdx;

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

                // Границы тела case — до следующего -> на том же уровне или до }
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
                    // else -> (default): НЕ считаем, НЕ увеличиваем caseIndex.
                    int elseLevel = (caseIndex > 0)
                            ? baseNesting + caseIndex - 1
                            : baseNesting;
                    analyzeBranchesRange(tokens, caseStart, caseEnd, elseLevel);
                } else {
                    int caseLevel = baseNesting + caseIndex;
                    int caseBodyLevel = caseLevel + 1;

                    addBranch("when-case");
                    absoluteComplexity++;
                    maxNesting = Math.max(maxNesting, caseLevel);

                    analyzeBranchesRange(tokens, caseStart, caseEnd, caseBodyLevel);
                    caseIndex++;
                }
                k = caseEnd;
            } else {
                k++;
            }
        }
        return closeBrace;
    }

    /** Проверяет, является ли while частью do-while. */
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

    private boolean isOp(Token t, String v) {
        return t.type == TokenType.Operator && t.value.equals(v);
    }

    private int findCloseParen(List<Token> tokens, int openIdx, int to) {
        if (openIdx >= to || !isOp(tokens.get(openIdx), "(")) return -1;
        int depth = 0;
        for (int i = openIdx; i < to; i++) {
            if (isOp(tokens.get(i), "(")) depth++;
            else if (isOp(tokens.get(i), ")")) { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    private int findCloseBraceTokens(List<Token> tokens, int openIdx, int to) {
        if (openIdx >= to || !isOp(tokens.get(openIdx), "{")) return -1;
        int depth = 0;
        for (int i = openIdx; i < to; i++) {
            if (isOp(tokens.get(i), "{")) depth++;
            else if (isOp(tokens.get(i), "}")) { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    // ================== Вывод ==================

    private void addBranch(String name) {
        branchCounts.merge(name, 1, Integer::sum);
    }

    private void displayResults() {
        branchesModel.setRowCount(0);
        List<Map.Entry<String, Integer>> list = new ArrayList<>(branchCounts.entrySet());
        list.sort((a, b) -> {
            int c = Integer.compare(b.getValue(), a.getValue());
            return c != 0 ? c : a.getKey().compareTo(b.getKey());
        });
        for (Map.Entry<String, Integer> e : list) {
            branchesModel.addRow(new Object[]{e.getKey(), e.getValue()});
        }
        branchesModel.addRow(new Object[]{"ИТОГО", absoluteComplexity});

        double relative = (operatorCount > 0)
                ? (double) absoluteComplexity / operatorCount
                : 0.0;

        StringBuilder sb = new StringBuilder();
        sb.append("МЕТРИКИ ДЖИЛБА\n");
        sb.append("─────────────────────────────────────────────\n");
        sb.append(String.format("  Абсолютная сложность (abs)       = %d%n", absoluteComplexity));
        sb.append(String.format("  Количество операторов (ops)      = %d%n", operatorCount));
        sb.append(String.format("  Относительная сложность (rel)    = %.4f%n", relative));
        sb.append(String.format("  Максимальный уровень вложенности = %d%n", maxNesting));
        sb.append("─────────────────────────────────────────────\n");
        sb.append("Пояснение:\n");
        sb.append("  abs = if (в т.ч. else if) + when-case + for\n");
        sb.append("        + while + do-while + ?:\n");
        sb.append("  rel = abs / ops  (ops — все операторы, кроме ';')\n");
        sb.append("  Вложенность нумеруется с 0.\n");
        sb.append("  when сам по себе не считается — только его case'ы.\n");
        sb.append("  else -> в when — это default, не считается.\n");
        sb.append("  else if — отдельный if (в таблице учитывается, вложенность не растёт).\n");
        sb.append("  catch не считается ветвлением.\n");
        sb.append("  ?: (Элвис) — считается ветвлением.\n");

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