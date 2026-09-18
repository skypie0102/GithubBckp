package com.skypie0102.githubbckp.data.local

import com.skypie0102.githubbckp.github.GithubRepository

fun GithubRepository.toEntity(
    selectedForBackup: Boolean = true,
    isAvailable: Boolean = true,
): RepositoryEntity = RepositoryEntity(
    githubId = id,
    owner = owner,
    name = name,
    defaultBranch = defaultBranch,
    isPrivate = isPrivate,
    selectedForBackup = selectedForBackup,
    isAvailable = isAvailable,
)
