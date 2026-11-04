CREATE TABLE yarns (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
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
    is_ai_enhanced INTEGER NOT NULL DEFAULT 0 CHECK (is_ai_enhanced IN (0, 1)),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX yarns_owner ON yarns(owner_id);
CREATE INDEX yarns_updated ON yarns(updated_at DESC, id DESC);

CREATE TABLE user_favorite_yarns (
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    yarn_id INTEGER NOT NULL REFERENCES yarns(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, yarn_id)
);

CREATE INDEX user_favorite_yarns_yarn ON user_favorite_yarns(yarn_id);
