package com.skypie0102.githubbckp.storage

/**
 * Legacy Room decoding only. New mirror work is always stored in the selected
 * local document tree and does not route through a storage provider.
 */
enum class StorageDestination {
    GOOGLE_DRIVE,
    DOCUMENT_TREE,
}
