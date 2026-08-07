# ARG_MAX point-in-time retention soundness bug

This is a standalone reproduction against standard upstream Feldera.

## Lateness contract

Feldera's `LATENESS` contract is that lateness is a memory/performance
optimization only: if an input never violates a declared `LATENESS` bound, the
circuit's output must be identical to the same program with no lateness
declarations. Rows that would violate the bound are dropped and counted in
`TOTAL_LATE_RECORDS`.

In every run below, `TOTAL_LATE_RECORDS = 0` and the error view stays empty, so
the input never violates the contract. The `generic_no_lateness` control is
therefore the ground-truth oracle for what the `LATENESS 0` program must return,
and any divergence from it is a contract breach rather than a user error.

## Revision

The full test was run in a clean worktree of the upstream default branch
(`origin/main`) at:

```text
328d8467d0f0abd71db39f1a3a7fec75cc375f0f
```

The same value is recorded in `UPSTREAM_COMMIT`.

## Files

- `sql/generic_lateness.sql`: inequality join plus `ARG_MAX`, with both
  timestamp columns declared `LATENESS 0`.
- `sql/asof_lateness.sql`: native `LEFT ASOF JOIN`, with both timestamp columns
  declared `LATENESS 0`.
- `sql/generic_no_lateness.sql`: inequality join plus `ARG_MAX`, with no
  lateness declarations.
- `driver.rs`: shared one-worker Rust regression driver. It executes every
  numbered item as a separate transaction, reads each transaction's signed
  output delta, explicitly integrates those deltas, and asserts the final SQL
  view contents.
- `run.sh`: independently compiles and tests each SQL program and emits plan
  artifacts.

The semantic assertion is deliberately failing for `generic_lateness` on this
revision. `run.sh` continues through both controls and exits nonzero after all
three cases have run.

## Exact commands

From the repository root:

```bash
cd sql-to-dbsp-compiler
./build.sh
cd ..
sql-to-dbsp-compiler/experiments/argmax-asof-retention/run.sh
```

Run cases individually with:

```bash
sql-to-dbsp-compiler/experiments/argmax-asof-retention/run.sh generic_lateness
sql-to-dbsp-compiler/experiments/argmax-asof-retention/run.sh asof_lateness
sql-to-dbsp-compiler/experiments/argmax-asof-retention/run.sh generic_no_lateness
```

The runner expands each case to the upstream compiler and Cargo commands below
(with case-specific paths):

```bash
java -jar sql-to-dbsp-compiler/SQL-compiler/target/sql2dbsp-jar-with-dependencies.jar \
  --handles -i --ignoreOrder \
  --plan target/argmax-asof-retention/<case>.calcite-plan.json \
  --dataflow target/argmax-asof-retention/<case>.dataflow.json \
  -o sql-to-dbsp-compiler/temp/src/lib.rs \
  sql-to-dbsp-compiler/experiments/argmax-asof-retention/sql/<case>.sql

FELDERA_RETENTION_CASE=<case> \
  CARGO_TARGET_DIR=target/argmax-asof-retention-cargo \
  RUST_MIN_STACK=8388608 \
  cargo test \
    --manifest-path sql-to-dbsp-compiler/temp/Cargo.toml \
    --bin temp --jobs 6 -- --nocapture --test-threads=1
```

Raw Calcite plans, dataflow plans, retention extracts, and test logs are written
under `target/argmax-asof-retention/`.

## Full 512-transaction result

Let:

```text
B = {(1, 10, 1, 7)} union {(2, t, t, t) | 50 <= t <= 561}
```

The exact final integrated results were:

| Case | Final integrated output | Rows | Final transaction delta |
|---|---|---:|---|
| Generic, `LATENESS 0` | `B`; `(1, 1000, 999, 7)` is absent | 513 | no additions or retractions |
| Native ASOF, `LATENESS 0` | `B union {(1, 1000, 999, 7)}` | 514 | add `(1, 1000, 999, 7)` |
| Generic, no lateness | `B union {(1, 1000, 999, 7)}` | 514 | add `(1, 1000, 999, 7)` |

All three initially produced `(1, 10, 1, 7)`. Each run used one worker and 514
transactions, appended 1,028 rows, observed `TOTAL_LATE_RECORDS = 0`, and kept
the integrated Feldera error view empty after every transaction. Thus every
input row was accepted.

The relevant result lines were:

