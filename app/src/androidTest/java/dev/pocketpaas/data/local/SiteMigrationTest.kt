package dev.pocketpaas.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SiteMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val databaseName = "site-migration-test-${UUID.randomUUID()}.db"

    @get:Rule
    val helper = MigrationTestHelper(instrumentation, PocketPaaSDatabase::class.java)

    @After
    fun deleteTestDatabase() {
        instrumentation.targetContext.deleteDatabase(databaseName)
    }

    @Test
    fun migrationFromVersionOnePreservesInstallationAndCreatesEmptySitesTable() {
        val installationId = "existing-installation-${UUID.randomUUID()}"
        val createdAt = 1_700_000_000_000L

        helper.createDatabase(databaseName, 1).use { database ->
            database.execSQL(
                "INSERT INTO platform_settings (id, installationId, createdAtEpochMillis) VALUES (?, ?, ?)",
                arrayOf<Any>(PlatformSettingsEntity.SINGLETON_ID, installationId, createdAt),
            )
        }

        helper.runMigrationsAndValidate(
            databaseName,
            5,
            true,
            DatabaseMigrations.MIGRATION_1_2,
            DatabaseMigrations.MIGRATION_2_3,
            DatabaseMigrations.MIGRATION_3_4,
            DatabaseMigrations.MIGRATION_4_5,
        ).use { database ->
            assertEquals(5, database.version)
            database.query(
                "SELECT id, installationId, createdAtEpochMillis, publicUrl FROM platform_settings ORDER BY id",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(PlatformSettingsEntity.SINGLETON_ID, cursor.getInt(0))
                assertEquals(installationId, cursor.getString(1))
                assertEquals(createdAt, cursor.getLong(2))
                assertTrue(cursor.isNull(3))
                assertFalse(cursor.moveToNext())
            }

            database.query("SELECT COUNT(*) FROM sites").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0L, cursor.getLong(0))
            }
        }
    }

    @Test
    fun migrationFromVersionFourPreservesSitesAndAddsAnOptionalUniqueCustomHostname() {
        val domainName = "legacy-site"
        helper.createDatabase(databaseName, 4).use { database ->
            database.execSQL(
                "INSERT INTO sites (domainName, internalPort, projectType, processId, publicUrl, status, logs, createdAt) " +
                    "VALUES (?, ?, ?, NULL, NULL, ?, ?, ?)",
                arrayOf<Any>(domainName, 18_000, "STATIC", "STOPPED", "legacy logs", 1_700_000_000_000L),
            )
        }

        helper.runMigrationsAndValidate(databaseName, 5, true, DatabaseMigrations.MIGRATION_4_5).use { database ->
            database.query("SELECT id, domainName, customHostname, logs FROM sites").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(domainName, cursor.getString(1))
                assertTrue(cursor.isNull(2))
                assertEquals("legacy logs", cursor.getString(3))
                assertFalse(cursor.moveToNext())
            }
            database.query("PRAGMA index_list(`sites`)").use { cursor ->
                var foundUniqueHostnameIndex = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == "index_sites_customHostname") {
                        foundUniqueHostnameIndex = cursor.getInt(2) == 1
                    }
                }
                assertTrue(foundUniqueHostnameIndex)
            }
        }
    }
}
