package pt.flawless.fdatabase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Splits a SQL script into individual statements with a simple line-by-line pass.
 *
 * <p>A statement ends with a {@code ;} that is the last non-blank character of a line. A
 * {@code ;} in the middle of a line (for example inside a string literal) does not split.
 * Lines that consist only of a {@code --} comment are ignored everywhere (including inside a
 * multi-line statement), empty statements are discarded,
 * {@code \r\n} line endings are normalized to {@code \n} and a leading UTF-8 BOM is stripped.
 * The last statement may omit the final {@code ;}.
 *
 * <p>Trailing comments: a line such as {@code CREATE TABLE t (id INT); -- note} does not end in
 * {@code ;} (the comment does), so it is NOT split: it is kept as-is and merges with the following
 * statement(s) up to the next line that ends in {@code ;}. Nothing may follow the {@code ;} on the same line.
 *
 * <p>Limitations: stored procedures, triggers and the {@code DELIMITER} command are not
 * supported, and a line ending in {@code ;} inside a multi-line string literal or block comment
 * would be treated as a statement end.
 */
public final class SqlStatementSplitter {

    private SqlStatementSplitter() {
    }

    public static List<String> split(String sql) {
        if (sql == null || sql.isEmpty()) {
            return Collections.emptyList();
        }
        String text = sql;
        if (text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        text = text.replace("\r\n", "\n").replace('\r', '\n');

        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (line.trim().startsWith("--")) {
                continue;
            }
            String stripped = stripTrailingWhitespace(line);
            if (stripped.endsWith(";")) {
                current.append(stripped, 0, stripped.length() - 1);
                flush(current, statements);
            } else {
                current.append(line).append('\n');
            }
        }
        flush(current, statements);
        return Collections.unmodifiableList(statements);
    }

    private static void flush(StringBuilder current, List<String> statements) {
        String statement = current.toString().trim();
        current.setLength(0);
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
    }

    private static String stripTrailingWhitespace(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end);
    }
}
