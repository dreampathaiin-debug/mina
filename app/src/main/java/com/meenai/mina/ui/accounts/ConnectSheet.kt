package com.meenai.mina.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import com.meenai.mina.ui.components.ButtonKind
import com.meenai.mina.ui.components.MinaButton
import com.meenai.mina.ui.components.pressScale
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType

/**
 * Connect 弹出的玻璃底表,三种形态:
 * Connection -> 一句权限说明 + Continue(TODO: 真 OAuth);
 * Login -> 邮箱 + 密码(可切换明文),存成「已保存未验证」(TODO: 加密存储);
 * API key -> 单个打码输入 + 粘贴,存完显示末 4 位(TODO: 加密存储)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectSheet(
    target: ConnectTarget?,
    loading: Boolean,
    onDismiss: () -> Unit,
    onConnect: () -> Unit,
    onSaveLogin: (String) -> Unit,
    onSaveKey: (String) -> Unit,
) {
    val item = target?.item ?: return
    val c = MinaTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AccountLogo(item, size = 48.dp)
                Text(
                    tr(R.string.accounts_sheet_connect_title, item.name),
                    style = MinaType.LargeTitle.copy(fontSize = androidx.compose.ui.unit.TextUnit(24f, androidx.compose.ui.unit.TextUnitType.Sp)),
                    color = c.ink,
                )
            }
            when (item.type) {
                AccountType.CONNECTION -> ConnectionBody(item, loading, onConnect)
                AccountType.LOGIN -> LoginBody(onSaveLogin)
                AccountType.API_KEY -> KeyBody(onSaveKey)
            }
        }
    }
}

@Composable
private fun ConnectionBody(item: AccountItem, loading: Boolean, onConnect: () -> Unit) {
    val c = MinaTheme.colors
    Text(tr(R.string.accounts_sheet_access, item.name), style = MinaType.Body, color = c.ink2)
    if (loading) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
            Text(tr(R.string.accounts_connecting), style = MinaType.Body, color = c.ink2)
        }
    } else {
        MinaButton(tr(R.string.accounts_continue), onConnect, Modifier.fillMaxWidth(), kind = ButtonKind.Primary)
    }
}

@Composable
private fun LoginBody(onSaveLogin: (String) -> Unit) {
    val c = MinaTheme.colors
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    GlassField(value = email, onValueChange = { email = it }, hint = tr(R.string.accounts_email_hint))
    GlassField(value = password, onValueChange = { password = it }, hint = tr(R.string.accounts_password_hint), password = true)
    Text(tr(R.string.accounts_login_note), style = MinaType.Hint, color = c.ink3)
    MinaButton(
        tr(R.string.accounts_save_login),
        { onSaveLogin(email) },
        Modifier.fillMaxWidth(),
        kind = ButtonKind.Primary,
        enabled = email.isNotBlank() && password.isNotBlank(),
    )
}

@Composable
private fun KeyBody(onSaveKey: (String) -> Unit) {
    val c = MinaTheme.colors
    var key by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    GlassField(
        value = key,
        onValueChange = { key = it },
        hint = tr(R.string.accounts_key_hint),
        password = true,
        trailing = {
            Box(
                Modifier
                    .pressScale(onClick = { clipboard.getText()?.text?.let { key = it } })
                    .padding(4.dp),
            ) {
                Text(tr(R.string.accounts_paste), style = MinaType.Label, color = c.accent)
            }
        },
    )
    Text(tr(R.string.accounts_key_note), style = MinaType.Hint, color = c.ink3)
    MinaButton(
        tr(R.string.accounts_save_key),
        { onSaveKey(key) },
        Modifier.fillMaxWidth(),
        kind = ButtonKind.Primary,
        enabled = key.isNotBlank(),
    )
}

/** 玻璃输入框:圆角 16,placeholder,BasicTextField。 */
@Composable
private fun GlassField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    trailing: @Composable () -> Unit = {},
) {
    val c = MinaTheme.colors
    var shown by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(c.glass.copy(alpha = if (c.isDark) 0.5f else 0.6f))
            .border(1.dp, c.cardEdge, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        textStyle = MinaType.Body.copy(color = c.ink),
        singleLine = true,
        visualTransformation = if (password && !shown) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Email),
        cursorBrush = SolidColor(c.accent),
        decorationBox = { inner ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(hint, style = MinaType.Body, color = c.ink3)
                    inner()
                }
                if (password) {
                    Spacer(Modifier.size(8.dp))
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .pressScale(onClick = { shown = !shown }, pressedScale = 0.94f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (shown) MinaIcons.EyeOff else MinaIcons.Eye,
                            tr(if (shown) R.string.accounts_hide else R.string.accounts_show),
                            Modifier.size(18.dp),
                            tint = c.ink2,
                        )
                    }
                }
                trailing()
            }
        },
    )
}
