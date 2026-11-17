--liquibase formatted sql

--changeset novelweb:notifications-073-in-app-delivery splitStatements:false rollbackSplitStatements:false
ALTER TABLE chapter_publication_release_events
    ADD CONSTRAINT uq_chapter_release_event_work_chapter UNIQUE (id, work_id, chapter_id);

ALTER TABLE chapter_published_outbox_events
    DROP CONSTRAINT chapter_published_outbox_events_status_check,
    ADD CONSTRAINT ck_chapter_published_outbox_status
        CHECK (status IN ('queued', 'leased', 'completed', 'retry', 'failed')),
    ADD COLUMN last_safe_failure_code VARCHAR(32),
    ADD CONSTRAINT ck_chapter_published_outbox_failure_code
        CHECK (last_safe_failure_code IS NULL OR last_safe_failure_code IN (
            'DELIVERY_FAILED', 'LEASE_EXPIRED'
        ));

CREATE TABLE notifications (
    id UUID PRIMARY KEY,
    actor_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    event_id UUID NOT NULL REFERENCES chapter_publication_release_events(id) ON DELETE RESTRICT,
    work_id UUID NOT NULL,
    chapter_id UUID NOT NULL,
    notification_type VARCHAR(32) NOT NULL DEFAULT 'CHAPTER_PUBLISHED'
        CHECK (notification_type = 'CHAPTER_PUBLISHED'),
    channel VARCHAR(16) NOT NULL DEFAULT 'IN_APP' CHECK (channel = 'IN_APP'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    read_at TIMESTAMPTZ,
    CONSTRAINT uq_notifications_actor_event_channel
        UNIQUE (actor_user_id, event_id, channel),
    CONSTRAINT fk_notifications_work_chapter
        FOREIGN KEY (work_id, chapter_id) REFERENCES chapters(work_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_notifications_event_work_chapter
        FOREIGN KEY (event_id, work_id, chapter_id)
        REFERENCES chapter_publication_release_events(id, work_id, chapter_id) ON DELETE RESTRICT
);

CREATE INDEX ix_notifications_actor_page
    ON notifications (actor_user_id, created_at DESC, id DESC);

--rollback DROP INDEX ix_notifications_actor_page;
--rollback DROP TABLE notifications;
--rollback ALTER TABLE chapter_published_outbox_events DROP CONSTRAINT ck_chapter_published_outbox_failure_code, DROP CONSTRAINT ck_chapter_published_outbox_status, DROP COLUMN last_safe_failure_code, ADD CONSTRAINT chapter_published_outbox_events_status_check CHECK (status IN ('queued', 'leased', 'completed', 'retry'));
--rollback ALTER TABLE chapter_publication_release_events DROP CONSTRAINT uq_chapter_release_event_work_chapter;
