package dev.pocketpaas.domain.model

enum class ProjectType {
    STATIC,
    DJANGO,
    NODEJS,
    REACT,
}

enum class SiteStatus {
    RUNNING,
    STOPPED,
    FAILED,
    DEPLOYING,
}

data class Site(
    val id: Long,
    val domainName: String,
    val internalPort: Int,
    val projectType: ProjectType,
    val processId: Int?,
    val publicUrl: String?,
    val status: SiteStatus,
    val logs: String,
    val createdAt: Long,
    /** Optional public DNS hostname. domainName remains the independent /sites/<domainName>/ identity. */
    val customHostname: String? = null,
)

sealed interface SiteCollectionState {
    data object Loading : SiteCollectionState
    data class Ready(val sites: List<Site>) : SiteCollectionState
    data class Failed(val lastKnownSites: List<Site>) : SiteCollectionState
}

class SiteNotFoundException(val siteId: Long) : NoSuchElementException("Site $siteId does not exist")

class SiteStillActiveException(val siteId: Long) : IllegalStateException("Site $siteId has an active runtime")
