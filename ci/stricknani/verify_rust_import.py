#!/usr/bin/env python3
"""Verify an isolated Rust import against a copied legacy SQLite snapshot."""

from __future__ import annotations

import argparse
import hashlib
import json
import sqlite3
import sys
from collections import Counter, defaultdict
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Never


class VerificationError(Exception):
    """Safe verifier error; never includes input paths or database values."""


class Parser(argparse.ArgumentParser):
    def error(self, _message: str) -> Never:
        raise VerificationError("invalid_arguments")


def canonical_path(value: str, *, directory: bool) -> Path:
    path = Path(value)
    try:
        resolved = path.resolve(strict=True)
    except OSError as error:
        raise VerificationError("invalid_input_path") from error
    if not path.is_absolute() or path != resolved:
        raise VerificationError("input_path_not_canonical")
    if directory != resolved.is_dir() or (not directory and not resolved.is_file()):
        raise VerificationError("input_path_wrong_type")
    return resolved


def open_immutable(path: Path) -> sqlite3.Connection:
    for suffix in ("-wal", "-shm"):
        if path.with_name(path.name + suffix).exists():
            raise VerificationError("sqlite_sidecar_present")
    uri = path.as_uri() + "?mode=ro&immutable=1"
    try:
        connection = sqlite3.connect(uri, uri=True, timeout=5)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA query_only = ON")
        return connection
    except sqlite3.Error as error:
        raise VerificationError("sqlite_open_failed") from error


def count(connection: sqlite3.Connection, table: str) -> int:
    return int(connection.execute(f'SELECT COUNT(*) FROM "{table}"').fetchone()[0])


def all_rows(connection: sqlite3.Connection, query: str) -> list[sqlite3.Row]:
    return list(connection.execute(query))


def timestamp(value: Any) -> datetime | None:
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return datetime.fromtimestamp(value, UTC)
    parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    return (
        parsed.replace(tzinfo=UTC) if parsed.tzinfo is None else parsed.astimezone(UTC)
    )


def epoch_seconds(value: Any) -> int | None:
    parsed = timestamp(value)
    return None if parsed is None else int(parsed.timestamp())


def file_digest(path: Path, root: Path) -> bytes:
    try:
        resolved = path.resolve(strict=True)
        if (
            resolved != path
            or not resolved.is_file()
            or not resolved.is_relative_to(root)
        ):
            raise VerificationError("unsafe_media_path")
        digest = hashlib.sha256()
        with resolved.open("rb") as source:
            while chunk := source.read(1024 * 1024):
                digest.update(chunk)
        return digest.digest()
    except OSError as error:
        raise VerificationError("media_read_failed") from error


def files_match(
    source: Path, target: Path, source_root: Path, target_root: Path
) -> bool:
    try:
        return file_digest(source, source_root) == file_digest(target, target_root)
    except VerificationError:
        return False


def compare_rows(
    report: dict[str, Any],
    name: str,
    source_rows: list[Any],
    target_rows: list[Any],
    *,
    allow_target_extras: bool = False,
) -> None:
    source = {row[0]: tuple(row[1:]) for row in source_rows}
    target = {row[0]: tuple(row[1:]) for row in target_rows}
    missing = source.keys() - target.keys()
    different = {
        key for key in source.keys() & target.keys() if source[key] != target[key]
    }
    extra = target.keys() - source.keys()
    report["checks"][name] = (
        not missing and not different and (allow_target_extras or not extra)
    )
    if missing:
        report["mismatches"].append(f"{name}.missing_rows:{len(missing)}")
    if different:
        report["mismatches"].append(f"{name}.changed_rows:{len(different)}")
    if extra and not allow_target_extras:
        report["mismatches"].append(f"{name}.extra_rows:{len(extra)}")


def check_counts(
    report: dict[str, Any],
    name: str,
    source_count: int,
    target_count: int,
    *,
    allow_target_extras: bool = False,
) -> None:
    report["counts"][name] = {"source": source_count, "target": target_count}
    passed = (
        target_count >= source_count
        if allow_target_extras
        else target_count == source_count
    )
    report["checks"][f"count_{name}"] = passed
    if not passed:
        report["mismatches"].append(f"count_{name}")


