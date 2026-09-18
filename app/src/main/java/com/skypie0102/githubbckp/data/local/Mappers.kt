package com.skypie0102.githubbckp.data.local

import com.skypie0102.githubbckp.backup.RepositoryRef

fun RepositoryRef.toEntity(selectedForBackup: Boolean): RepositoryEntity =
    RepositoryEntity(
        githubId = id,
        owner = owner,
        name = name,
        defaultBranch = defaultBranch,
        isPrivate = isPrivate,
        selectedForBackup = selectedForBackup,
        isAvailable = true,
    )

fun RepositoryEntity.toRepositoryRef(): RepositoryRef =
    RepositoryRef(
        id = githubId,
        owner = owner,
        name = name,
        defaultBranch = defaultBranch,
        isPrivate = isPrivate,
    )
