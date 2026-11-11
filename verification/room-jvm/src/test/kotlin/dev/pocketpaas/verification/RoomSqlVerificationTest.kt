package dev.pocketpaas.verification

import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.pocketpaas.data.local.PocketPaaSDatabase
import dev.pocketpaas.data.local.PlatformFeatureSchemas
import dev.pocketpaas.data.local.CustomHostnameSchema
import dev.pocketpaas.data.local.SiteDao
import dev.pocketpaas.data.local.SiteEntity
import dev.pocketpaas.data.local.SiteSchema
import dev.pocketpaas.data.local.ServerStateSchema
import dev.pocketpaas.data.local.SshAuthorizedKeyEntity
import dev.pocketpaas.data.local.SshSettingsEntity
import dev.pocketpaas.domain.model.ActivityCategory
import dev.pocketpaas.domain.model.ActivityLevel
import dev.pocketpaas.domain.model.ProjectType
import dev.pocketpaas.domain.model.SiteStatus
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses Room's generated production DAO against real bundled SQLite on the JVM. */
class RoomSqlVerificationTest {
    private val databases = mutableListOf<PocketPaaSDatabase>()

    @After
    fun closeDatabases() {
        databases.forEach(PocketPaaSDatabase::close)
    }

    @Test
    fun concurrentPortAllocationCommitsUniqueRowsAndSkipsReservedPorts() = runBlocking {
        val dao = newDatabase().siteDao()
        val reservedPorts = listOf(12_010, 12_025, 12_050)

        val sites = List(64) { index ->
            async(Dispatchers.Default) {
                dao.createWithAllocatedPort(
                    "concurrent-$index.example",
                    ProjectType.NODEJS,
                    index.toLong(),
                    12_000,
                    12_100,
                    reservedPorts,
                )
            }
        }.awaitAll()

        assertEquals(64, sites.map(SiteEntity::id).distinct().size)
        assertEquals(64, sites.map(SiteEntity::internalPort).distinct().size)
        assertFalse(sites.any { it.internalPort in reservedPorts })
        assertEquals(64, dao.observeSites().first().size)
        assertEquals(sites.maxOf(SiteEntity::internalPort), dao.getMaxInternalPort())
    }

    @Test
    fun allocationReportsExhaustionAndReusesDeletedPortInsideTransaction() = runBlocking {
        val dao = newDatabase().siteDao()
        val first = create(dao, "first.example", 15_000, 15_002, listOf(15_001))
        val second = create(dao, "second.example", 15_000, 15_002, listOf(15_001))

        assertEquals(15_000, first.internalPort)
        assertEquals(15_002, second.internalPort)
        val failure = runCatching { create(dao, "full.example", 15_000, 15_002, listOf(15_001)) }
        assertTrue(failure.exceptionOrNull() is IllegalStateException)
        assertNull(dao.getSiteByDomainName("full.example"))
        assertEquals(1, dao.deleteSiteIfInactive(first.id))

        val replacement = create(dao, "replacement.example", 15_000, 15_002, listOf(15_001))
        assertEquals(15_000, replacement.internalPort)
        assertEquals(2, dao.observeSites().first().size)
    }

    @Test
    fun concurrentLogAppendsAreAtomicAndBoundedToTheNewestCharacters() = runBlocking {
        val dao = newDatabase().siteDao()
        val site = create(dao, "logs.example")
        val chunks = List(64) { index -> "[$index]\n" }

        chunks.map { chunk -> async(Dispatchers.Default) { dao.appendLogs(site.id, chunk, 10_000) } }
            .awaitAll()

        val completeLogs = checkNotNull(dao.getSite(site.id)).logs
        assertEquals(chunks.sumOf(String::length), completeLogs.length)
        chunks.forEach { chunk -> assertEquals(1, completeLogs.split(chunk).size - 1) }
        dao.appendLogs(site.id, "terminal-line", 16)
        assertEquals((completeLogs + "terminal-line").takeLast(16), dao.getSite(site.id)?.logs)
        assertEquals(1, dao.clearLogs(site.id))
        assertEquals("", dao.getSite(site.id)?.logs)
    }

    @Test
    fun reactiveRuntimeTransitionsCompareStatusesAndPreventActiveDeletion() = runBlocking {
        val dao = newDatabase().siteDao()
        val site = create(dao, "runtime.example")
        assertEquals(SiteStatus.STOPPED, dao.observeSite(site.id).first()?.status)

        assertEquals(
            1,
            dao.compareAndSetRuntimeState(site.id, SiteStatus.STOPPED, SiteStatus.DEPLOYING, null),
        )
        assertEquals(
            0,
            dao.compareAndSetRuntimeState(site.id, SiteStatus.STOPPED, SiteStatus.RUNNING, 1234),
        )
        dao.updateRuntimeState(site.id, SiteStatus.RUNNING, 1234)
        val running = withTimeout(10_000) {
            dao.observeSitesByStatus(SiteStatus.RUNNING).first { rows -> rows.any { it.id == site.id } }
        }
        assertEquals(1234, running.single().processId)
        assertEquals(0, dao.deleteSiteIfInactive(site.id))

        dao.updatePublicUrl(site.id, "https://runtime.trycloudflare.com")
        assertEquals("https://runtime.trycloudflare.com", dao.getSite(site.id)?.publicUrl)
        dao.updateRuntimeState(site.id, SiteStatus.STOPPED, null)
        assertEquals(1, dao.deleteSiteIfInactive(site.id))
        assertNull(dao.observeSite(site.id).first())
    }

    @Test
    fun duplicateDomainFailureRollsBackWithoutConsumingPorts() = runBlocking {
        val dao = newDatabase().siteDao()
        create(dao, "unique.example")
        val failure = runCatching { create(dao, "unique.example") }

        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
        assertEquals(listOf(20_000), dao.getUsedInternalPorts())
        assertEquals(1, dao.observeSites().first().size)
    }

