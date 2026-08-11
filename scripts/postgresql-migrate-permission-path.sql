BEGIN;

ALTER TABLE folder_permissions
    ALTER COLUMN path TYPE varchar(4096);

COMMIT;
