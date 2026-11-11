package dev.pocketpaas.data.runtime

import dev.pocketpaas.domain.files.SiteFileEntry
import dev.pocketpaas.domain.files.SiteFileManager
import dev.pocketpaas.domain.files.SiteFileNotFoundException
import dev.pocketpaas.domain.files.SiteFileNotRegularException
import dev.pocketpaas.domain.files.SiteFileTooLargeException
import dev.pocketpaas.domain.model.SiteInput
import dev.pocketpaas.domain.repository.SiteRepository
import dev.pocketpaas.domain.system.RuntimeFileStore
import dev.pocketpaas.domain.system.RuntimeLayoutProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Site-scoped facade over the runtime filesystem. Callers never provide an absolute path. */
@Singleton
class RuntimeSiteFileManager @Inject constructor(
    private val sites: SiteRepository,
    private val layoutProvider: RuntimeLayoutProvider,
    private val files: RuntimeFileStore,
) : SiteFileManager {
    private val operations = Mutex()

    override suspend fun list(siteId: Long, relativeDirectory: String): List<SiteFileEntry> = operations.withLock {
        val directory = resolve(siteId, relativeDirectory, allowRoot = true)
        files.listEntries(directory).asSequence()
            .filter { visibleName(it.name) }
            .map { SiteFileEntry(it.name, it.isDirectory, it.sizeBytes) }
            .toList()
    }

    override suspend fun read(siteId: Long, relativePath: String): String = operations.withLock {
        val path = resolve(siteId, relativePath, allowRoot = false)
        files.readTextOrNull(path) ?: throw SiteFileNotFoundException(siteId, relativePath)
    }

    override suspend fun write(siteId: Long, relativePath: String, content: String) = operations.withLock {
        val path = resolve(siteId, relativePath, allowRoot = false)
        if (content.toByteArray(Charsets.UTF_8).size > MAX_EDITABLE_FILE_BYTES) throw SiteFileTooLargeException()
        files.writeTextAtomic(path, content)
    }

    override suspend fun createDirectory(siteId: Long, relativePath: String) = operations.withLock {
        files.ensureDirectory(resolve(siteId, relativePath, allowRoot = false))
    }

    override suspend fun delete(siteId: Long, relativePath: String) = operations.withLock {
        val path = resolve(siteId, relativePath, allowRoot = false)
        val name = relativePath.substringAfterLast('/')
        val parent = relativePath.substringBeforeLast('/', "")
        val parentPath = resolve(siteId, parent, allowRoot = true)
        val entry = files.listEntries(parentPath).firstOrNull { it.name == name }
            ?: throw SiteFileNotFoundException(siteId, relativePath)
        if (entry.isDirectory) throw SiteFileNotRegularException(relativePath)
        files.delete(path)
    }

    private suspend fun resolve(siteId: Long, relativePath: String, allowRoot: Boolean): String {
        require(siteId > 0) { "A registered site ID is required." }
        val site = sites.getSite(siteId) ?: throw dev.pocketpaas.domain.model.SiteNotFoundException(siteId)
        val domain = SiteInput.normalizeDomainName(site.domainName)
        require(domain == site.domainName) { "Site identity is not canonical." }
        val layout = layoutProvider.current()
        val runtimeRoot = normalizedAbsolute(layout.rootPath)
        val projectDirectory = normalizedAbsolute(layout.projectsPath)
        require(projectDirectory.startsWith("$runtimeRoot/")) { "Project storage is outside the runtime root." }
        val root = "$projectDirectory/$domain"
        val parts = validateRelativePath(relativePath, allowRoot)
        val resolved = if (parts.isEmpty()) root else "$root/${parts.joinToString("/")}"
        require(resolved.startsWith("$root/") || resolved == root) { "Site file path escaped its project directory." }
        require(resolved.startsWith("$runtimeRoot/")) { "Site file path escaped runtime storage." }
        return resolved
    }

    private fun normalizedAbsolute(path: String): String {
        require(path.length in 2..MAX_PATH_CHARACTERS && path.startsWith('/') && '\\' !in path) {
            "A normalized absolute runtime path is required."
        }
        val components = path.split('/').drop(1)
        require(components.none { it.isEmpty() || it == "." || it == ".." || it.any(Char::isISOControl) }) {
            "A normalized absolute runtime path is required."
        }
        return path
    }

    private fun validateRelativePath(path: String, allowRoot: Boolean): List<String> {
        if (allowRoot && path.isEmpty()) return emptyList()
        require(path.isNotEmpty() && path.length <= MAX_PATH_CHARACTERS && !path.startsWith('/') &&
            !path.endsWith('/') && '\\' !in path && path.none(Char::isISOControl)
        ) { "Invalid site-relative path." }
        val parts = path.split('/')
        require(parts.size <= MAX_PATH_SEGMENTS && parts.all(::visibleName)) { "Invalid site-relative path." }
        return parts
    }

    private fun visibleName(name: String): Boolean = name.isNotEmpty() && name.length <= MAX_NAME_CHARACTERS &&
        name != "." && name != ".." && !name.startsWith('.') && '/' !in name && '\\' !in name &&
        name.none(Char::isISOControl) && name.lowercase() !in HIDDEN_DIRECTORIES

    companion object {
        const val MAX_EDITABLE_FILE_BYTES = 32 * 1024
        private const val MAX_PATH_CHARACTERS = 4096
        private const val MAX_PATH_SEGMENTS = 64
        private const val MAX_NAME_CHARACTERS = 255
        private val HIDDEN_DIRECTORIES = setOf("node_modules", "venv", "__pycache__")
    }
}