    @Test
    fun migrationFromPhaseOnePreservesSettingsAndPassesGeneratedRoomSchemaValidation() = runBlocking {
        val directory = Files.createTempDirectory("pocketpaas-room-migration-").toFile()
        val file = File(directory, "migration.db")
        val driver = BundledSQLiteDriver()
        val phaseOneDatabase = Room.databaseBuilder<PhaseOneDatabase>(file.absolutePath)
            .setDriver(driver)
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        try {
            phaseOneDatabase.platformSettingsDao().getOrCreate(
                LegacyPlatformSettingsEntity(
                    installationId = "phase-one-installation",
                    createdAtEpochMillis = 12_345L,
                ),
            )
        } finally {
            phaseOneDatabase.close()
        }

        val migration = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                SiteSchema.statements.forEach { sql -> connection.execute(sql) }
            }
        }
        val database = Room.databaseBuilder<PocketPaaSDatabase>(file.absolutePath)
            .setDriver(driver)
            .setQueryCoroutineContext(Dispatchers.IO)
            .addMigrations(migration, publicUrlMigration(), featureSchemaMigration(), customHostnameMigration())
            .build()
        databases += database

        val preserved = database.platformSettingsDao().getSettings()
        assertNotNull(preserved)
        assertEquals("phase-one-installation", preserved?.installationId)
        assertEquals(12_345L, preserved?.createdAtEpochMillis)
        assertTrue(database.siteDao().observeSites().first().isEmpty())
        assertEquals(20_000, create(database.siteDao(), "after-migration.example").internalPort)
    }

    @Test
    fun phaseTwoMigrationPreservesSitesAndAddsReactiveMasterPublicUrl() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("pocketpaas-room-v2-migration-").toFile()
        val file = File(directory, "migration.db")
        val driver = BundledSQLiteDriver()
        val old = Room.databaseBuilder<PhaseTwoDatabase>(file.absolutePath)
            .setDriver(driver).setQueryCoroutineContext(Dispatchers.IO).build()
        val original: SiteEntity
        try {
            old.platformSettingsDao().getOrCreate(LegacyPlatformSettingsEntity(installationId = "v2-id", createdAtEpochMillis = 77))
            original = create(old.siteDao(), "preserved.example")
            old.siteDao().appendLogs(original.id, "existing logs", 65_536)
        } finally { old.close() }
        val database = Room.databaseBuilder<PocketPaaSDatabase>(file.absolutePath)
            .setDriver(driver).setQueryCoroutineContext(Dispatchers.IO)
            .addMigrations(publicUrlMigration(), featureSchemaMigration(), customHostnameMigration()).build().also(databases::add)
        assertEquals("v2-id", database.platformSettingsDao().getSettings()?.installationId)
        assertEquals(77L, database.platformSettingsDao().getSettings()?.createdAtEpochMillis)
        assertNull(database.platformSettingsDao().observePublicUrl().first())
        assertEquals(original.internalPort, database.siteDao().getSite(original.id)?.internalPort)
        assertEquals("existing logs", database.siteDao().getSite(original.id)?.logs)
        assertEquals(1, database.platformSettingsDao().updatePublicUrl("https://gateway.trycloudflare.com"))
        assertEquals("https://gateway.trycloudflare.com", database.platformSettingsDao().observePublicUrl().first())
        assertEquals(1, database.platformSettingsDao().updatePublicUrl(null))
        assertNull(database.platformSettingsDao().getSettings()?.publicUrl)

        // Verify v4 tables work after migration
        val settings = database.sshDao().getOrCreateSettings(SshSettingsEntity(port = 8022, updatedAt = 100L))
        assertEquals(8022, settings.port)
        val keyId = database.sshDao().insertAuthorizedKey(
            SshAuthorizedKeyEntity(name = "laptop", publicKey = "ssh-ed25519 AAAA", fingerprint = "SHA256:test1", createdAt = 100L),
        )
        assertTrue(keyId > 0)
        val eventId = database.activityEventDao().record(
            category = ActivityCategory.SSH,
            level = ActivityLevel.INFO,
            message = "SSH key added",
            detail = null,
            timestamp = 200L,
            keep = 10,
        )
        assertTrue(eventId > 0)
        assertEquals(1, database.activityEventDao().recent(10).size)
    }

    private fun publicUrlMigration(): Migration = object : Migration(2, 3) {
        override fun migrate(connection: SQLiteConnection) { connection.execute(ServerStateSchema.addPublicUrl) }
    }

    private fun featureSchemaMigration(): Migration = object : Migration(3, 4) {
        override fun migrate(connection: SQLiteConnection) {
            PlatformFeatureSchemas.statements.forEach { sql -> connection.execute(sql) }
        }
    }

    private fun customHostnameMigration(): Migration = object : Migration(4, 5) {
        override fun migrate(connection: SQLiteConnection) {
            CustomHostnameSchema.statements.forEach { sql -> connection.execute(sql) }
        }
    }

    private fun newDatabase(): PocketPaaSDatabase =
        Room.inMemoryDatabaseBuilder<PocketPaaSDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
            .also(databases::add)

    private suspend fun create(
        dao: SiteDao,
        name: String,
        minimumPort: Int = 20_000,
        maximumPort: Int = 20_100,
        reservedPorts: List<Int> = emptyList(),
    ): SiteEntity = dao.createWithAllocatedPort(
        name,
        ProjectType.STATIC,
        1L,
        minimumPort,
        maximumPort,
        reservedPorts,
    )

    private fun SQLiteConnection.execute(sql: String) {
        prepare(sql).use { statement -> statement.step() }
    }
}
