package com.v2ray.ang.ui.checkupdate

import java.net.URI

internal data class ReleaseNotePart(val text: String, val url: String? = null)

/** A small link parser, not a full Markdown renderer. Only web links are actionable. */
internal object ReleaseNoteLinks {
    const val MANUAL_DOWNLOAD_PAGE = "https://github.com/qhs200312/Freedom/releases/latest"
    private val pattern = Regex("""\[([^\]\r\n]+)]\((https?://[^\s)]+)\)|(https?://[^\s<>，。；：！？、）】]+)""",
        RegexOption.IGNORE_CASE)
    private val trailingPunctuation = ".,;:!?，。；：！？、）】"

    fun parse(text: String): List<ReleaseNotePart> {
        val parts = mutableListOf<ReleaseNotePart>()
        var offset = 0
        for (match in pattern.findAll(text)) {
            if (match.range.first > offset) parts += ReleaseNotePart(text.substring(offset, match.range.first))
            val markdownUrl = match.groups[2]?.value
            val raw = markdownUrl ?: match.groups[3]!!.value
            val url = if (markdownUrl != null) raw else trimUrl(raw)
            if (isWebUrl(url)) {
                parts += ReleaseNotePart(if (markdownUrl != null) match.groupValues[1] else url, url)
                if (markdownUrl == null && raw.length > url.length) {
                    parts += ReleaseNotePart(raw.substring(url.length))
                }
            } else parts += ReleaseNotePart(match.value)
            offset = match.range.last + 1
        }
        if (offset < text.length) parts += ReleaseNotePart(text.substring(offset))
        return parts
    }

    fun paragraphs(notes: String): List<String> =
        notes.trim().split(Regex("""\r?\n\s*\r?\n""")).filter(String::isNotBlank)

    fun manualDownloadIndex(paragraphs: List<String>): Int =
        paragraphs.indexOfFirst { it.contains(Regex("""\bxray(?:-core)?\b""", RegexOption.IGNORE_CASE)) }
            .let { if (it >= 0) it + 1 else 0 }

    fun copyMessage(title: String, notes: String, downloadLabel: String): String = buildString {
        append(title)
        if (notes.isNotBlank()) append("\n\n").append(notes.trim())
        append("\n\n").append(downloadLabel).append(": ").append(MANUAL_DOWNLOAD_PAGE)
    }

    private fun trimUrl(raw: String): String {
        var url = raw.trimEnd { it in trailingPunctuation }
        // Keep balanced URL parentheses, but exclude surrounding prose punctuation.
        while (url.endsWith(')') && url.count { it == ')' } > url.count { it == '(' }) url = url.dropLast(1)
        return url
    }

    private fun isWebUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)
}
