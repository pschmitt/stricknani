PRAGMA foreign_keys = OFF;

CREATE TABLE users (
    id INTEGER PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    hashed_password VARCHAR(255) NOT NULL,
    is_active BOOLEAN NOT NULL,
    is_admin BOOLEAN NOT NULL,
    token_version INTEGER NOT NULL,
    created_at DATETIME NOT NULL,
    profile_image VARCHAR(255)
);
CREATE TABLE api_tokens (
    id INTEGER PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    created_at DATETIME NOT NULL,
    last_used_at DATETIME,
    expires_at DATETIME,
    user_id INTEGER NOT NULL REFERENCES users(id)
);
CREATE TABLE categories (
    id INTEGER PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    created_at DATETIME NOT NULL,
    user_id INTEGER NOT NULL REFERENCES users(id)
);
CREATE TABLE yarns (
    id INTEGER PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    brand VARCHAR(120),
    colorway VARCHAR(120),
    dye_lot VARCHAR(120),
    fiber_content VARCHAR(255),
    weight_category VARCHAR(80),
    recommended_needles VARCHAR(255),
    weight_grams INTEGER,
    length_meters INTEGER,
    notes TEXT,
    link VARCHAR(2048),
    link_archive VARCHAR(2048),
    link_archive_requested_at DATETIME,
    link_archive_failed BOOLEAN NOT NULL,
    is_ai_enhanced BOOLEAN NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    owner_id INTEGER NOT NULL REFERENCES users(id)
);
CREATE TABLE projects (
    id INTEGER PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    category VARCHAR(50),
    yarn VARCHAR(255),
    needles VARCHAR(255),
    stitch_sample TEXT,
    description TEXT,
    notes TEXT,
    other_materials TEXT,
    tags TEXT,
    link VARCHAR(2048),
    link_archive VARCHAR(2048),
    link_archive_requested_at DATETIME,
    link_archive_failed BOOLEAN NOT NULL,
    is_ai_enhanced BOOLEAN NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    owner_id INTEGER NOT NULL REFERENCES users(id)
);
CREATE TABLE steps (
    id INTEGER PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    step_number INTEGER NOT NULL,
    created_at DATETIME NOT NULL,
    project_id INTEGER NOT NULL REFERENCES projects(id)
);
CREATE TABLE project_yarns (
    project_id INTEGER NOT NULL REFERENCES projects(id),
    yarn_id INTEGER NOT NULL REFERENCES yarns(id),
    PRIMARY KEY (project_id, yarn_id)
);
CREATE TABLE user_favorites (
    user_id INTEGER NOT NULL REFERENCES users(id),
    project_id INTEGER NOT NULL REFERENCES projects(id),
    PRIMARY KEY (user_id, project_id)
);
CREATE TABLE user_favorite_yarns (
    user_id INTEGER NOT NULL REFERENCES users(id),
    yarn_id INTEGER NOT NULL REFERENCES yarns(id),
    PRIMARY KEY (user_id, yarn_id)
);
CREATE TABLE images (
    id INTEGER PRIMARY KEY,
    filename VARCHAR(255) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    image_type VARCHAR(20) NOT NULL,
    alt_text VARCHAR(255) NOT NULL,
    is_title_image BOOLEAN NOT NULL,
    is_stitch_sample BOOLEAN,
    width INTEGER,
    height INTEGER,
    created_at DATETIME NOT NULL,
    project_id INTEGER NOT NULL REFERENCES projects(id),
    step_id INTEGER REFERENCES steps(id)
);
CREATE TABLE attachments (
    id INTEGER PRIMARY KEY,
    filename VARCHAR(255) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes INTEGER NOT NULL,
    created_at DATETIME NOT NULL,
    project_id INTEGER NOT NULL REFERENCES projects(id)
);
CREATE TABLE yarn_images (
    id INTEGER PRIMARY KEY,
    filename VARCHAR(255) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    alt_text VARCHAR(255) NOT NULL,
    is_primary BOOLEAN NOT NULL,
    created_at DATETIME NOT NULL,
    yarn_id INTEGER NOT NULL REFERENCES yarns(id)
);
CREATE TABLE audit_logs (
    id INTEGER PRIMARY KEY,
    actor_user_id INTEGER NOT NULL REFERENCES users(id),
    entity_type VARCHAR(20) NOT NULL,
    entity_id INTEGER NOT NULL,
    action VARCHAR(64) NOT NULL,
    details TEXT,
    created_at DATETIME NOT NULL
);

