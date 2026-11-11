package dev.pocketpaas.data.local

import dev.pocketpaas.domain.model.Site

fun SiteEntity.toDomain(): Site = Site(
    id = id,
    domainName = domainName,
    internalPort = internalPort,
    projectType = projectType,
    processId = processId,
    publicUrl = publicUrl,
    status = status,
    logs = logs,
    createdAt = createdAt,
    customHostname = customHostname,
)
