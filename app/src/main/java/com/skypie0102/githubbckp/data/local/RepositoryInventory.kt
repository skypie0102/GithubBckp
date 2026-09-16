package com.skypie0102.githubbckp.data.local

import com.skypie0102.githubbckp.backup.RepositoryRef

data class RepositoryInventoryPlan(
    val repositories: List<RepositoryEntity>,
    val newlyUnavailableCount: Int,
    val reactivatedCount: Int,
)

fun planRepositoryInventory(
    existing: List<RepositoryEntity>,
    remote: List<RepositoryRef>,
): RepositoryInventoryPlan {
    val existingById = existing.associateBy { it.githubId }
    val uniqueRemote = remote.distinctBy { it.id }
    val remoteIds = uniqueRemote.mapTo(mutableSetOf()) { it.id }

    val repositories = uniqueRemote.map { repository ->
        val previous = existingById[repository.id]
        RepositoryEntity(
            githubId = repository.id,
            owner = repository.owner,
            name = repository.name,
            defaultBranch = repository.defaultBranch,
            isPrivate = repository.isPrivate,
            selectedForBackup = previous?.selectedForBackup ?: true,
            lastKnownSha = previous?.lastKnownSha,
            isAvailable = true,
        )
    }

    return RepositoryInventoryPlan(
        repositories = repositories,
        newlyUnavailableCount = existing.count { it.isAvailable && it.githubId !in remoteIds },
        reactivatedCount = existing.count { !it.isAvailable && it.githubId in remoteIds },
    )
}
