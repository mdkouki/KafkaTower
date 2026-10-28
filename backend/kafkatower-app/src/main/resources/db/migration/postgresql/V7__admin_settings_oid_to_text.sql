-- admin_settings.payload was mapped as a large object, so rows written by that version hold the
-- OID of a large object (e.g. '16455') instead of the JSON. Copy the JSON back into the column
-- and drop the large object. Rows that already hold JSON are left alone.
WITH legacy AS (
    SELECT section, payload::oid AS loid
    FROM admin_settings
    WHERE payload ~ '^[0-9]+$'
      AND EXISTS (SELECT 1 FROM pg_largeobject_metadata m WHERE m.oid = payload::oid)
),
fixed AS (
    UPDATE admin_settings s
    SET payload = convert_from(lo_get(l.loid), 'UTF8')
    FROM legacy l
    WHERE s.section = l.section
    RETURNING l.loid
)
SELECT lo_unlink(loid) FROM fixed;