```text
RESULT case=generic_lateness workers=1 advancement_transactions=512 transactions=514 appended_rows=1028 late_records=0 integrated_row_count=513 target=None final_delta_additions=[] final_delta_retractions=[]
RESULT case=asof_lateness workers=1 advancement_transactions=512 transactions=514 appended_rows=1028 late_records=0 integrated_row_count=514 target=Some(["1", "1000", "999", "7"]) final_delta_additions=[["1", "1000", "999", "7"]] final_delta_retractions=[]
RESULT case=generic_no_lateness workers=1 advancement_transactions=512 transactions=514 appended_rows=1028 late_records=0 integrated_row_count=514 target=Some(["1", "1000", "999", "7"]) final_delta_additions=[["1", "1000", "999", "7"]] final_delta_retractions=[]
```

## Retention-plan comparison

The bounded generic dataflow installs ordinary
`accumulate_integrate_trace_retain_values` on both indexed join inputs. On the
history side, the predicate clips values whose `effective_ts` is below the
delayed waterline floor. It then performs `join_flatmap_balanced`, followed by
the `chain_aggregate` that implements `ARG_MAX`. The only aggregate-side
retention in this generated plan is
`accumulate_integrate_trace_retain_keys`, after the join and aggregate.

No `TopN` retention operator is generated for `ARG_MAX` in this program. In
particular, there is no pre-join summary preserving the last history value for
each entity. The downstream aggregate cannot recover a history row already
clipped from the join trace.

The native ASOF dataflow instead installs ordinary value retention on the query
trace and `accumulate_integrate_trace_retain_values_last_n` on the history
trace. The compiler constructs that operator as `LastN(1)`: it retains values
at or above the floor plus one predecessor below it for each join key. The
unbounded generic control has no waterline-driven trace-retention operators.

## Reduced schedule

The required 512 advancement transactions remain the default. After validating
that schedule, the same driver was run with fewer advancement transactions by
setting `FELDERA_ADVANCEMENT_TRANSACTIONS`.

The smallest failing count observed was 6 (`t = 50..55`): the bounded generic
case omitted the final row with 16 accepted inputs across 8 transactions. A
count of 5 passed. Both controls still returned value 7 with a count of 6.

```bash
FELDERA_ADVANCEMENT_TRANSACTIONS=6 \
  sql-to-dbsp-compiler/experiments/argmax-asof-retention/run.sh generic_lateness
```

## Related issues

- `feldera/feldera#6690` / `feldera/feldera#6693` (fix merged 2026-07-27):
  upstream established that using `integrate_trace_retain_values` for
  LATENESS-driven GC is unsound because it "could delete keys that should be
  permanently frozen," and switched the affected path to
  `integrate_trace_retain_keys`. That fix is scoped to tables with a
  `PRIMARY KEY` whose key contains a `LATENESS` column (the
  `input_map_with_waterline` output). It is already present in the pinned commit
  above, yet the bug below still reproduces.
- `feldera/feldera#4631` (merged 2025-08-26): introduced the
  `retain_values`-based waterline GC that `#6690` later identified as unsound.
- `feldera/feldera#2010` ("GC for AsOf joins"): why native ASOF installs
  `..._retain_values_last_n` (`LastN(1)`) predecessor retention — exactly what
  makes the ASOF control correct here.
- `feldera/feldera#4661` ("Inefficient ARG_MIN with a temporal filter"): a
  *performance* issue in the same operator family, not this soundness bug.

## Conclusion

The bounded inequality-join-plus-`ARG_MAX` formulation is answer-level unsound
with valid `LATENESS 0` inputs: it drops the on-time row `(1, 1000, 999, 7)`
that both native ASOF and the no-lateness control retain, with zero late
records. This breaches the lateness contract stated above.

It is the same root cause as `feldera/feldera#6690` / `feldera/feldera#6693` —
`retain_values`-based GC deleting a row that is still reachable — surviving in a
codepath that fix did not cover. Here the unsound retention is
`accumulate_integrate_trace_retain_values` on the history-side *join input* of
the generic plan (the tables have no `PRIMARY KEY`; `LATENESS` is on a plain
column), not the `input_map_with_waterline` output.

A fix would extend the `#6693` direction to this plan: the history join input
must not GC below-floor rows with `retain_values`, and should instead retain the
last predecessor per join key (as ASOF already does via `LastN(1)`) or fall back
to `retain_keys`. This reproduction intentionally makes no compiler change.
