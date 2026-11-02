package com.v2ray.ang.ui.checkupdate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.util.Utils

@Composable
internal fun UpdateReleaseNotes(notes: String) {
    val paragraphs = remember(notes) { ReleaseNoteLinks.paragraphs(notes) }
    val insertion = remember(paragraphs) { ReleaseNoteLinks.manualDownloadIndex(paragraphs) }
    for (index in 0..paragraphs.size) {
        if (index == insertion) ManualDownloadNotice()
        paragraphs.getOrNull(index)?.let { ClickableReleaseNote(it) }
    }
}

@Composable
private fun ClickableReleaseNote(text: String) {
    val context = LocalContext.current
    val parts = remember(text) { ReleaseNoteLinks.parse(text) }
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = buildAnnotatedString {
        parts.forEach { part ->
            if (part.url == null) append(part.text) else {
                withLink(LinkAnnotation.Url(
                    part.url,
                    styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                    linkInteractionListener = { Utils.openUri(context, part.url) },
                )) { append(part.text) }
            }
        }
    }
    SelectionContainer {
        Text(annotated, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ManualDownloadNotice() {
    val context = LocalContext.current
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.update_manual_download_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
            TextButton(
                onClick = { Utils.openUri(context, ReleaseNoteLinks.MANUAL_DOWNLOAD_PAGE) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.update_manual_download_browser),
                    textDecoration = TextDecoration.Underline)
            }
        }
    }
}
