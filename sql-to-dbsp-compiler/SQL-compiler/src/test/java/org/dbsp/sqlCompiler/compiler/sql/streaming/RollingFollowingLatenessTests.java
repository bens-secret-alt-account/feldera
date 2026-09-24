package org.dbsp.sqlCompiler.compiler.sql.streaming;

import org.dbsp.sqlCompiler.compiler.sql.StreamingTestBase;
import org.dbsp.sqlCompiler.compiler.sql.tools.CompilerCircuitStream;
import org.junit.Test;

/** A rolling aggregate whose window looks forward gets the input waterline as the
 * bound on its output ordering column, so GC prunes state that is still live.
 *
 * <p>Only rows 60 and 109 carry a non-zero value, so the intermediate steps change
 * no existing row and each assert a single output row.  Row 60's window is
 * [60, 110]: it must be updated when 109 arrives, but by then 60 is far below the
 * waterline.
 *
 * <p>Expected outputs are what the same programs compute without LATENESS.
 * No input violates lateness: the waterline before each insert is 50, 90, 91, ...,
 * 98, and every inserted timestamp is at or above it. */
public class RollingFollowingLatenessTests extends StreamingTestBase {
    /** CURRENT ROW AND 50 FOLLOWING, and the DESC PRECEDING spelling of the same
     * window, produce identical output. */
    void runForwardWindow(CompilerCircuitStream ccs) {
        ccs.step("INSERT INTO t VALUES(60, 1);", """
 ts | x | s | weight
---------------------
 60 | 1 | 1 | 1""");
        ccs.step("INSERT INTO t VALUES(100, 0);", """
 ts | x | s | weight
---------------------
 100 | 0 | 0 | 1""");
        ccs.step("INSERT INTO t VALUES(101, 0);", """
 ts | x | s | weight
---------------------
 101 | 0 | 0 | 1""");
        ccs.step("INSERT INTO t VALUES(102, 0);", """
 ts | x | s | weight
---------------------
 102 | 0 | 0 | 1""");
        ccs.step("INSERT INTO t VALUES(103, 0);", """
 ts | x | s | weight
---------------------
 103 | 0 | 0 | 1""");
        ccs.step("INSERT INTO t VALUES(104, 0);", """
 ts | x | s | weight
---------------------
 104 | 0 | 0 | 1""");
        ccs.step("INSERT INTO t VALUES(105, 0);", """
 ts | x | s | weight
---------------------
 105 | 0 | 0 | 1""");
        ccs.step("INSERT INTO t VALUES(106, 0);", """
 ts | x | s | weight
---------------------
 106 | 0 | 0 | 1""");
        ccs.step("INSERT INTO t VALUES(107, 0);", """
 ts | x | s | weight
---------------------
 107 | 0 | 0 | 1""");
        ccs.step("INSERT INTO t VALUES(108, 0);", """
 ts | x | s | weight
---------------------
 108 | 0 | 0 | 1""");
        ccs.step("INSERT INTO t VALUES(109, 7);", """
 ts | x | s | weight
---------------------
 60 | 1 | 1 | -1
 60 | 1 | 8 | 1
 100 | 0 | 0 | -1
 100 | 0 | 7 | 1
 101 | 0 | 0 | -1
 101 | 0 | 7 | 1
 102 | 0 | 0 | -1
 102 | 0 | 7 | 1
 103 | 0 | 0 | -1
 103 | 0 | 7 | 1
 104 | 0 | 0 | -1
 104 | 0 | 7 | 1
 105 | 0 | 0 | -1
 105 | 0 | 7 | 1
 106 | 0 | 0 | -1
 106 | 0 | 7 | 1
 107 | 0 | 0 | -1
 107 | 0 | 7 | 1
 108 | 0 | 0 | -1
 108 | 0 | 7 | 1
 109 | 7 | 7 | 1""");
    }

    @Test
    public void rollingFollowingLong() {
        String sql = """
                CREATE TABLE t (ts BIGINT NOT NULL LATENESS 10, x BIGINT NOT NULL);
                CREATE VIEW V AS SELECT ts, x, SUM(x) OVER (ORDER BY ts
                   RANGE BETWEEN CURRENT ROW AND 50 FOLLOWING) AS s FROM t;""";
        this.runForwardWindow(this.getCCS(sql).compactAfterEachStep());
    }

    /** The same window, spelled descending.  Fails the same way. */
    @Test
    public void rollingDescPreceding() {
        String sql = """
                CREATE TABLE t (ts BIGINT NOT NULL LATENESS 10, x BIGINT NOT NULL);
                CREATE VIEW V AS SELECT ts, x, SUM(x) OVER (ORDER BY ts DESC
                   RANGE BETWEEN 50 PRECEDING AND CURRENT ROW) AS s FROM t;""";
        this.runForwardWindow(this.getCCS(sql).compactAfterEachStep());
    }

    /** Control: a backward-looking window.  A new row never changes the output of an
     * older row, so the input waterline is a sound bound and this passes. */
    @Test
    public void rollingPrecedingControl() {
        String sql = """
                CREATE TABLE t (ts BIGINT NOT NULL LATENESS 10, x BIGINT NOT NULL);
                CREATE VIEW V AS SELECT ts, x, SUM(x) OVER (ORDER BY ts
                   RANGE BETWEEN 50 PRECEDING AND CURRENT ROW) AS s FROM t;""";
        CompilerCircuitStream ccs = this.getCCS(sql).compactAfterEachStep();
        ccs.step("INSERT INTO t VALUES(60, 1);", """
 ts | x | s | weight
---------------------
 60 | 1 | 1 | 1""");
        ccs.step("INSERT INTO t VALUES(100, 0);", """
 ts | x | s | weight
---------------------
 100 | 0 | 1 | 1""");
        ccs.step("INSERT INTO t VALUES(101, 0);", """
 ts | x | s | weight
---------------------
 101 | 0 | 1 | 1""");
        ccs.step("INSERT INTO t VALUES(102, 0);", """
 ts | x | s | weight
---------------------
 102 | 0 | 1 | 1""");
        ccs.step("INSERT INTO t VALUES(103, 0);", """
 ts | x | s | weight
---------------------
 103 | 0 | 1 | 1""");
        ccs.step("INSERT INTO t VALUES(104, 0);", """
 ts | x | s | weight
---------------------
 104 | 0 | 1 | 1""");
        ccs.step("INSERT INTO t VALUES(105, 0);", """
 ts | x | s | weight
---------------------
 105 | 0 | 1 | 1""");
        ccs.step("INSERT INTO t VALUES(106, 0);", """
 ts | x | s | weight
---------------------
 106 | 0 | 1 | 1""");
        ccs.step("INSERT INTO t VALUES(107, 0);", """
 ts | x | s | weight
---------------------
 107 | 0 | 1 | 1""");
        ccs.step("INSERT INTO t VALUES(108, 0);", """
 ts | x | s | weight
---------------------
 108 | 0 | 1 | 1""");
        ccs.step("INSERT INTO t VALUES(109, 7);", """
 ts | x | s | weight
---------------------
 109 | 7 | 8 | 1""");
    }
}
