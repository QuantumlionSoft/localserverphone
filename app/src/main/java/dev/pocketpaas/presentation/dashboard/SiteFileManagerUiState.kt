package dev.pocketpaas.presentation.dashboard

import dev.pocketpaas.domain.files.SiteFileEntry

data class SiteFileManagerUiState(
    val siteId: Long? = null,
    val directory: String = "",
    val entries: List<SiteFileEntry> = emptyList(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    val editorPath: String? = null,
    val editorContent: String = "",
    val error: String? = null,
) {
    val isOpen: Boolean get() = siteId != null
}
