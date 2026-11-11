--liquibase formatted sql

--changeset novelweb:reader-002-preferences
CREATE TABLE reader_preferences (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    schema_version INTEGER NOT NULL DEFAULT 1 CHECK (schema_version = 1),
    theme VARCHAR(16) NOT NULL DEFAULT 'light' CHECK (theme IN ('light', 'sepia', 'dark')),
    font_family VARCHAR(16) NOT NULL DEFAULT 'serif' CHECK (font_family IN ('serif', 'sans')),
    font_size INTEGER NOT NULL DEFAULT 20 CHECK (font_size BETWEEN 16 AND 28),
    line_height NUMERIC NOT NULL DEFAULT 1.8 CHECK (line_height BETWEEN 1.2 AND 2.2),
    content_width INTEGER NOT NULL DEFAULT 720 CHECK (content_width BETWEEN 560 AND 900),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
--rollback DROP TABLE reader_preferences;
