package com.skypie0102.githubbckp.github

import com.skypie0102.githubbckp.backup.RepositoryRef
import java.io.File

interface GithubGateway {
    suspend fun listRepositories(): List<RepositoryRef>

    suspend fun downloadSourceArchive(
        repository: RepositoryRef,
        ref: String,
        destination: File,
    ): Long
}
