package com.skypie0102.githubbckp.backup

data class RepositoryRef(
    val id: Long,
    val owner: String,
    val name: String,
    val defaultBranch: String,
    val isPrivate: Boolean,
) {
    val fullName: String = "$owner/$name"
}
