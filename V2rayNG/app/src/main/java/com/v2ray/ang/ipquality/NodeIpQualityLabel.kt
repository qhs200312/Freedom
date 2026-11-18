package com.v2ray.ang.ipquality

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R

@Composable
internal fun NodeIpQualityLabel(result: NodeIpQualityResult, modifier: Modifier = Modifier) {
    val quality = result.quality
    val text = if (quality == null) stringResource(R.string.node_ip_quality_unknown) else {
        val level = stringResource(when (quality.level) {
            IpQualityLevel.good -> R.string.ip_quality_good
            IpQualityLevel.normal -> R.string.ip_quality_normal
            IpQualityLevel.risky -> R.string.ip_quality_risky
        })
        val type = stringResource(when (quality.type) {
            IpType.residential -> R.string.node_ip_quality_residential
            IpType.hosting -> R.string.node_ip_quality_hosting
            IpType.mobile -> R.string.node_ip_quality_mobile
            IpType.business -> R.string.node_ip_quality_business
        })
        val classification = stringResource(R.string.node_ip_quality_result, level, type)
        when {
            quality.typeNeedsConfirmation -> stringResource(R.string.ip_quality_unconfirmed_value, classification)
            quality.inferred -> stringResource(R.string.ip_quality_inferred_value, classification)
            else -> classification
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = quality?.let { gradeColor(it.level) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
