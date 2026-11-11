package dev.pocketpaas.domain.system

import dev.pocketpaas.domain.model.RuntimeLayout

/** Resolves the layout inside the selected runtime, which may use a different Android UID. */
fun interface RuntimeLayoutProvider {
    suspend fun current(): RuntimeLayout
}
