CREATE TABLE queries (
    entity_id BIGINT NOT NULL,
    query_ts BIGINT NOT NULL LATENESS 0,
    query_id BIGINT NOT NULL
) WITH ('append_only' = 'true');

CREATE TABLE config_history (
    entity_id BIGINT NOT NULL,
    effective_ts BIGINT NOT NULL LATENESS 0,
    config_value BIGINT NOT NULL
) WITH ('append_only' = 'true');

CREATE VIEW effective_config_asof AS
SELECT
    q.entity_id,
    q.query_ts,
    q.query_id,
    c.config_value
FROM queries AS q
LEFT ASOF JOIN config_history AS c
MATCH_CONDITION(q.query_ts >= c.effective_ts)
ON q.entity_id = c.entity_id;
