CREATE TABLE projects (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    category_id INTEGER REFERENCES categories(id) ON DELETE SET NULL,
    name VARCHAR(255) NOT NULL,
    needles VARCHAR(255),
    stitch_sample TEXT,
    description TEXT,
    notes TEXT,
    other_materials TEXT,
    tags TEXT NOT NULL DEFAULT '[]',
    link VARCHAR(2048),
    link_archive VARCHAR(2048),
    is_ai_enhanced INTEGER NOT NULL DEFAULT 0 CHECK (is_ai_enhanced IN (0, 1)),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX projects_owner_updated ON projects(owner_id, updated_at DESC, id DESC);
CREATE INDEX projects_owner_category ON projects(owner_id, category_id);

CREATE TABLE project_steps (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    description TEXT,
    step_number INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX project_steps_order ON project_steps(project_id, step_number, id);

CREATE TABLE project_yarns (
    project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    yarn_id INTEGER NOT NULL REFERENCES yarns(id) ON DELETE CASCADE,
    PRIMARY KEY (project_id, yarn_id)
);

CREATE INDEX project_yarns_yarn ON project_yarns(yarn_id);

CREATE TABLE user_favorites (
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, project_id)
);

CREATE INDEX user_favorites_project ON user_favorites(project_id);
