-- Identity dimension for persisted simulation output. These two tables are the
-- stable schema owned by Flyway; the per-metric tables (laborers, prices, banks,
-- …) are derived at runtime from each printer's typed columns (see
-- com.civstudio.io.sink.jdbc.JdbcSchema), so they cannot drift from the printers.
--
-- Every metric row carries (run_id, colony_id), the database equivalent of the
-- per-colony CSV file-name prefix: one table per metric holds every colony of
-- every run, told apart by those keys.

CREATE TABLE IF NOT EXISTS runs (
    id          bigserial PRIMARY KEY,
    scenario    text        NOT NULL,           -- which simulation entry point
    seed        bigint      NOT NULL,           -- the run's RNG seed (reproducibility)
    started_at  timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,                    -- set when the run completes
    git_rev     text                            -- optional source revision
);

CREATE TABLE IF NOT EXISTS settlements (
    id            bigserial PRIMARY KEY,
    run_id        bigint           NOT NULL REFERENCES runs(id),
    name          text             NOT NULL,    -- the colony's name
    founding_date date             NOT NULL,    -- in-game founding date (start date)
    latitude      double precision NOT NULL,
    longitude     double precision NOT NULL
);

CREATE INDEX IF NOT EXISTS settlements_run_idx ON settlements (run_id);
