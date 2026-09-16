package com.skypie0102.githubbckp.data.local

internal fun normalizeRepositoryInventory(
    repositories: List<RepositoryEntity>,
): List<RepositoryEntity> = repositories
    .distinctBy { it.githubId }
    .map { it.copy(isAvailable = true) }
