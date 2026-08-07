#!/usr/bin/env bash

set -uo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
compiler_dir=$(cd "$script_dir/../.." && pwd)
repo_root=$(cd "$compiler_dir/.." && pwd)
jar="$compiler_dir/SQL-compiler/target/sql2dbsp-jar-with-dependencies.jar"
temp_dir="$compiler_dir/temp"
artifact_dir="$repo_root/target/argmax-asof-retention"

if [[ ! -f "$jar" ]]; then
    echo "SQL compiler jar not found: $jar" >&2
    echo "Build it first with: (cd $compiler_dir && ./build.sh)" >&2
    exit 2
fi

mkdir -p "$artifact_dir"
cp "$repo_root/Cargo.lock" "$temp_dir/Cargo.lock"
cp "$script_dir/driver.rs" "$temp_dir/src/main.rs"

if (($#)); then
    cases=("$@")
else
    cases=(generic_lateness asof_lateness generic_no_lateness)
fi

overall_status=0
for case_name in "${cases[@]}"; do
    sql="$script_dir/sql/$case_name.sql"
    generated="$temp_dir/src/lib.rs"
    plan="$artifact_dir/$case_name.calcite-plan.json"
    dataflow="$artifact_dir/$case_name.dataflow.json"
    log="$artifact_dir/$case_name.test.log"
    retention="$artifact_dir/$case_name.retention.txt"

    if [[ ! -f "$sql" ]]; then
        echo "unknown case: $case_name" >&2
        exit 2
    fi

    echo "Compiling $case_name"
    if ! java -jar "$jar" \
        --handles \
        -i \
        --ignoreOrder \
        --plan "$plan" \
        --dataflow "$dataflow" \
        -o "$generated" \
        "$sql"; then
        exit 2
    fi

    {
        echo "case=$case_name"
        echo "commit=$(git -C "$repo_root" rev-parse HEAD)"
        jq -r '.mir | to_entries[] | [.key, .value.operation, ((.value.inputs // []) | map(.node) | join(",")), (.value.table // "")] | @tsv' "$dataflow"
        rg -n 'accumulate_integrate_trace_retain|join_flatmap|asof_join|chain_aggregate' "$generated" || true
    } >"$retention"

    echo "Testing $case_name"
    if FELDERA_RETENTION_CASE="$case_name" \
        CARGO_TARGET_DIR="$repo_root/target/argmax-asof-retention-cargo" \
        RUST_MIN_STACK=8388608 \
        cargo test \
            --manifest-path "$temp_dir/Cargo.toml" \
            --bin temp \
            --jobs 6 \
            -- \
            --nocapture \
            --test-threads=1 2>&1 | tee "$log"; then
        echo "$case_name: semantic assertion passed"
    else
        status=${PIPESTATUS[0]}
        echo "$case_name: semantic assertion failed (cargo status $status)"
        overall_status=1
    fi
done

echo "Artifacts: $artifact_dir"
exit "$overall_status"
