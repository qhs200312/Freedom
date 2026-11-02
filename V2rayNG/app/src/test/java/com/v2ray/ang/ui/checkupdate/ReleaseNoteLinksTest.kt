package com.v2ray.ang.ui.checkupdate

import org.junit.Assert.*
import org.junit.Test

class ReleaseNoteLinksTest {
    @Test
    fun markdownAndBareDownloadLinksAreActionableWithoutMarkdownPunctuation() {
        val parts = ReleaseNoteLinks.parse(
            "下载：[Freedom 最新版](${ReleaseNoteLinks.MANUAL_DOWNLOAD_PAGE})，或 https://example.org/app.apk。",
        )
        assertEquals(listOf(ReleaseNoteLinks.MANUAL_DOWNLOAD_PAGE, "https://example.org/app.apk"),
            parts.mapNotNull { it.url })
        assertEquals("下载：Freedom 最新版，或 https://example.org/app.apk。", parts.joinToString("") { it.text })
    }

    @Test
    fun onlyWebLinksCanBeOpenedAndMalformedLinksRemainText() {
        val cases = listOf(
            "[install](intent://download)", "[script](javascript:alert(1))",
            "https://", "https://user:password@example.org/app.apk", "https://bad_host<>",
        )
        cases.forEach { input ->
            val parts = ReleaseNoteLinks.parse(input)
            assertTrue(input, parts.none { it.url != null })
            assertEquals(input, parts.joinToString("") { it.text })
        }
    }

    @Test
    fun surroundingPunctuationIsPreservedAndBalancedUrlParenthesesAreKept() {
        val parts = ReleaseNoteLinks.parse("见 (https://example.org/file.apk)，以及 https://example.org/path(foo)。")
        assertEquals(listOf("https://example.org/file.apk", "https://example.org/path(foo)"), parts.mapNotNull { it.url })
        assertEquals("见 (https://example.org/file.apk)，以及 https://example.org/path(foo)。",
            parts.joinToString("") { it.text })
    }

    @Test
    fun manualDownloadAppearsImmediatelyBelowXrayUpdateBeforeOtherFeatures() {
        val paragraphs = ReleaseNoteLinks.paragraphs(
            "# Freedom 2.9.0\r\n\r\n1. Xray-core 更新及安全说明。\r\n\r\n2. YAML 分流导入。\r\n\r\n3. IP 质量。",
        )
        val index = ReleaseNoteLinks.manualDownloadIndex(paragraphs)
        assertEquals(2, index)
        assertTrue(paragraphs[index - 1].contains("Xray-core"))
        assertTrue(paragraphs[index].contains("YAML"))
    }

    @Test
    fun missingOrEmptyNotesStillExposeManualDownloadAtTheTop() {
        for (notes in listOf("", "普通更新说明", "### Features\n\nFixes.")) {
            assertEquals(0, ReleaseNoteLinks.manualDownloadIndex(ReleaseNoteLinks.paragraphs(notes)))
        }
    }

    @Test
    fun copyingIncludesTheWholeMessageAndFallbackLinkEvenWithNoNotes() {
        val notes = "1. Xray 安全更新。\n\n2. YAML 导入。"
        val message = ReleaseNoteLinks.copyMessage("发现新版本 2.9.0", notes, "手动下载")
        assertEquals("发现新版本 2.9.0\n\n$notes\n\n手动下载: ${ReleaseNoteLinks.MANUAL_DOWNLOAD_PAGE}", message)
        assertTrue(ReleaseNoteLinks.copyMessage("2.9.0", "", "下载")
            .endsWith(ReleaseNoteLinks.MANUAL_DOWNLOAD_PAGE))
    }
}