def table_names(connection: sqlite3.Connection) -> set[str]:
    return {
        str(row[0])
        for row in connection.execute(
            "SELECT name FROM sqlite_master WHERE type='table'"
        )
    }


def compare_import(
    source_db: Path, source_media: Path, destination: Path
) -> dict[str, Any]:
    rust_db = destination / "database.sqlite3"
    rust_media = destination / "media"
    if (
        not rust_db.is_file()
        or not rust_media.is_dir()
        or rust_db.resolve(strict=True) != rust_db
    ):
        raise VerificationError("destination_incomplete")
    source = open_immutable(source_db)
    target = open_immutable(rust_db)
    report: dict[str, Any] = {"checks": {}, "counts": {}, "mismatches": []}
    try:
        source_tables = table_names(source)
        target_tables = table_names(target)
        required_source = {
            "users",
            "api_tokens",
            "categories",
            "yarns",
            "projects",
            "steps",
            "project_yarns",
            "user_favorites",
            "user_favorite_yarns",
            "images",
            "attachments",
            "yarn_images",
            "audit_logs",
        }
        required_target = {
            "users",
            "api_tokens",
            "categories",
            "yarns",
            "projects",
            "project_steps",
            "project_yarns",
            "user_favorites",
            "user_favorite_yarns",
            "media_files",
            "media_url_aliases",
            "user_avatars",
            "audit_events",
        }
        report["checks"]["required_schema"] = (
            required_source <= source_tables and required_target <= target_tables
        )
        if not report["checks"]["required_schema"]:
            report["mismatches"].append("required_schema")
            return report

        source_integrity = (
            source.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        )
        report["checks"]["source_integrity"] = source_integrity
        if not source_integrity:
            report["mismatches"].append("source_integrity")

        source_fk = list(source.execute("PRAGMA foreign_key_check"))
        source_fk = [
            row
            for row in source_fk
            if not (row[0] == "audit_logs" and row[2] == "users")
        ]
        target_fk = list(target.execute("PRAGMA foreign_key_check"))
        report["checks"]["source_relationships"] = not source_fk
        report["checks"]["target_foreign_keys"] = not target_fk
        if source_fk:
            report["mismatches"].append(f"source_relationships:{len(source_fk)}")
        if target_fk:
            report["mismatches"].append(f"target_foreign_keys:{len(target_fk)}")

        mappings = [
            ("users", "users", False),
            ("api_tokens", "api_tokens", False),
            ("categories", "categories", True),
            ("yarns", "yarns", False),
            ("projects", "projects", False),
            ("steps", "project_steps", False),
            ("project_yarns", "project_yarns", False),
            ("user_favorites", "user_favorites", False),
            ("user_favorite_yarns", "user_favorite_yarns", False),
        ]
        for source_name, target_name, allow_extra in mappings:
            check_counts(
                report,
                source_name,
                count(source, source_name),
                count(target, target_name),
                allow_target_extras=allow_extra,
            )

        compare_rows(
            report,
            "user_identity_and_passwords",
            [
                (
                    row["id"],
                    row["email"],
                    row["hashed_password"],
                    row["is_active"],
                    row["is_admin"],
                    row["token_version"],
                    timestamp(row["created_at"]),
                    row["profile_image"],
                )
                for row in all_rows(
                    source,
                    "SELECT id,email,hashed_password,is_active,is_admin,token_version,"
                    "created_at,profile_image FROM users ORDER BY id",
                )
            ],
            [
                (
                    row["id"],
                    row["legacy_email"],
                    row["password_hash"],
                    row["active"],
                    row["is_admin"],
                    row["legacy_token_version"],
                    timestamp(row["created_at"]),
                    row["profile_image"],
                )
                for row in all_rows(
                    target,
                    "SELECT id,legacy_email,password_hash,active,is_admin,"
                    "legacy_token_version,created_at,profile_image "
                    "FROM users ORDER BY id",
                )
            ],
        )
        # Login email is normalized; exact source spelling remains in legacy_email.
        old_emails = {
            row[0]: str(row[1]).strip().lower()
            for row in source.execute("SELECT id,email FROM users")
        }
        new_emails = {
            row[0]: row[1] for row in target.execute("SELECT id,email FROM users")
        }
        normalized_email_ok = old_emails == new_emails
        report["checks"]["normalized_login_emails"] = normalized_email_ok
        if not normalized_email_ok:
            report["mismatches"].append("normalized_login_emails")

        token_source = all_rows(
            source,
            "SELECT id,user_id,token_hash,name,created_at,last_used_at,expires_at "
            "FROM api_tokens ORDER BY id",
        )
        token_target = all_rows(
            target,
            "SELECT id,user_id,token_hash,name,created_at,last_used_at,expires_at "
            "FROM api_tokens ORDER BY id",
        )
        token_source_map = {row[0]: row for row in token_source}
        token_target_map = {row[0]: row for row in token_target}
        token_ok = token_source_map.keys() <= token_target_map.keys()
        if token_ok:
            for token_id, source_token in token_source_map.items():
                target_token = token_target_map[token_id]
                if (source_token[1], source_token[2], source_token[3]) != (
                    target_token[1],
                    target_token[2],
                    target_token[3],
                ):
                    token_ok = False
                    break
                source_token_times = tuple(
                    epoch_seconds(source_token[index]) for index in (4, 5, 6)
                )
                target_token_times = tuple(target_token[index] for index in (4, 5, 6))
                if source_token_times != target_token_times:
                    token_ok = False
                    break
        report["checks"]["api_token_hashes_and_timestamps"] = token_ok
        if not token_ok:
            report["mismatches"].append("api_token_hashes_and_timestamps")

        compare_rows(
            report,
            "yarn_ownership",
            all_rows(source, "SELECT id, owner_id FROM yarns ORDER BY id"),
            all_rows(target, "SELECT id, owner_id FROM yarns ORDER BY id"),
        )
        compare_rows(
            report,
            "project_ownership_and_legacy_yarn",
            all_rows(source, "SELECT id, owner_id, yarn FROM projects ORDER BY id"),
            all_rows(
                target,
                "SELECT id, owner_id, legacy_yarn_text FROM projects ORDER BY id",
            ),
        )
        compare_rows(
            report,
            "yarn_record_fields_and_timestamps",
            [
                (
                    row["id"],
                    row["owner_id"],
                    row["name"],
                    row["description"],
                    row["brand"],
                    row["colorway"],
                    row["dye_lot"],
                    row["fiber_content"],
                    row["weight_category"],
                    row["recommended_needles"],
                    row["weight_grams"],
                    row["length_meters"],
                    row["notes"],
                    row["link"],
                    row["link_archive"],
                    timestamp(row["link_archive_requested_at"]),
                    row["link_archive_failed"],
                    row["is_ai_enhanced"],
                    timestamp(row["created_at"]),
                    timestamp(row["updated_at"]),
                )
                for row in all_rows(source, "SELECT * FROM yarns ORDER BY id")
            ],
            [
                (
                    row["id"],
                    row["owner_id"],
                    row["name"],
                    row["description"],
                    row["brand"],
                    row["colorway"],
                    row["dye_lot"],
                    row["fiber_content"],
                    row["weight_category"],
                    row["recommended_needles"],
                    row["weight_grams"],
                    row["length_meters"],
                    row["notes"],
                    row["link"],
                    row["link_archive"],
                    timestamp(row["archive_requested_at"]),
                    row["archive_failed"],
                    row["is_ai_enhanced"],
                    timestamp(row["created_at"]),
                    timestamp(row["updated_at"]),
                )
                for row in all_rows(target, "SELECT * FROM yarns ORDER BY id")
            ],
        )
        compare_rows(
            report,
            "project_record_fields_and_timestamps",
            [
                (
                    row["id"],
                    row["owner_id"],
                    row["name"],
                    row["needles"],
                    row["stitch_sample"],
                    row["description"],
                    row["notes"],
                    row["other_materials"],
                    row["tags"] or "[]",
                    row["link"],
                    row["link_archive"],
                    row["is_ai_enhanced"],
                    timestamp(row["created_at"]),
                    timestamp(row["updated_at"]),
                    row["yarn"],
                    timestamp(row["link_archive_requested_at"]),
                    row["link_archive_failed"],
                )
                for row in all_rows(source, "SELECT * FROM projects ORDER BY id")
            ],
            [
                (
                    row["id"],
                    row["owner_id"],
                    row["name"],
                    row["needles"],
                    row["stitch_sample"],
                    row["description"],
                    row["notes"],
                    row["other_materials"],
                    row["tags"],
                    row["link"],
                    row["link_archive"],
                    row["is_ai_enhanced"],
                    timestamp(row["created_at"]),
                    timestamp(row["updated_at"]),
                    row["legacy_yarn_text"],
                    timestamp(row["archive_requested_at"]),
                    row["archive_failed"],
                )
                for row in all_rows(target, "SELECT * FROM projects ORDER BY id")
            ],
        )

        for source_table, target_table, source_cols, target_cols, key in (
            (
                "categories",
                "categories",
                "id,user_id,name,created_at",
                "id,user_id,name,created_at",
                "id",
            ),
            (
                "steps",
                "project_steps",
                "id,project_id,title,description,step_number,created_at",
                "id,project_id,title,description,step_number,created_at",
                "id",
            ),
            (
                "project_yarns",
                "project_yarns",
                "project_id,yarn_id",
                "project_id,yarn_id",
                None,
            ),
            (
                "user_favorites",
                "user_favorites",
                "user_id,project_id",
                "user_id,project_id",
                None,
            ),
            (
                "user_favorite_yarns",
                "user_favorite_yarns",
                "user_id,yarn_id",
                "user_id,yarn_id",
                None,
            ),
        ):
            if key:
                source_query_rows = all_rows(
                    source, f"SELECT {source_cols} FROM {source_table} ORDER BY {key}"
                )
                target_query_rows = all_rows(
                    target, f"SELECT {target_cols} FROM {target_table} ORDER BY {key}"
                )
                if source_table in ("categories", "steps"):
                    source_row_values = [
                        (row[0], *row[1:-1], timestamp(row[-1]))
                        for row in source_query_rows
                    ]
                    target_row_values = [
                        (row[0], *row[1:-1], timestamp(row[-1]))
                        for row in target_query_rows
                    ]
                else:
                    source_row_values = [tuple(row) for row in source_query_rows]
                    target_row_values = [tuple(row) for row in target_query_rows]
                compare_rows(
                    report,
                    f"{source_table}_records",
                    source_row_values,
                    target_row_values,
                    allow_target_extras=source_table == "categories",
                )
            else:
                source_pair_counts = Counter(
                    tuple(row)
                    for row in all_rows(
                        source, f"SELECT {source_cols} FROM {source_table}"
                    )
                )
                target_pair_counts = Counter(
                    tuple(row)
                    for row in all_rows(
                        target, f"SELECT {target_cols} FROM {target_table}"
                    )
                )
                ok = source_pair_counts == target_pair_counts
                report["checks"][f"{source_table}_records"] = ok
                if not ok:
                    report["mismatches"].append(f"{source_table}_records")

        source_media_records: list[tuple[str, sqlite3.Row]] = []
        for table in ("images", "attachments", "yarn_images"):
            source_media_records.extend(
                (table, row)
                for row in all_rows(source, f"SELECT * FROM {table} ORDER BY id")
            )
        target_media = {
            (row["legacy_source"], row["legacy_id"]): row
            for row in target.execute(
                "SELECT * FROM media_files WHERE legacy_source IS NOT NULL"
            )
        }
        media_ok = True
        namespace_ok = True
        for table, row in source_media_records:
            legacy_media_key = (table, row["id"])
            imported = target_media.get(legacy_media_key)
            if imported is None:
                media_ok = False
                continue
            if table == "images":
                subdir, parent_id = "projects", row["project_id"]
                values = (
                    imported["owner_id"],
                    imported["project_id"],
                    imported["step_id"],
                    imported["kind"],
                    imported["original_filename"],
                    imported["alt_text"],
                    imported["legacy_image_type"],
                    imported["is_stitch_sample"],
                    imported["legacy_is_title_image"],
                    timestamp(imported["created_at"]),
                )
                wanted = (
                    None,
                    row["project_id"],
                    row["step_id"],
                    "step" if row["step_id"] is not None else "title",
                    row["original_filename"],
                    row["alt_text"],
                    row["image_type"],
                    row["is_stitch_sample"],
                    row["is_title_image"],
                    timestamp(row["created_at"]),
                )
                owner = source.execute(
                    "SELECT owner_id FROM projects WHERE id=?", (parent_id,)
                ).fetchone()[0]
                wanted = (owner, *wanted[1:])
                media_ok &= values == wanted and imported["is_primary"] == 0
                namespace_ok &= imported["public_id"] == row["id"]
            elif table == "attachments":
                subdir, parent_id = "projects", row["project_id"]
                owner = source.execute(
                    "SELECT owner_id FROM projects WHERE id=?", (parent_id,)
                ).fetchone()[0]
                media_ok &= (
                    imported["owner_id"],
                    imported["project_id"],
                    imported["kind"],
                    imported["original_filename"],
                    imported["content_type"],
                    imported["size_bytes"],
                    timestamp(imported["created_at"]),
                ) == (
                    owner,
                    parent_id,
                    "attachment",
                    row["original_filename"],
                    row["content_type"],
                    row["size_bytes"],
                    timestamp(row["created_at"]),
                )
                namespace_ok &= imported["public_id"] == row["id"]
            else:
                subdir, parent_id = "yarns", row["yarn_id"]
                owner = source.execute(
                    "SELECT owner_id FROM yarns WHERE id=?", (parent_id,)
                ).fetchone()[0]
                media_ok &= (
                    imported["owner_id"],
                    imported["yarn_id"],
                    imported["kind"],
                    imported["original_filename"],
                    imported["alt_text"],
                    imported["is_primary"],
                    timestamp(imported["created_at"]),
                ) == (
                    owner,
                    parent_id,
                    "photo",
                    row["original_filename"],
                    row["alt_text"],
                    row["is_primary"],
                    timestamp(row["created_at"]),
                )
                namespace_ok &= imported["public_id"] == row["id"]
            source_path = source_media / subdir / str(parent_id) / row["filename"]
            target_path = rust_media / subdir / str(parent_id) / imported["filename"]
            media_ok &= files_match(source_path, target_path, source_media, rust_media)
        for table in ("images", "attachments", "yarn_images"):
            check_counts(
                report,
                f"media_{table}",
                count(source, table),
                int(
                    target.execute(
                        "SELECT COUNT(*) FROM media_files WHERE legacy_source=?",
                        (table,),
                    ).fetchone()[0]
                ),
            )
        report["checks"]["media_records_and_original_bytes"] = media_ok
        report["checks"]["media_public_id_namespaces"] = namespace_ok
        if not media_ok:
            report["mismatches"].append("media_records_and_original_bytes")
        if not namespace_ok:
            report["mismatches"].append("media_public_id_namespaces")

        alias_ok = True
        target_aliases = {
            (row["subdir"], row["parent_id"], row["legacy_filename"]): row["media_id"]
            for row in target.execute("SELECT * FROM media_url_aliases")
        }
        expected_alias_records: dict[tuple[str, int, str], set[tuple[str, int]]] = (
            defaultdict(set)
        )
        for table, row in source_media_records:
            subdir = "yarns" if table == "yarn_images" else "projects"
            parent_id = row["yarn_id"] if table == "yarn_images" else row["project_id"]
            expected_alias_records[(subdir, parent_id, row["filename"])].add(
                (table, row["id"])
            )
        for alias_key, expected_records in expected_alias_records.items():
            alias_media_id = target_aliases.get(alias_key)
            if alias_media_id is None:
                alias_ok = False
                continue
            associated = target.execute(
                "SELECT legacy_source,legacy_id FROM media_files WHERE id=?",
                (alias_media_id,),
            ).fetchone()
            if (
                associated is None
                or (associated["legacy_source"], associated["legacy_id"])
                not in expected_records
            ):
                alias_ok = False
        report["checks"]["legacy_media_aliases"] = alias_ok
        if not alias_ok:
            report["mismatches"].append("legacy_media_aliases")

        category_targets = {
            (row["user_id"], row["name"])
            for row in target.execute("SELECT user_id,name FROM categories")
        }
        category_links_ok = all(
            row["category"] is None
            or (row["owner_id"], row["category"]) in category_targets
            for row in source.execute("SELECT owner_id,category FROM projects")
        )
        report["checks"]["project_category_ownership"] = category_links_ok
        if not category_links_ok:
            report["mismatches"].append("project_category_ownership")

        avatar_rows = all_rows(
            source,
            "SELECT id,profile_image FROM users WHERE profile_image IS NOT NULL "
            "ORDER BY id",
        )
        avatars = {
            row["user_id"]: row for row in target.execute("SELECT * FROM user_avatars")
        }
        avatar_ok = len(avatar_rows) == len(avatars)
        for user_id, filename in avatar_rows:
            imported = avatars.get(user_id)
            if imported is None or imported["original_filename"] != filename:
                avatar_ok = False
                continue
            avatar_ok &= files_match(
                source_media / "users" / str(user_id) / filename,
                rust_media / "users" / str(user_id) / imported["filename"],
                source_media,
                rust_media,
            )
        report["counts"]["user_avatars"] = {
            "source": len(avatar_rows),
            "target": len(avatars),
        }
        report["checks"]["avatars_and_original_bytes"] = avatar_ok
        if not avatar_ok:
            report["mismatches"].append("avatars_and_original_bytes")

        audit_rows = all_rows(
            source,
            "SELECT id,actor_user_id,entity_type,entity_id,action,details,created_at "
            "FROM audit_logs ORDER BY id",
        )
        known_users = {row[0] for row in source.execute("SELECT id FROM users")}
        audit_target = {
            row["id"]: row for row in target.execute("SELECT * FROM audit_events")
        }
        audit_ok = True
        for row in audit_rows:
            event = audit_target.get(row["id"])
            actor = (
                row["actor_user_id"] if row["actor_user_id"] in known_users else None
            )
            legacy_actor = row["actor_user_id"]
            if event is None or (
                event["actor_user_id"],
                event["legacy_actor_user_id"],
                event["entity_type"],
                event["entity_id"],
                event["action"],
                event["details"],
                timestamp(event["created_at"]),
            ) != (
                actor,
                legacy_actor,
                row["entity_type"],
                row["entity_id"],
                row["action"],
                row["details"],
                timestamp(row["created_at"]),
            ):
                audit_ok = False
        report["counts"]["audit_events"] = {
            "legacy": len(audit_rows),
            "rust_total": len(audit_target),
            "native_extra": max(0, len(audit_target) - len(audit_rows)),
        }
        report["checks"]["legacy_audit_preserved"] = audit_ok
        if not audit_ok:
            report["mismatches"].append("legacy_audit_preserved")
        return report
    except (
        sqlite3.Error,
        KeyError,
        TypeError,
        ValueError,
        OverflowError,
        AttributeError,
        IndexError,
    ) as error:
        raise VerificationError("database_schema_or_data_invalid") from error
    finally:
        source.close()
        target.close()


