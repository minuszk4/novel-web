--liquibase formatted sql

--changeset novelweb:notifications-071-work-subscription-owner-page-index
CREATE INDEX ix_work_subscriptions_actor_page
    ON work_subscriptions (actor_user_id, followed_at DESC, id DESC)
    WHERE active = TRUE;

--rollback DROP INDEX ix_work_subscriptions_actor_page;
