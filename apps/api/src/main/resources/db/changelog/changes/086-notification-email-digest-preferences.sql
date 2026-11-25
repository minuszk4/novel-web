--liquibase formatted sql

--changeset novelweb:notifications-086-email-digest-preferences
CREATE TABLE notification_preferences (
    actor_user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    email_digest_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    digest_frequency VARCHAR(8),
    time_zone VARCHAR(64),
    send_local_time TIME WITHOUT TIME ZONE,
    email_consent_policy_version VARCHAR(64),
    email_consented_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_notification_preferences_frequency
        CHECK (digest_frequency IS NULL OR digest_frequency IN ('DAILY', 'WEEKLY')),
    CONSTRAINT ck_notification_preferences_schedule_complete
        CHECK (
            (digest_frequency IS NULL AND time_zone IS NULL AND send_local_time IS NULL)
            OR (digest_frequency IS NOT NULL AND time_zone IS NOT NULL AND send_local_time IS NOT NULL)
        ),
    CONSTRAINT ck_notification_preferences_consent
        CHECK (
            (email_digest_enabled AND email_consent_policy_version IS NOT NULL
                AND email_consented_at IS NOT NULL)
            OR (NOT email_digest_enabled AND email_consent_policy_version IS NULL
                AND email_consented_at IS NULL)
        ),
    CONSTRAINT ck_notification_preferences_time_zone_length
        CHECK (time_zone IS NULL OR length(time_zone) BETWEEN 1 AND 64),
    CONSTRAINT ck_notification_preferences_consent_version_length
        CHECK (email_consent_policy_version IS NULL
            OR length(email_consent_policy_version) BETWEEN 1 AND 64)
);

--rollback DROP TABLE notification_preferences;
