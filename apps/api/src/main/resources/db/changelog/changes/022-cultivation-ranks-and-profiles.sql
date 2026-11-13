--liquibase formatted sql

--changeset novelweb:cultivation-001-rank-catalog-and-profile
CREATE TABLE cultivation_rank_catalogs (
    version VARCHAR(32) PRIMARY KEY CHECK (version ~ '^[a-z0-9][a-z0-9._-]{0,31}$'),
    policy_version VARCHAR(32) NOT NULL CHECK (policy_version ~ '^[a-z0-9][a-z0-9._-]{0,31}$'),
    xp_per_active_minute SMALLINT NOT NULL CHECK (xp_per_active_minute > 0),
    daily_xp_cap SMALLINT NOT NULL CHECK (daily_xp_cap > 0),
    effective_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE cultivation_rank_catalog_entries (
    catalog_version VARCHAR(32) NOT NULL REFERENCES cultivation_rank_catalogs(version) ON DELETE RESTRICT,
    locale VARCHAR(16) NOT NULL CHECK (locale ~ '^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$'),
    rank_key VARCHAR(48) NOT NULL CHECK (rank_key ~ '^[a-z0-9][a-z0-9-]{0,47}$'),
    group_label VARCHAR(64) NOT NULL CHECK (length(trim(group_label)) BETWEEN 1 AND 64),
    display_name VARCHAR(100) NOT NULL CHECK (length(trim(display_name)) BETWEEN 1 AND 100),
    threshold_xp BIGINT NOT NULL CHECK (threshold_xp >= 0),
    sort_order SMALLINT NOT NULL CHECK (sort_order >= 0),
    PRIMARY KEY (catalog_version, locale, rank_key),
    UNIQUE (catalog_version, locale, threshold_xp),
    UNIQUE (catalog_version, locale, sort_order)
);

CREATE TABLE cultivation_profiles (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    catalog_version VARCHAR(32) NOT NULL REFERENCES cultivation_rank_catalogs(version) ON DELETE RESTRICT,
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

INSERT INTO cultivation_rank_catalogs
    (version, policy_version, xp_per_active_minute, daily_xp_cap, effective_at)
VALUES
    ('cultivation-v1', 'xp-v1', 1, 120, clock_timestamp());

INSERT INTO cultivation_rank_catalog_entries
    (catalog_version, locale, rank_key, group_label, display_name, threshold_xp, sort_order)
VALUES
    ('cultivation-v1', 'vi-VN', 'mortal', 'Nhập môn', 'Phàm nhân', 0, 0),
    ('cultivation-v1', 'vi-VN', 'qi-1', 'Luyện Khí', 'Luyện Khí tầng 1', 100, 1),
    ('cultivation-v1', 'vi-VN', 'qi-2', 'Luyện Khí', 'Luyện Khí tầng 2', 200, 2),
    ('cultivation-v1', 'vi-VN', 'qi-3', 'Luyện Khí', 'Luyện Khí tầng 3', 300, 3),
    ('cultivation-v1', 'vi-VN', 'qi-4', 'Luyện Khí', 'Luyện Khí tầng 4', 400, 4),
    ('cultivation-v1', 'vi-VN', 'qi-5', 'Luyện Khí', 'Luyện Khí tầng 5', 500, 5),
    ('cultivation-v1', 'vi-VN', 'qi-6', 'Luyện Khí', 'Luyện Khí tầng 6', 600, 6),
    ('cultivation-v1', 'vi-VN', 'qi-7', 'Luyện Khí', 'Luyện Khí tầng 7', 700, 7),
    ('cultivation-v1', 'vi-VN', 'qi-8', 'Luyện Khí', 'Luyện Khí tầng 8', 800, 8),
    ('cultivation-v1', 'vi-VN', 'qi-9', 'Luyện Khí', 'Luyện Khí tầng 9', 900, 9),
    ('cultivation-v1', 'vi-VN', 'foundation', 'Tứ đại cảnh', 'Trúc Cơ', 1000, 10),
    ('cultivation-v1', 'vi-VN', 'golden-core', 'Tứ đại cảnh', 'Kim Đan', 2500, 11),
    ('cultivation-v1', 'vi-VN', 'nascent-soul', 'Tứ đại cảnh', 'Nguyên Anh', 5000, 12),
    ('cultivation-v1', 'vi-VN', 'spirit', 'Thượng giới', 'Hóa Thần', 9000, 13),
    ('cultivation-v1', 'vi-VN', 'void', 'Thượng giới', 'Luyện Hư', 15000, 14),
    ('cultivation-v1', 'vi-VN', 'integration', 'Thượng giới', 'Hợp Thể', 23000, 15),
    ('cultivation-v1', 'vi-VN', 'mahayana', 'Thượng giới', 'Đại Thừa', 33000, 16),
    ('cultivation-v1', 'vi-VN', 'tribulation', 'Thượng giới', 'Độ Kiếp', 45000, 17);
--rollback DROP TABLE cultivation_profiles;
--rollback DROP TABLE cultivation_rank_catalog_entries;
--rollback DROP TABLE cultivation_rank_catalogs;

--changeset novelweb:cultivation-002-rank-catalog-immutability splitStatements:false
CREATE FUNCTION reject_cultivation_rank_catalog_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Cultivation rank catalogs are append-only; publish a new version'
        USING ERRCODE = '55000';
END;
$$;
--rollback DROP FUNCTION reject_cultivation_rank_catalog_mutation();

--changeset novelweb:cultivation-003-rank-catalog-immutability-triggers
CREATE TRIGGER trg_cultivation_rank_catalog_immutable
    BEFORE UPDATE OR DELETE ON cultivation_rank_catalogs
    FOR EACH ROW EXECUTE FUNCTION reject_cultivation_rank_catalog_mutation();

CREATE TRIGGER trg_cultivation_rank_catalog_entry_immutable
    BEFORE UPDATE OR DELETE ON cultivation_rank_catalog_entries
    FOR EACH ROW EXECUTE FUNCTION reject_cultivation_rank_catalog_mutation();
--rollback DROP TRIGGER trg_cultivation_rank_catalog_entry_immutable ON cultivation_rank_catalog_entries;
--rollback DROP TRIGGER trg_cultivation_rank_catalog_immutable ON cultivation_rank_catalogs;
