-- Short code used as the prefix of ticket numbers (e.g. IT-2026-000036).
ALTER TABLE departments ADD COLUMN code VARCHAR(10);

UPDATE departments
SET code = left(
    CASE
        WHEN length(regexp_replace(upper(regexp_replace(department_name, '(\w)\w*\W*', '\1', 'g')), '[^A-Z0-9]', '', 'g')) >= 2
            THEN regexp_replace(upper(regexp_replace(department_name, '(\w)\w*\W*', '\1', 'g')), '[^A-Z0-9]', '', 'g')
        ELSE upper(regexp_replace(department_name, '[^A-Za-z0-9]', '', 'g'))
    END, 5);

UPDATE departments SET code = 'HD' WHERE code IS NULL OR code = '';

ALTER TABLE departments ALTER COLUMN code SET NOT NULL;