INSERT INTO users VALUES
    (7, 'fixture@example.invalid', 'not-a-real-password-hash-fixture', 1, 1, 4,
     '2026-01-02 03:04:05.123456', 'avatar.png');
INSERT INTO api_tokens VALUES
    (11, 'Fixture PAT', 'fixture-sha256-token-hash', '2026-01-03 04:05:06.987654',
     '2026-01-04 05:06:07.123456', '2027-01-03 04:05:06.000000', 7);
INSERT INTO categories VALUES
    (13, 'Handmade', '2026-01-05 06:07:08.000001', 7);
INSERT INTO yarns VALUES
    (17, 'Fixture yarn', 'soft', 'Fixture brand', 'Blue', 'LOT-1', 'wool', 'DK',
     '4 mm', 100, 200, 'keep dry', 'https://example.invalid/yarn', NULL,
     '2026-01-06 07:08:09.000002', 0, 1, '2026-01-07 08:09:10.000003',
     '2026-01-08 09:10:11.000004', 7);
INSERT INTO projects VALUES
    (19, 'Fixture project', 'Handmade', 'Legacy wool text', '4 mm', '24 sts',
     'description', 'notes', 'buttons', '["sock","wool"]',
     'https://example.invalid/project', NULL, '2026-01-09 10:11:12.000005',
     1, 1, '2026-01-10 11:12:13.000006', '2026-01-11 12:13:14.000007', 7);
INSERT INTO projects VALUES
    (20, 'Uncategorized fixture project', 'Vintage', NULL, NULL, NULL,
     NULL, NULL, NULL, NULL, NULL, NULL, NULL, 0, 0,
     '2026-01-19 20:21:22.000016', '2026-01-20 21:22:23.000017', 7);
INSERT INTO steps VALUES
    (23, 'Cast on', 'CO 24', 1, '2026-01-12 13:14:15.000008', 19);
INSERT INTO project_yarns VALUES (19, 17);
INSERT INTO user_favorites VALUES (7, 19);
INSERT INTO user_favorite_yarns VALUES (7, 17);
INSERT INTO images VALUES
    (1, 'shared.png', 'shared-original.png', 'diagram', 'Project diagram', 0, NULL,
     1, 1, '2026-01-13 14:15:16.000009', 19, 23),
    (2, 'title-a.png', 'title-a-original.png', 'title', 'First title', 1, 0,
     1, 1, '2026-01-13 14:15:17.000009', 19, NULL),
    (3, 'title-b.png', 'title-b-original.png', 'title', 'Second title', 1, 1,
     1, 1, '2026-01-13 14:15:18.000009', 19, NULL);
INSERT INTO attachments VALUES
    (1, 'shared.png', 'shared-original.png', 'image/png', 68,
     '2026-01-14 15:16:17.000010', 19),
    (2, 'document.pdf', 'document.pdf', 'application/pdf', 36,
     '2026-01-14 15:16:18.000011', 19);
INSERT INTO yarn_images VALUES
    (1, 'photo.png', 'photo-original.png', 'Yarn photo', 1,
     '2026-01-15 16:17:18.000012', 17);
INSERT INTO audit_logs VALUES
    (31, 7, 'project', 19, 'created', '{"fixture":true}', '2026-01-16 17:18:19.000013'),
    (32, 7, 'project', 777, 'deleted', NULL, '2026-01-17 18:19:20.000014'),
    (33, 404, 'yarn', 888, 'deleted', NULL, '2026-01-18 19:20:21.000015');