def main(argv: list[str] | None = None) -> int:
    parser = Parser(description=__doc__)
    parser.add_argument("--source-db", required=True)
    parser.add_argument("--source-media", required=True)
    parser.add_argument("--destination", required=True)
    try:
        args = parser.parse_args(argv)
        source_db = canonical_path(args.source_db, directory=False)
        source_media = canonical_path(args.source_media, directory=True)
        destination = canonical_path(args.destination, directory=True)
        rust_media = destination / "media"
        if source_db == destination / "database.sqlite3":
            raise VerificationError("source_and_destination_must_be_separate")
        if (
            source_media == rust_media
            or source_media in rust_media.parents
            or rust_media.is_relative_to(source_media)
        ):
            raise VerificationError("source_and_destination_must_be_separate")
        report = compare_import(source_db, source_media, destination)
    except VerificationError as error:
        report = {"checks": {}, "counts": {}, "mismatches": [str(error)]}
    except (sqlite3.Error, OSError, ValueError, TypeError, KeyError, OverflowError):
        report = {"checks": {}, "counts": {}, "mismatches": ["verification_failed"]}
    print(json.dumps(report, sort_keys=True, separators=(",", ":")))
    return int(bool(report["mismatches"]))


if __name__ == "__main__":
    sys.exit(main())
