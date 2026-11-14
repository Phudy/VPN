package com.phudy.redline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phudy.redline.ui.theme.R

@Composable
fun Card(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(R.radius)
    Column(
        modifier
            .clip(shape)
            .background(R.cardGradient)
            .border(1.dp, R.Stroke, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(R.gap),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = R.Muted, modifier = modifier.padding(bottom = R.gapS))
}

@Composable
fun ScreenHeader(title: String, subtitle: String? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = R.gap, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineLarge, color = R.Text)
            if (subtitle != null) Text(subtitle, color = R.Muted, fontSize = 13.sp)
        }
        trailing?.invoke()
    }
}

@Composable
fun ProBadge() {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(R.redGradient).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("ULTIMATE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
    }
}

@Composable
fun StatTile(label: String, value: String, icon: ImageVector, modifier: Modifier = Modifier, accent: Color = R.Red) {
    Card(modifier) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(10.dp))
        Text(value, color = R.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = R.Muted, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
fun SettingRow(
    icon: ImageVector, title: String, subtitle: String? = null,
    checked: Boolean? = null, onCheck: ((Boolean) -> Unit)? = null, onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(R.radiusS))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(R.radiusS)).background(R.Surface2), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = R.Red, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = R.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            if (subtitle != null) Text(subtitle, color = R.Muted, fontSize = 12.sp)
        }
        if (checked != null) {
            Switch(
                checked = checked, onCheckedChange = onCheck,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = R.Red, checkedThumbColor = Color.White,
                    uncheckedTrackColor = R.Surface2, uncheckedBorderColor = R.Stroke, uncheckedThumbColor = R.Muted,
                ),
            )
        }
        trailing?.invoke()
    }
}

@Composable
fun RedButton(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(R.radiusS))
            .background(if (enabled) R.redGradient else androidx.compose.ui.graphics.SolidColor(R.Surface2))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, color = if (enabled) Color.White else R.Muted, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun GhostButton(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(R.radiusS)
    Row(
        modifier.clip(shape).border(1.dp, R.Stroke, shape).background(R.Surface2).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { Icon(icon, null, tint = R.Red, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, color = R.Text, fontWeight = FontWeight.SemiBold)
    }
}
