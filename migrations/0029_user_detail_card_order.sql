-- Per-user order of the reorderable sections on the project and yarn detail pages
-- (shared by the web app and Android). A comma-separated list of section ids;
-- NULL means the default order. The ids are validated by detail_order.rs.
ALTER TABLE users ADD COLUMN detail_order_project TEXT;
ALTER TABLE users ADD COLUMN detail_order_yarn TEXT;
