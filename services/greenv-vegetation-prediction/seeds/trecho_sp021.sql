-- =====================================================================
-- Seed: trecho grid for SP-021 / Rodoanel Oeste, km 0.0 to 29.3
-- Phase 2 artifact of docs/VEGETATION_PREDICTION_TASK.md
--
-- WHAT THIS IS:
--   A deterministic spatial partition of a stated km range into ~500 m
--   stretches, one per sentido. This is STRUCTURE, not data:
--     * km_start / km_end are arithmetic (0.0, 0.5, 1.0, ... 29.0, 29.3),
--       taken straight from the Phase 2 scenario brief.
--     * NO vegetation observations are created here.
--     * centroid_lat / centroid_lon / geom_wkt are left NULL. They will be
--       filled in Phase 3 from the linear reference, which for SP-021 is a
--       SYNTHETIC anchor-point interpolation (params.yaml: linear_reference,
--       confidence: hypothesis). Until then, every trecho carries
--       linear_reference_provenance = 'hypothesis'.
--
-- Result: 59 trechos per sentido (km_start 0.0, 0.5, ... 29.0; the last is
--         the 300 m stretch 29.0-29.3) x 2 sentidos = 118 rows.
--
-- Portability: the recursive CTE runs on SQLite (>= 3.8.3) and PostgreSQL
-- (needs the RECURSIVE keyword, already present). printf() is SQLite-only;
-- the PostgreSQL form is noted at the bottom.
-- =====================================================================

INSERT INTO trecho (trecho_id, rodovia, sentido, km_start, km_end, length_m,
                    centroid_lat, centroid_lon, geom_wkt,
                    linear_reference_provenance, created_at, notes)
WITH RECURSIVE
    params(rodovia, km_min, km_max, step_km) AS (
        VALUES ('SP-021', 0.0, 29.3, 0.5)
    ),
    sentidos(sentido) AS (
        VALUES ('norte'), ('sul')
    ),
    grid(km_start) AS (
        SELECT km_min FROM params
        UNION ALL
        SELECT ROUND(km_start + (SELECT step_km FROM params), 4)
        FROM grid
        WHERE km_start + (SELECT step_km FROM params) < (SELECT km_max FROM params)
    )
SELECT
    p.rodovia || ':' || s.sentido || ':' ||
        printf('%06d', CAST(ROUND(g.km_start * 1000) AS INTEGER))          AS trecho_id,
    p.rodovia                                                              AS rodovia,
    s.sentido                                                              AS sentido,
    g.km_start                                                             AS km_start,
    MIN(ROUND(g.km_start + p.step_km, 4), p.km_max)                        AS km_end,
    (MIN(ROUND(g.km_start + p.step_km, 4), p.km_max) - g.km_start) * 1000.0 AS length_m,
    NULL                                                                   AS centroid_lat,
    NULL                                                                   AS centroid_lon,
    NULL                                                                   AS geom_wkt,
    'hypothesis'                                                           AS linear_reference_provenance,
    strftime('%Y-%m-%dT%H:%M:%SZ', 'now')                                  AS created_at,
    'structural km partition; geometry pending Phase 3 (synthetic linear reference)' AS notes
FROM grid g
CROSS JOIN params p
CROSS JOIN sentidos s
ORDER BY s.sentido, g.km_start;

-- Register the seed in the ledger.
INSERT INTO dataset_manifest (manifest_id, kind, created_at, params_hash, seed,
                              row_count, is_synthetic, source_summary, notes)
VALUES (
    lower(hex(randomblob(16))),
    'trecho_seed',
    strftime('%Y-%m-%dT%H:%M:%SZ', 'now'),
    NULL,
    NULL,
    (SELECT COUNT(*) FROM trecho WHERE rodovia = 'SP-021'),
    0,
    'SP-021 km 0.0-29.3, 500 m stretches, both sentidos; km partition from the Phase 2 brief; geometry NULL pending Phase 3',
    'Not synthetic data: a deterministic spatial index. Geometry (lat/lon) is deferred and will be hypothesis-provenance.'
);

-- ---------------------------------------------------------------------
-- PostgreSQL note: replace
--     printf('%06d', CAST(ROUND(g.km_start * 1000) AS INTEGER))
--   with
--     lpad((round(g.km_start * 1000))::int::text, 6, '0')
--   and
--     strftime('%Y-%m-%dT%H:%M:%SZ', 'now')  ->  to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
--     lower(hex(randomblob(16)))             ->  gen_random_uuid()::text   (pgcrypto / pg >= 13)
--     MIN(a, b) scalar                       ->  least(a, b)
-- ---------------------------------------------------------------------
