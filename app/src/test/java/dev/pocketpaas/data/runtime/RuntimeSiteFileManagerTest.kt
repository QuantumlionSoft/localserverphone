package dev.pocketpaas.data.runtime

import dev.pocketpaas.domain.files.SiteFileNotFoundException
import dev.pocketpaas.domain.files.SiteFileNotRegularException
import dev.pocketpaas.domain.files.SiteFileTooLargeException
import dev.pocketpaas.domain.model.ProjectType
import dev.pocketpaas.domain.model.RuntimeLayout
import dev.pocketpaas.domain.model.Site
import dev.pocketpaas.domain.model.SiteCollectionState
import dev.pocketpaas.domain.model.SiteStatus
import dev.pocketpaas.domain.model.SiteNotFoundException
import dev.pocketpaas.domain.repository.SiteRepository
import dev.pocketpaas.domain.system.RuntimeFileEntry
import dev.pocketpaas.domain.system.RuntimeFileStore
import dev.pocketpaas.domain.system.RuntimeLayoutProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSiteFileManagerTest {
    @Test
    fun listsAndEditsOnlyFilesUnderRegisteredProjectRoot(): Unit = runBlocking {
        val files = MemoryFiles()
        val manager = manager(files)
        manager.createDirectory(1, "assets")
        manager.write(1, "index.html", "<h1>Hello</h1>")
        manager.write(1, "assets/site.css", "body { color: red; }")
        files.contents["$PROJECTS/example.com/.env"] = "secret"

        assertEquals("<h1>Hello</h1>", manager.read(1, "index.html"))
        assertEquals(listOf("assets", "index.html"), manager.list(1).map { it.name })
        assertEquals(listOf("site.css"), manager.list(1, "assets").map { it.name })
        assertTrue(files.contents.keys.all { it.startsWith("$PROJECTS/example.com/") })
        assertFalse(manager.list(1).any { it.name == ".env" })
    }

    @Test
    fun rejectsTraversalAbsolutePathsAndHiddenComponents(): Unit = runBlocking {
        val manager = manager(MemoryFiles())
        listOf("../other/index.html", "/etc/passwd", "assets/../../logs", ".env", "assets/.secret")
            .forEach { path ->
                assertThrows(IllegalArgumentException::class.java) {
                    runBlocking { manager.read(1, path) }
                }
            }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { manager.write(1, "node_modules/package.json", "private") }
        }
    }

    @Test
    fun missingSitesAndFilesAreReportedWithoutEscaping(): Unit = runBlocking {
        val manager = manager(MemoryFiles())
        assertThrows(SiteNotFoundException::class.java) {
            runBlocking { manager.read(999, "index.html") }
        }
        assertThrows(SiteFileNotFoundException::class.java) {
            runBlocking { manager.read(1, "missing.txt") }
        }
    }

    @Test
    fun enforcesTextLimitAndDoesNotDeleteDirectoriesAsFiles(): Unit = runBlocking {
        val manager = manager(MemoryFiles())
        assertThrows(SiteFileTooLargeException::class.java) {
            runBlocking { manager.write(1, "large.txt", "x".repeat(32 * 1024 + 1)) }
        }
        manager.createDirectory(1, "folder")
        assertThrows(SiteFileNotRegularException::class.java) {
            runBlocking { manager.delete(1, "folder") }
        }
        manager.write(1, "folder/file.txt", "ok")
        manager.delete(1, "folder/file.txt")
        assertThrows(SiteFileNotFoundException::class.java) {
            runBlocking { manager.read(1, "folder/file.txt") }
        }
    }

    private fun manager(files: RuntimeFileStore) = RuntimeSiteFileManager(
        sites = Sites(),
        layoutProvider = RuntimeLayoutProvider {
            RuntimeLayout(
                ROOT, PROJECTS, "$ROOT/nginx/nginx.conf", "$ROOT/nginx/conf.d", "$ROOT/runtime", "$ROOT/logs",
            )
        },
        files = files,
    )

    private class Sites : SiteRepository {
        private val site = Site(1, "example.com", 18000, ProjectType.STATIC, null, null, SiteStatus.STOPPED, "", 1)
        override val sites: StateFlow<List<Site>> = MutableStateFlow(listOf(site))
        override val state: StateFlow<SiteCollectionState> = MutableStateFlow(SiteCollectionState.Ready(listOf(site)))
        override fun observeSite(id: Long): Flow<Site?> = MutableStateFlow(site.takeIf { it.id == id })
        override fun observeSitesByStatus(status: SiteStatus): Flow<List<Site>> = MutableStateFlow(listOf(site).filter { it.status == status })
        override suspend fun listSites(): List<Site> = listOf(site)
        override suspend fun getSite(id: Long): Site? = site.takeIf { it.id == id }
        override suspend fun createSite(domainName: String, projectType: ProjectType): Site = error("Unused")
        override suspend fun updateRuntimeState(id: Long, status: SiteStatus, processId: Int?) = Unit
        override suspend fun compareAndSetRuntimeState(id: Long, expectedStatus: SiteStatus, newStatus: SiteStatus, processId: Int?) = false
        override suspend fun updatePublicUrl(id: Long, publicUrl: String?) = Unit
        override suspend fun appendLogs(id: Long, text: String) = Unit
        override suspend fun clearLogs(id: Long) = Unit
        override suspend fun deleteSiteIfInactive(id: Long) = Unit
    }

    private class MemoryFiles : RuntimeFileStore {
        val contents = linkedMapOf<String, String>()
        private val directories = linkedSetOf(ROOT, PROJECTS, "$PROJECTS/example.com")
        override suspend fun writeTextAtomic(path: String, text: String) {
            require(path.substringBeforeLast('/') in directories)
            contents[path] = text
        }
        override suspend fun readTextOrNull(path: String): String? = contents[path]
        override suspend fun delete(path: String) { contents.remove(path) }
        override suspend fun ensureDirectory(path: String) {
            val parent = path.substringBeforeLast('/')
            require(parent in directories || directories.any { parent.startsWith("$it/") })
            directories.add(path)
        }
        override suspend fun listFiles(directory: String): List<String> = contents.keys.filter {
            it.substringBeforeLast('/') == directory
        }.sorted()
        override suspend fun listEntries(directory: String): List<RuntimeFileEntry> {
            require(directory in directories)
            val files = contents.keys.filter { it.substringBeforeLast('/') == directory }.map { path ->
                RuntimeFileEntry(path.substringAfterLast('/'), false, contents.getValue(path).toByteArray().size.toLong())
            }
            val subdirectories = directories.filter {
                it != directory && it.substringBeforeLast('/') == directory
            }.map { path -> RuntimeFileEntry(path.substringAfterLast('/'), true, null) }
            return (subdirectories + files).sortedBy { it.name }
        }
    }

    private companion object {
        const val ROOT = "/termux/home/.pocket-paas"
        const val PROJECTS = "$ROOT/htdocs"
    }
}
