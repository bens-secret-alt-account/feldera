package org.dbsp.sqlCompiler.compiler.sql.streaming;

import org.dbsp.sqlCompiler.compiler.sql.StreamingTestBase;
import org.dbsp.sqlCompiler.compiler.sql.tools.CompilerCircuitStream;
import org.junit.Test;

/** Expected outputs are what the same program computes without LATENESS.
 * No input violates lateness. */
public class LeadLatenessTests extends StreamingTestBase {
    @Test
    public void leadGroupBy() {
        String sql = """
                CREATE TABLE t (ts BIGINT NOT NULL LATENESS 10, x BIGINT NOT NULL);
                CREATE LOCAL VIEW V AS SELECT ts, x, LEAD(x) OVER (ORDER BY ts) AS nx FROM t;
                CREATE VIEW C AS SELECT ts, COUNT(*) AS c, SUM(nx) AS s FROM V GROUP BY ts;""";
        CompilerCircuitStream ccs = this.getCCS(sql).compactAfterEachStep();
        ccs.step("INSERT INTO t VALUES(0, 1);", """
                 ts | c | s | weight
                ---------------------
                 0  | 1 |NULL| 1""");
        ccs.step("INSERT INTO t VALUES(100, 2);", """
                 ts | c | s | weight
                ---------------------
                 0  | 1 |NULL| -1
                 0  | 1 | 2 | 1
                 100| 1 |NULL| 1""");
        ccs.step("INSERT INTO t VALUES(101, 7);", """
                 ts | c | s | weight
                ---------------------
                 100| 1 |NULL| -1
                 100| 1 | 7 | 1
                 101| 1 |NULL| 1""");
        // 95 >= waterline 91, not late; it becomes the successor of row 0
        ccs.step("INSERT INTO t VALUES(95, 8);", """
                 ts | c | s | weight
                ---------------------
                 0  | 1 | 2 | -1
                 0  | 1 | 8 | 1
                 95 | 1 | 2 | 1""");
    }
}
