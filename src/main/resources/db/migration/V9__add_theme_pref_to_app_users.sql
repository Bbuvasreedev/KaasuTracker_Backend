ALTER TABLE app_users
    ADD COLUMN IF NOT EXISTS theme_pref VARCHAR(20) NOT NULL DEFAULT 'light';

UPDATE app_users
SET theme_pref = 'light'
WHERE theme_pref IS NULL OR theme_pref = '';

