package com.querysense.backend.schema.ddl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SqlStatementSplitterTest
 *
 * The cases here are drawn from what a real mysqldump file actually
 * contains, not from what a tidy hand-written schema looks like. Every one
 * of them is a way a naive split(";") gets the wrong answer.
 */
class SqlStatementSplitterTest {

    @Test
    @DisplayName("splits on semicolons and drops empty fragments")
    void splitsOnSemicolons() {
        List<String> parts = SqlStatementSplitter.split("CREATE TABLE a (id INT);; CREATE TABLE b (id INT);");
        assertThat(parts).containsExactly("CREATE TABLE a (id INT)", "CREATE TABLE b (id INT)");
    }

    @Test
    @DisplayName("a semicolon inside a single-quoted string does not split")
    void ignoresSemicolonInString() {
        List<String> parts = SqlStatementSplitter.split("INSERT INTO a VALUES ('x;y'); CREATE TABLE b (id INT);");
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0)).isEqualTo("INSERT INTO a VALUES ('x;y')");
    }

    @Test
    @DisplayName("a semicolon inside backticks does not split")
    void ignoresSemicolonInBackticks() {
        List<String> parts = SqlStatementSplitter.split("CREATE TABLE `we;ird` (id INT);");
        assertThat(parts).containsExactly("CREATE TABLE `we;ird` (id INT)");
    }

    @Test
    @DisplayName("line comments are stripped and their semicolons ignored")
    void stripsLineComments() {
        String sql = "-- a comment; with a semicolon\nCREATE TABLE a (id INT);\n# hash comment;\n";
        assertThat(SqlStatementSplitter.split(sql)).containsExactly("CREATE TABLE a (id INT)");
    }

    @Test
    @DisplayName("block comments are stripped, including mysqldump's executable-comment syntax")
    void stripsBlockComments() {
        String sql = "/*!40101 SET NAMES utf8 */;\nCREATE TABLE a (id INT);";
        assertThat(SqlStatementSplitter.split(sql)).containsExactly("CREATE TABLE a (id INT)");
    }

    @Test
    @DisplayName("an escaped quote inside a string does not end the string")
    void handlesEscapedQuote() {
        List<String> parts = SqlStatementSplitter.split(
                "INSERT INTO a VALUES ('it\\'s; fine'); CREATE TABLE b (id INT);");
        assertThat(parts).hasSize(2);
    }

    @Test
    @DisplayName("a trailing statement with no semicolon is still returned")
    void keepsUnterminatedTail() {
        assertThat(SqlStatementSplitter.split("CREATE TABLE a (id INT)"))
                .containsExactly("CREATE TABLE a (id INT)");
    }

    @Test
    @DisplayName("null and blank input yield nothing")
    void handlesEmptyInput() {
        assertThat(SqlStatementSplitter.split(null)).isEmpty();
        assertThat(SqlStatementSplitter.split("   \n  ")).isEmpty();
    }
}
