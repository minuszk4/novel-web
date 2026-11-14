--liquibase formatted sql

--changeset novelweb:translation-003-attempt-failure splitStatements:true
ALTER TABLE translation_attempts
    DROP CONSTRAINT IF EXISTS translation_attempts_outcome_check;
ALTER TABLE translation_attempts
    ADD CONSTRAINT ck_translation_attempt_outcome CHECK (outcome IN
        ('running', 'succeeded', 'rate_limited', 'temporary', 'timeout', 'quota_exceeded',
         'billing_limit_exceeded', 'billing_disabled', 'cost_limit_exceeded',
         'unsupported_language', 'invalid_response', 'input_rejected', 'cancelled'));

ALTER TABLE translation_outbox_events
    DROP CONSTRAINT IF EXISTS ck_translation_outbox_event_type;
ALTER TABLE translation_outbox_events
    ADD CONSTRAINT ck_translation_outbox_event_type CHECK (event_type IN
        ('translation.job.requested', 'translation.job.cancel_requested',
         'translation.job.retry_requested', 'translation.unit.attempt_requested',
         'translation.unit.completed', 'translation.unit.retry_scheduled',
         'translation.unit.failed', 'translation.job.failed',
         'translation.candidate.ready'));

--rollback ALTER TABLE translation_outbox_events DROP CONSTRAINT ck_translation_outbox_event_type;
--rollback ALTER TABLE translation_outbox_events ADD CONSTRAINT ck_translation_outbox_event_type CHECK (event_type IN ('translation.job.requested', 'translation.job.cancel_requested', 'translation.job.retry_requested', 'translation.unit.attempt_requested', 'translation.unit.completed', 'translation.candidate.ready'));
--rollback ALTER TABLE translation_attempts DROP CONSTRAINT ck_translation_attempt_outcome;
--rollback ALTER TABLE translation_attempts ADD CONSTRAINT translation_attempts_outcome_check CHECK (outcome IN ('running', 'succeeded', 'rate_limited', 'temporary', 'timeout', 'quota_exceeded', 'billing_disabled', 'cost_limit_exceeded', 'invalid_response', 'input_rejected', 'cancelled'));
