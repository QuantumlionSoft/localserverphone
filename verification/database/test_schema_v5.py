#!/usr/bin/env python3
"""Run the 1→5 Room migrations against SQLite and compare with the Room 5 export."""

import json
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCES = ROOT / "app/src/main/java/dev/pocketpaas/data/local"
EXPORT = ROOT / "app/schemas/dev.pocketpaas.data.local.PocketPaaSDatabase/5.json"
TRIPLE_QUOTED = re.compile(r'"""(.*?)"""', re.DOTALL)
SINGLE_QUOTED = re.compile(r'"((?:CREATE|ALTER)[^"]*)"')


def statements(path):
    text = path.read_text(encoding="utf-8")
    return [match.group(1).strip() for match in TRIPLE_QUOTED.finditer(text)] + [
        match.group(1).strip() for match in SINGLE_QUOTED.finditer(text)
    ]


def table_info(connection, table):
    return {row[1]: row for row in connection.execute(f"PRAGMA table_info(`{table}`)")}


class SchemaV5Test(unittest.TestCase):
    def setUp(self):
        self.connection = sqlite3.connect(":memory:")
        self.addCleanup(self.connection.close)
        self.connection.execute(
            "CREATE TABLE `platform_settings` (`id` INTEGER NOT NULL, "
            "`installationId` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, "
            "PRIMARY KEY(`id`))"
        )
        self.connection.execute(
            "INSERT INTO `platform_settings` VALUES (1, 'existing-installation', 1234)"
        )
        for filename in ("SiteSchema.kt", "ServerStateSchema.kt", "PlatformFeatureSchemas.kt", "CustomHostnameSchema.kt"):
            for sql in statements(SOURCES / filename):
                self.connection.execute(sql)
        self.connection.commit()

    def test_room_export_matches_migrated_schema_and_preserves_existing_rows(self):
        exported = json.loads(EXPORT.read_text(encoding="utf-8"))
        self.assertEqual(5, exported["database"]["version"])
        self.assertEqual("existing-installation", self.connection.execute(
            "SELECT installationId FROM platform_settings WHERE id = 1"
        ).fetchone()[0])
        site_columns = table_info(self.connection, "sites")
        self.assertIn("customHostname", site_columns)
        self.assertEqual(0, site_columns["customHostname"][3])

        expected_tables = {entity["tableName"]: entity for entity in exported["database"]["entities"]}
        actual_tables = {
            row[0]
            for row in self.connection.execute(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' "
                "AND name != 'room_master_table'"
            )
        }
        self.assertEqual(set(expected_tables), actual_tables)
        for table, entity in expected_tables.items():
            actual_columns = table_info(self.connection, table)
            for field in entity["fields"]:
                column = field["columnName"]
                self.assertIn(column, actual_columns, f"{table}.{column} missing")
                row = actual_columns[column]
                self.assertEqual(field["affinity"], row[2].upper(), f"{table}.{column} affinity")
                self.assertEqual(field.get("notNull", False), bool(row[3]), f"{table}.{column} nullability")

    def test_4_to_5_migration_preserves_existing_sites_and_adds_nullable_hostname(self):
        connection = sqlite3.connect(":memory:")
        self.addCleanup(connection.close)
        connection.execute(
            "CREATE TABLE sites (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, domainName TEXT NOT NULL, "
            "internalPort INTEGER NOT NULL, projectType TEXT NOT NULL, processId INTEGER, publicUrl TEXT, "
            "status TEXT NOT NULL, logs TEXT NOT NULL, createdAt INTEGER NOT NULL)"
        )
        connection.execute("CREATE UNIQUE INDEX index_sites_domainName ON sites (domainName)")
        connection.execute("CREATE UNIQUE INDEX index_sites_internalPort ON sites (internalPort)")
        connection.execute(
            "INSERT INTO sites (domainName, internalPort, projectType, processId, publicUrl, status, logs, createdAt) "
            "VALUES ('legacy-site', 18000, 'STATIC', NULL, NULL, 'STOPPED', 'existing logs', 1234)"
        )
        for sql in statements(SOURCES / "CustomHostnameSchema.kt"):
            connection.execute(sql)

        row = connection.execute(
            "SELECT domainName, customHostname, logs, createdAt FROM sites WHERE id = 1"
        ).fetchone()
        self.assertEqual(("legacy-site", None, "existing logs", 1234), row)
        indexes = {entry[1]: bool(entry[2]) for entry in connection.execute("PRAGMA index_list(sites)")}
        self.assertTrue(indexes["index_sites_customHostname"])

    def test_custom_hostname_index_allows_null_but_rejects_duplicate_dns_names(self):
        insert = (
            "INSERT INTO sites (domainName, internalPort, projectType, processId, publicUrl, "
            "status, logs, createdAt, customHostname) VALUES (?, ?, 'STATIC', NULL, NULL, 'STOPPED', '', 1, ?)"
        )
        self.connection.execute(insert, ("site-one", 18000, "www.example.com"))
        self.connection.execute(insert, ("site-two", 18001, None))
        self.connection.execute(insert, ("site-three", 18002, None))
        with self.assertRaises(sqlite3.IntegrityError):
            self.connection.execute(insert, ("site-four", 18003, "www.example.com"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
