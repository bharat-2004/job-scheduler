-- Same behaviour as V1's function, but checks for existing partitions first instead of relying on
-- CREATE TABLE IF NOT EXISTS, which logged one NOTICE per existing partition (38) on every startup.
CREATE OR REPLACE FUNCTION maintain_run_partitions(retention_days INT, days_ahead INT)
RETURNS VOID
LANGUAGE plpgsql AS
$$
DECLARE
    today     DATE := (now() AT TIME ZONE 'UTC')::date;
    d         DATE;
    part_name TEXT;
    part      RECORD;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtext('maintain_run_partitions'));

    FOR d IN SELECT generate_series(today - retention_days, today + days_ahead, INTERVAL '1 day')::date
    LOOP
        part_name := 'runs_' || to_char(d, 'YYYYMMDD');
        IF to_regclass(part_name) IS NULL THEN
            EXECUTE format(
                'CREATE TABLE %I PARTITION OF runs FOR VALUES FROM (%L) TO (%L)',
                part_name,
                d::timestamp AT TIME ZONE 'UTC',
                (d + 1)::timestamp AT TIME ZONE 'UTC');
        END IF;
    END LOOP;

    FOR part IN
        SELECT c.relname
          FROM pg_inherits i
          JOIN pg_class c ON c.oid = i.inhrelid
          JOIN pg_class p ON p.oid = i.inhparent
         WHERE p.relname = 'runs'
           AND c.relname ~ '^runs_[0-9]{8}$'
           AND to_date(substring(c.relname FROM 6), 'YYYYMMDD') < today - retention_days
    LOOP
        EXECUTE format('DROP TABLE %I', part.relname);
    END LOOP;
END;
$$;
