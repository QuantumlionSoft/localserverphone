package dev.pocketpaas.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.pocketpaas.domain.model.ProjectType
import dev.pocketpaas.domain.model.SiteStatus

@Entity(
    tableName = "sites",
    indices = [
        Index(value = ["domainName"], unique = true),
        Index(value = ["internalPort"], unique = true),
        Index(value = ["customHostname"], unique = true),
    ],
)
data class SiteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val domainName: String,
    val internalPort: Int,
    val projectType: ProjectType,
    val processId: Int? = null,
    val publicUrl: String? = null,
    val status: SiteStatus = SiteStatus.STOPPED,
    val logs: String = "",
    val createdAt: Long,
    val customHostname: String? = null,
)
