package com.meenai.mina.ui.accounts

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import com.meenai.mina.ui.components.GlassPillTabs

/** All / Connections / Logins / API Keys:黑色滑块的玻璃胶囊,弹簧滑动。 */
@Composable
fun AccountTabs(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassPillTabs(
        options = listOf(
            tr(R.string.accounts_tab_all),
            tr(R.string.accounts_tab_connections),
            tr(R.string.accounts_tab_logins),
            tr(R.string.accounts_tab_api_keys),
        ),
        selected = selected,
        onSelect = onSelect,
        modifier = modifier,
        tags = listOf("tab_all", "tab_connections", "tab_logins", "tab_api_keys"),
        height = 44.dp,
    )
}
