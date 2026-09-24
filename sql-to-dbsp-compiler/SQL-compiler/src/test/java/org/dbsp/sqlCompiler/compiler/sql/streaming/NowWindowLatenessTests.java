package org.dbsp.sqlCompiler.compiler.sql.streaming;

import org.dbsp.sqlCompiler.compiler.sql.StreamingTestBase;
import org.dbsp.sqlCompiler.compiler.sql.tools.CompilerCircuitStream;
import org.junit.Test;

/** Expected outputs are what the same program computes without LATENESS.
 * No input violates lateness. */
public class NowWindowLatenessTests extends StreamingTestBase {
    @Test
    public void nowWindowGroupBy() {
        String sql = """
                CREATE TABLE T (ts TIMESTAMP NOT NULL LATENESS INTERVAL 1 MINUTE, x INT);
                CREATE LOCAL VIEW W AS SELECT * FROM T WHERE ts >= NOW() - INTERVAL 1 HOUR;
                CREATE VIEW C AS SELECT ts, COUNT(*) AS c FROM W GROUP BY ts;""";
        CompilerCircuitStream ccs = this.getCCS(sql).compactAfterEachStep();
        ccs.step("""
                INSERT INTO now VALUES('2024-01-01 12:00:00');
                INSERT INTO T VALUES('2024-01-01 11:30:00', 1);
                INSERT INTO T VALUES('2024-01-01 11:50:00', 1);""", """
                 ts | c | weight
                -----------------
                 2024-01-01 11:30:00 | 1 | 1
                 2024-01-01 11:50:00 | 1 | 1""");
        ccs.step("INSERT INTO T VALUES('2024-01-01 11:55:00', 1);", """
                 ts | c | weight
                -----------------
                 2024-01-01 11:55:00 | 1 | 1""");
        ccs.step("INSERT INTO T VALUES('2024-01-01 11:56:00', 1);", """
                 ts | c | weight
                -----------------
                 2024-01-01 11:56:00 | 1 | 1""");
        ccs.step("INSERT INTO now VALUES('2024-01-01 12:40:00');", """
                 ts | c | weight
                -----------------
                 2024-01-01 11:30:00 | 1 | -1""");
    }
}
