package pt.flawless.fdatabase;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqlStatementSplitterTest {

    @Test
    void split_twoStatementsOnSeparateLines() {
        List<String> result = SqlStatementSplitter.split("CREATE TABLE a (id INT);\nCREATE TABLE b (id INT);\n");
        assertEquals(Arrays.asList("CREATE TABLE a (id INT)", "CREATE TABLE b (id INT)"), result);
    }

    @Test
    void split_handlesCrlf() {
        List<String> result = SqlStatementSplitter.split("CREATE TABLE a (\r\n  id INT\r\n);\r\nCREATE TABLE b (id INT);\r\n");
        assertEquals(2, result.size());
        for (String statement : result) {
            assertFalse(statement.contains("\r"), "statement must not contain CR: " + statement);
            assertFalse(statement.endsWith(";"));
        }
        assertEquals("CREATE TABLE a (\n  id INT\n)", result.get(0));
    }

    @Test
    void split_semicolonMidLineDoesNotSplit() {
        List<String> result = SqlStatementSplitter.split("INSERT INTO t (v) VALUES ('a;b');\n");
        assertEquals(Arrays.asList("INSERT INTO t (v) VALUES ('a;b')"), result);
    }

    @Test
    void split_ignoresEmptyStatementsAndLineComments() {
        String sql = "-- header comment\n;\n\nCREATE TABLE a (id INT);\n-- another\n   ;  \n;\n";
        assertEquals(Arrays.asList("CREATE TABLE a (id INT)"), SqlStatementSplitter.split(sql));
    }

    @Test
    void split_stripsBom() {
        List<String> result = SqlStatementSplitter.split("\uFEFFCREATE TABLE a (id INT);\n");
        assertEquals(Arrays.asList("CREATE TABLE a (id INT)"), result);
    }

    @Test
    void split_lastStatementWithoutSemicolon() {
        List<String> result = SqlStatementSplitter.split("CREATE TABLE a (id INT);\nCREATE TABLE b (id INT)\n");
        assertEquals(Arrays.asList("CREATE TABLE a (id INT)", "CREATE TABLE b (id INT)"), result);
    }
}
