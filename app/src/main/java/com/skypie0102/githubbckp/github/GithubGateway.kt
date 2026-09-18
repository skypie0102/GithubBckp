package com.skypie0102.githubbckp.github

data class GithubRepository(
    val id: Long,
    val owner: String,
    val name: String,
    val defaultBranch: String,
    val isPrivate: Boolean,
) {
    val fullName: String = "$owner/$name"
    val remoteUrl: String = "https://github.com/$fullName.git"
}

interface GithubGateway {
    suspend fun listRepositories(): List<GithubRepository>
}
