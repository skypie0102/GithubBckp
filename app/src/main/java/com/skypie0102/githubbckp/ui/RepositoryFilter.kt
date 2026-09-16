package com.skypie0102.githubbckp.ui

import com.skypie0102.githubbckp.data.local.RepositoryEntity
import java.util.Locale

internal fun filterRepositories(
    repositories: List<RepositoryEntity>,
    query: String,
): List<RepositoryEntity> {
    val normalized = query.trim().lowercase(Locale.ROOT)
    if (normalized.isBlank()) return repositories

    return repositories.filter { repository ->
        val fullName = "${repository.owner}/${repository.name}".lowercase(Locale.ROOT)
        fullName.contains(normalized) || repository.defaultBranch.lowercase(Locale.ROOT).contains(normalized)
    }
}
