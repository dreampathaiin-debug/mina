package com.meenai.mina.ui.accounts

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import coil.compose.AsyncImage
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import com.meenai.mina.ui.components.PillTone
import com.meenai.mina.ui.components.RowChevron
import com.meenai.mina.ui.components.StatusPill
import com.meenai.mina.ui.components.pressScale
import com.meenai.mina.ui.theme.InterTight
import com.meenai.mina.ui.theme.MinaShapes
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType
import com.meenai.mina.ui.theme.MinaIcons

/** 名字 17sp Medium;副标题 14sp。 */
val AccountNameStyle = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, fontFamily = InterTight)
val AccountSubStyle = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Normal, fontFamily = InterTight)

/** 缺矢量 logo 的品牌用字母头像,配品牌色。 */
private val LetterColors: Map<String, Long> = mapOf(
    "amazon" to 0xFFFF9900,
    "flipkart" to 0xFF2874F0,
    "swiggy" to 0xFFFC8019,
    "zomato" to 0xFFE23744,
    "groww" to 0xFF00B386,
    "zerodha" to 0xFF387ED1,
    "uber" to 0xFF141414,
)

/** 36dp 品牌标志;没有 logoRes 就落到字母头像。API key 的单色 logo 用 ink 着色(深色主题可见)。 */
@Composable
fun AccountLogo(item: AccountItem, size: Dp = 36.dp) {
    val c = MinaTheme.colors
    when {
        !item.logoUrl.isNullOrBlank() -> AsyncImage(
            model = item.logoUrl,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(size),
        )
        item.logoRes != null -> Image(
            painterResource(item.logoRes),
            contentDescription = null,
            Modifier.size(size),
            colorFilter = if (item.type == AccountType.API_KEY && item.id != "gemini") ColorFilter.tint(c.ink) else null,
        )
        else -> {
        Box(
            Modifier.size(size)
                .clip(RoundedCornerShape(size / 4))
                .background(Color((LetterColors[item.id] ?: 0xFF6E7380))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                item.name.take(1),
                style = TextStyle(fontSize = (size.value * 0.44f).sp, fontWeight = FontWeight.SemiBold, fontFamily = InterTight),
                color = Color.White,
            )
        }
        }
    }
}

/** 列表里的玻璃卡:半透明 + 1dp 卡边描边。不做真模糊:列表本身是 haze 源,卡片再模糊会看见自己的重影。 */
@Composable
fun GlassCardLite(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = MinaTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(MinaShapes.Card)
            .background(c.surface.copy(alpha = if (c.isDark) 0.55f else 0.78f))
            .border(1.dp, c.cardEdge, MinaShapes.Card),
        content = content,
    )
}

/** 分组卡行之间的 1dp 分隔线(白 40%,即 Mina 的 cardEdge;卡片首尾没有)。 */
@Composable
fun RowDivider() {
    Box(
        Modifier.fillMaxWidth()
            .padding(start = 20.dp)
            .height(1.dp)
            .background(MinaTheme.colors.cardEdge),
    )
}

/** 分组卡里的一行:36dp logo | 名字 + 副标题 | Connect 胶囊 / Connected。 */
@Composable
fun GroupedAccountRow(
    item: AccountItem,
    modifier: Modifier = Modifier,
    onOpen: (AccountItem) -> Unit,
) {
    val c = MinaTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .pressScale(onClick = { onOpen(item) }, pressedScale = 0.98f)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AccountLogo(item)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.name, style = AccountNameStyle, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            item.subtitle?.let {
                Text(it, style = AccountSubStyle, color = c.ink2.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        AccountAction(item, onOpen)
    }
}

/** 行尾:Connect 玻璃胶囊(44dp),已连的显示对勾 + Connected,已存登录显示 Saved。 */
@Composable
private fun AccountAction(item: AccountItem, onOpen: (AccountItem) -> Unit) {
    val c = MinaTheme.colors
    when (item.status) {
        AccountStatus.CONNECTED -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(MinaIcons.Check, null, Modifier.size(15.dp), tint = c.ink2)
                Text(tr(R.string.accounts_connected), style = AccountSubStyle.copy(fontWeight = FontWeight.Medium), color = c.ink2)
            }
        }
        AccountStatus.SAVED_NOT_VERIFIED -> {
            Text(tr(R.string.accounts_saved), style = AccountSubStyle.copy(fontWeight = FontWeight.Medium), color = c.ink2)
        }
        AccountStatus.NOT_CONNECTED -> {
            Box(
                Modifier
                    .height(44.dp)
                    .pressScale(onClick = { onOpen(item) }, pressedScale = 0.96f)
                    .background(c.glass.copy(alpha = if (c.isDark) 0.5f else 0.75f), MinaShapes.Pill)
                    .border(1.dp, c.cardEdge, MinaShapes.Pill)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(tr(R.string.accounts_connect), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, fontFamily = InterTight), color = c.ink)
            }
        }
    }
}

/** 已连区块的一张卡:logo | 名字(+ Managed login 徽章)与身份 | 垃圾桶或箭头。 */
@Composable
fun ConnectedAccountCard(
    item: AccountItem,
    modifier: Modifier = Modifier,
    onOpen: (AccountItem) -> Unit,
    onRemove: (AccountItem) -> Unit,
) {
    val c = MinaTheme.colors
    GlassCardLite(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .pressScale(onClick = { onOpen(item) }, pressedScale = 0.98f)
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AccountLogo(item)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.name, style = AccountNameStyle, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (item.status == AccountStatus.SAVED_NOT_VERIFIED) {
                        StatusPill(tr(R.string.accounts_managed_login), PillTone.Neutral)
                    }
                }
                item.identity?.let {
                    Text(it, style = AccountSubStyle, color = c.ink2.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (item.status == AccountStatus.SAVED_NOT_VERIFIED) {
                    Text(tr(R.string.accounts_not_signed_in), style = MinaType.Hint, color = c.ink3)
                }
            }
            if (item.status == AccountStatus.CONNECTED) {
                Box(
                    Modifier
                        .size(36.dp)
                        .background(c.glass.copy(alpha = if (c.isDark) 0.5f else 0.6f), CircleShape)
                        .border(1.dp, c.cardEdge, CircleShape)
                        .clip(CircleShape)
                        .pressScale(onClick = { onRemove(item) }, pressedScale = 0.94f),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(MinaIcons.Trash, tr(R.string.accounts_remove), Modifier.size(16.dp), tint = c.ink2)
                }
            } else {
                Spacer(Modifier.size(4.dp))
                RowChevron()
            }
        }
    }
}
