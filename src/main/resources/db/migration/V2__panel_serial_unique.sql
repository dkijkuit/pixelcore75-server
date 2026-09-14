-- The serial doubles as the MQTT topic base, so duplicates are a correctness bug
-- (two panels fighting over one topic) and unvalidated characters are an injection
-- vector (review 2026-08-27). Hibernate runs with ddl-auto: validate, which does
-- not enforce @Column(unique = true), hence the explicit constraint.
-- Format validation itself lives in Px75PanelService (regex, service-level, so
-- legacy rows can still load).
ALTER TABLE px75_panel ADD CONSTRAINT uq_px75_panel_serial UNIQUE (serial);
