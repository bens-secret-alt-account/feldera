CREATE TABLE queries (
    entity_id BIGINT NOT NULL,
    query_ts BIGINT NOT NULL,
    query_id BIGINT NOT NULL
) WITH ('append_only' = 'true');

CREATE TABLE config_history (
    entity_id BIGINT NOT NULL,
    effective_ts BIGINT NOT NULL,
    config_value BIGINT NOT NULL
) WITH ('append_only' = 'true');

CREATE VIEW effective_config_arg_max AS
SELECT
    q.entity_id,
    q.query_ts,
    q.query_id,
    ARG_MAX(c.config_value, c.effective_ts) AS config_value
FROM queries AS q
JOIN config_history AS c
  ON q.entity_id = c.entity_id
 AND c.effective_ts <= q.query_ts
GROUP BY q.entity_id, q.query_ts, q.query_id;
