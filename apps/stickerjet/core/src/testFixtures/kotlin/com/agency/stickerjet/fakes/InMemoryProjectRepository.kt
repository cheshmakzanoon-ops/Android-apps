package com.agency.stickerjet.fakes

import com.agency.stickerjet.domain.ProjectRepository
import com.agency.stickerjet.domain.StickerProject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [ProjectRepository] that keeps projects in memory. Every operation runs under one [Mutex], so
 * concurrent callers see the same atomic writes that the real repository promises.
 *
 * [list] returns projects in the order they were first saved. It does not sort by update time.
 */
class InMemoryProjectRepository(initial: Collection<StickerProject> = emptyList()) : ProjectRepository {

    private val mutex = Mutex()
    private val projects = LinkedHashMap<String, StickerProject>().apply {
        initial.forEach { put(it.id, it) }
    }

    override suspend fun list(): List<StickerProject> = mutex.withLock { projects.values.toList() }

    override suspend fun load(id: String): StickerProject? = mutex.withLock { projects[id] }

    /** Replaces any project with the same id. */
    override suspend fun save(project: StickerProject) {
        mutex.withLock { projects[project.id] = project }
    }

    override suspend fun delete(id: String) {
        mutex.withLock { projects.remove(id) }
    }
}
