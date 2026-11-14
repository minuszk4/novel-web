--liquibase formatted sql

--changeset novelweb:translation-002-attempt-outbox-event splitStatements:true
ALTER TABLE translation_outbox_events
    DROP CONSTRAINT IF EXISTS translation_outbox_events_event_type_check;
ALTER TABLE translation_outbox_events
    ADD CONSTRAINT ck_translation_outbox_event_type CHECK (event_type IN
        ('translation.job.requested', 'translation.job.cancel_requested',
         'translation.job.retry_requested', 'translation.unit.attempt_requested',
         'translation.unit.completed', 'translation.candidate.ready'));

--rollback ALTER TABLE translation_outbox_events DROP CONSTRAINT ck_translation_outbox_event_type;
--rollback ALTER TABLE translation_outbox_events ADD CONSTRAINT translation_outbox_events_event_type_check CHECK (event_type IN ('translation.job.requested', 'translation.job.cancel_requested', 'translation.job.retry_requested', 'translation.unit.completed', 'translation.candidate.ready'));
