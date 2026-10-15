CREATE TABLE device
(
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    serial_no     TEXT NOT NULL UNIQUE,
    manufacturer  TEXT,
    model         TEXT,
    first_seen_at TEXT NOT NULL,
    last_seen_at  TEXT NOT NULL
);

CREATE TABLE app_icon
(
    hash TEXT PRIMARY KEY,
    png  BLOB NOT NULL
);

CREATE TABLE snapshot
(
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id         INTEGER NOT NULL REFERENCES device (id) ON DELETE CASCADE,
    android_user_id   INTEGER NOT NULL,
    kind              TEXT    NOT NULL CHECK (kind IN ('BEFORE_UPDATE', 'AFTER_UPDATE', 'OTHER')),
    note              TEXT,
    created_at        TEXT    NOT NULL,
    adb_serial        TEXT,
    build_fingerprint TEXT,
    build_display_id  TEXT,
    build_incremental TEXT,
    android_release   TEXT,
    sdk_int           INTEGER,
    security_patch    TEXT,
    package_count     INTEGER NOT NULL,
    metadata_status   TEXT    NOT NULL CHECK (metadata_status IN ('FULL', 'PARTIAL', 'UNAVAILABLE')),
    metadata_message  TEXT
);

CREATE INDEX snapshot_device_idx ON snapshot (device_id, android_user_id, created_at);

CREATE TABLE snapshot_package
(
    snapshot_id        INTEGER NOT NULL REFERENCES snapshot (id) ON DELETE CASCADE,
    package_name       TEXT    NOT NULL,
    state              TEXT    NOT NULL CHECK (state IN ('ENABLED', 'DISABLED', 'UNINSTALLED')),
    is_system          INTEGER NOT NULL,
    is_updated_system  INTEGER,
    version_code       INTEGER,
    version_name       TEXT,
    label              TEXT,
    apk_path           TEXT,
    first_install_time INTEGER,
    last_update_time   INTEGER,
    icon_hash          TEXT REFERENCES app_icon (hash),
    PRIMARY KEY (snapshot_id, package_name)
);

CREATE TABLE action_log
(
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at         TEXT    NOT NULL,
    device_id          INTEGER NOT NULL REFERENCES device (id) ON DELETE CASCADE,
    android_user_id    INTEGER NOT NULL,
    package_name       TEXT    NOT NULL,
    label              TEXT,
    action             TEXT    NOT NULL CHECK (action IN ('UNINSTALL', 'DISABLE', 'RESTORE')),
    before_snapshot_id INTEGER NOT NULL REFERENCES snapshot (id) ON DELETE RESTRICT,
    after_snapshot_id  INTEGER NOT NULL REFERENCES snapshot (id) ON DELETE RESTRICT,
    state_before       TEXT,
    state_after        TEXT,
    status             TEXT    NOT NULL CHECK (status IN ('SUCCESS', 'FAILED', 'SKIPPED')),
    commands           TEXT,
    output             TEXT,
    message            TEXT,
    warning            TEXT,
    duration_ms        INTEGER NOT NULL
);

CREATE INDEX action_log_created_idx ON action_log (created_at);
CREATE INDEX action_log_before_idx ON action_log (before_snapshot_id);
CREATE INDEX action_log_after_idx ON action_log (after_snapshot_id);

CREATE TABLE app_setting
(
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
