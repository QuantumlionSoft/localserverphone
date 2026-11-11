package dev.pocketpaas.domain.files

/** A directory entry exposed by the site-scoped file manager. */
data class SiteFileEntry(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long?,
)

/**
 * File operations are deliberately keyed by a registered site and a relative path.
 * Implementations must never accept an arbitrary Termux or runtime path from callers.
 */
interface SiteFileManager {
    suspend fun list(siteId: Long, relativeDirectory: String = ""): List<SiteFileEntry>
    suspend fun read(siteId: Long, relativePath: String): String
    suspend fun write(siteId: Long, relativePath: String, content: String)
    suspend fun createDirectory(siteId: Long, relativePath: String)
    suspend fun delete(siteId: Long, relativePath: String)
}

class SiteFileNotFoundException(siteId: Long, relativePath: String) :
    NoSuchElementException("File '$relativePath' does not exist in site $siteId")

class SiteFileNotRegularException(relativePath: String) :
    IllegalArgumentException("'$relativePath' is not a regular file")

class SiteFileTooLargeException :
    IllegalArgumentException("Text files managed here are limited to 32 KiB")
