package com.skypie0102.githubbckp.github

import java.io.IOException
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class GithubLatestReleaseServiceTest {
    @Test
    fun parsesLatestReleaseMetadataAndAssets() {
        val release = parseLatestReleaseJson(
            """
            {
              "id": 10,
              "tag_name": "v1.2.3",
              "name": "Release 1.2.3",
              "body": "Notes",
              "html_url": "https://github.com/owner/repo/releases/tag/v1.2.3",
              "published_at": "2026-09-18T10:00:00Z",
              "updated_at": "2026-09-18T11:00:00Z",
              "tarball_url": "https://api.github.com/repos/owner/repo/tarball/v1.2.3",
              "assets": [
                {
                  "id": 21,
                  "name": "app.apk",
                  "size": 1234,
                  "url": "https://api.github.com/repos/owner/repo/releases/assets/21"
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(10L, release.id)
        assertEquals("v1.2.3", release.tagName)
        assertEquals("Release 1.2.3", release.name)
        assertEquals("Notes", release.body)
        assertEquals(1, release.assets.size)
        assertEquals("app.apk", release.assets.single().name)
        assertEquals(1234L, release.assets.single().sizeBytes)
    }

    @Test
    fun blankOptionalReleaseFieldsBecomeNull() {
        val release = parseLatestReleaseJson(
            """
            {
              "id": 10,
              "tag_name": "v1",
              "name": "",
              "body": "",
              "html_url": "https://github.com/owner/repo/releases/tag/v1",
              "published_at": "",
              "updated_at": "2026-09-18T11:00:00Z",
              "tarball_url": "https://api.github.com/repos/owner/repo/tarball/v1",
              "assets": []
            }
            """.trimIndent(),
        )

        assertNull(release.name)
        assertNull(release.body)
        assertNull(release.publishedAt)
    }

    @Test
    fun downloadRedirectValidationRejectsNonGithubHostsAndHttp() {
        assertThrows(IOException::class.java) {
            requireTrustedGithubDownloadUrl(URL("https://example.com/file"))
        }
        assertThrows(IOException::class.java) {
            requireTrustedGithubDownloadUrl(URL("http://api.github.com/file"))
        }
    }

    @Test
    fun downloadRedirectValidationAcceptsGithubDownloadHosts() {
        requireTrustedGithubDownloadUrl(URL("https://api.github.com/repos/a/b/tarball/v1"))
        requireTrustedGithubDownloadUrl(URL("https://codeload.github.com/a/b/legacy.tar.gz/v1"))
        requireTrustedGithubDownloadUrl(URL("https://release-assets.githubusercontent.com/file"))
    }
}
