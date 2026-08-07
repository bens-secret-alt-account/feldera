use dbsp::{
    algebra::AddByRef,
    circuit::{metrics::TOTAL_LATE_RECORDS, CircuitConfig},
    utils::Tup3,
    zset, DBData,
};
use feldera_sqllib::{append_to_collection_handle, read_output_spine, WSet};
use hashing::zset_to_rows;
use sltsqlvalue::ToSqlRow;
use std::{collections::BTreeSet, sync::atomic::Ordering};
use temp::circuit;

type TextRow = Vec<String>;

fn snapshot<K>(set: &WSet<K>, format: &str) -> BTreeSet<TextRow>
where
    K: DBData + ToSqlRow,
{
    zset_to_rows(set)
        .into_iter()
        .map(|row| {
            row.to_slt_strings(format)
                .into_iter()
                .map(|field| field.to_string())
                .collect()
        })
        .collect()
}

#[test]
fn point_in_time_lookup_survives_cross_key_waterline_advancement() {
    let case = std::env::var("FELDERA_RETENTION_CASE").unwrap_or_else(|_| "unspecified".to_owned());
    let advancement_transactions = std::env::var("FELDERA_ADVANCEMENT_TRANSACTIONS")
        .map(|value| value.parse::<i64>().expect("invalid advancement count"))
        .unwrap_or(512);
    assert!((0..=951).contains(&advancement_transactions));
    TOTAL_LATE_RECORDS.store(0, Ordering::Relaxed);

    let (mut dbsp, streams) =
        circuit(CircuitConfig::with_workers(1)).expect("failed to construct the generated circuit");
    let mut appended_rows = 0_u64;
    let mut transactions = 0_u64;

    // Transaction 1.
    append_to_collection_handle(&zset!(Tup3::new(1_i64, 0_i64, 7_i64) => 1), &streams.1);
    append_to_collection_handle(&zset!(Tup3::new(1_i64, 10_i64, 1_i64) => 1), &streams.0);
    appended_rows += 2;
    transactions += 1;
    dbsp.transaction().expect("transaction 1 failed");

    // read_output_spine returns this transaction's output delta.  Integrate
    // those signed Z-sets explicitly to reconstruct the SQL view contents.
    let mut integrated = read_output_spine(&streams.2);
    let mut integrated_errors = read_output_spine(&streams.3);
    let integrated_rows = snapshot(&integrated, "IIII");
    let errors = snapshot(&integrated_errors, "TTT");
    assert!(
        errors.is_empty(),
        "transaction 1 rejected input: {errors:?}"
    );
    assert!(
        integrated_rows.contains(&vec!["1".into(), "10".into(), "1".into(), "7".into()]),
        "initial point-in-time lookup did not return 7: {integrated_rows:?}"
    );

    // Transactions 2 through 513 under the default schedule: 512 deliberately
    // separate advancement transactions for another key.
    for offset in 0_i64..advancement_transactions {
        let timestamp = 50_i64 + offset;
        append_to_collection_handle(
            &zset!(Tup3::new(2_i64, timestamp, timestamp) => 1),
            &streams.1,
        );
        append_to_collection_handle(
            &zset!(Tup3::new(2_i64, timestamp, timestamp) => 1),
            &streams.0,
        );
        appended_rows += 2;
        transactions += 1;
        dbsp.transaction().unwrap_or_else(|error| {
            panic!("advancement transaction at {timestamp} failed: {error}")
        });

        let output_delta = read_output_spine(&streams.2);
        integrated = integrated.add_by_ref(&output_delta);
        let error_delta = read_output_spine(&streams.3);
        integrated_errors = integrated_errors.add_by_ref(&error_delta);
        let errors = snapshot(&integrated_errors, "TTT");
        assert!(
            errors.is_empty(),
            "advancement transaction at {timestamp} rejected input: {errors:?}"
        );
    }

    let before_final = snapshot(&integrated, "IIII");
    let mut expected_before_final =
        BTreeSet::from([vec!["1".into(), "10".into(), "1".into(), "7".into()]]);
    for offset in 0_i64..advancement_transactions {
        let timestamp = (50_i64 + offset).to_string();
        expected_before_final.insert(vec![
            "2".into(),
            timestamp.clone(),
            timestamp.clone(),
            timestamp,
        ]);
    }
    assert_eq!(
        before_final, expected_before_final,
        "unexpected integrated output before the final transaction"
    );

    // Final transaction (transaction 514 under the default schedule).
    append_to_collection_handle(
        &zset!(Tup3::new(2_i64, 1000_i64, 1000_i64) => 1),
        &streams.1,
    );
    append_to_collection_handle(&zset!(Tup3::new(1_i64, 1000_i64, 999_i64) => 1), &streams.0);
    appended_rows += 2;
    transactions += 1;
    dbsp.transaction().expect("final transaction failed");

    let final_delta = read_output_spine(&streams.2);
    integrated = integrated.add_by_ref(&final_delta);
    let final_error_delta = read_output_spine(&streams.3);
    integrated_errors = integrated_errors.add_by_ref(&final_error_delta);
    let final_integrated = snapshot(&integrated, "IIII");
    let errors = snapshot(&integrated_errors, "TTT");
    let late_records = TOTAL_LATE_RECORDS.load(Ordering::Relaxed);
    assert!(
        errors.is_empty(),
        "the final transaction rejected input: {errors:?}"
    );
    assert_eq!(late_records, 0, "some input rows were rejected as late");
    assert_eq!(appended_rows, (advancement_transactions as u64 * 2) + 4);
    assert_eq!(transactions, advancement_transactions as u64 + 2);

    // This is the incremental output delta of the final transaction, computed
    // explicitly from the two adjacent integrated snapshots.
    let delta_additions: Vec<_> = final_integrated
        .difference(&before_final)
        .cloned()
        .collect();
    let delta_retractions: Vec<_> = before_final
        .difference(&final_integrated)
        .cloned()
        .collect();
    let expected = vec!["1".into(), "1000".into(), "999".into(), "7".into()];
    let observed = final_integrated.get(&expected).cloned();

    println!(
        "RESULT case={case} workers=1 advancement_transactions={advancement_transactions} \
         transactions={transactions} appended_rows={appended_rows} \
         late_records={late_records} integrated_row_count={} target={observed:?} \
         final_delta_additions={delta_additions:?} final_delta_retractions={delta_retractions:?}",
        final_integrated.len(),
    );

    // The semantic regression assertion.  On an affected compiler this fails
    // only for generic_lateness; the two controls pass.
    assert_eq!(
        observed,
        Some(expected),
        "integrated output omitted the predecessor-based lookup result"
    );
}
