-- Opt-in privacy setting: when 1, images the user uploads from now on have their EXIF/XMP/IPTC
-- metadata (GPS, camera, dates, ...) removed before they are stored. Off (0) for every existing
-- and new account, so behaviour does not change until a user turns it on. Already stored photos
-- are never touched by this setting.
ALTER TABLE users ADD COLUMN strip_photo_metadata INTEGER NOT NULL DEFAULT 0
    CHECK (strip_photo_metadata IN (0, 1));
