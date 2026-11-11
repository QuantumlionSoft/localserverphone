package dev.pocketpaas.domain.system

/** A regular file or directory discovered below a runtime-owned directory. */
data class RuntimeFileEntry(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long?,
)
