-- Flyway callback: runs after every migrate (i.e. on every app startup), before any
-- request or tick can insert a run, so today's partition always exists.
SELECT maintain_run_partitions(${retention_days}, ${partitions_ahead_days});
