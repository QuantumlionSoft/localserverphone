package dev.pocketpaas.domain.system

/** Files belong to the selected runtime UID, which can differ from the APK UID. */
interface RuntimeFileStore {
    suspend fun writeTextAtomic(path: String, text: String)
    suspend fun readTextOrNull(path: String): String?
    suspend fun delete(path: String)
    suspend fun ensureDirectory(path: String)
    suspend fun listFiles(directory: String): List<String>

    suspend fun listEntries(directory: String): List<RuntimeFileEntry> = listFiles(directory).map { path ->
        RuntimeFileEntry(path.substringAfterLast('/'), isDirectory = false, sizeBytes = null)
    }
}
