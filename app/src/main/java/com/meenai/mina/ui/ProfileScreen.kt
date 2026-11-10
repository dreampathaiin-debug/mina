package com.meenai.mina.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meenai.mina.R
import com.meenai.mina.core.memory.ProfileStore
import com.meenai.mina.i18n.tr
import com.meenai.mina.ui.components.FooterNote
import com.meenai.mina.ui.components.GroupCard
import com.meenai.mina.ui.components.GroupLabel
import com.meenai.mina.ui.components.ListRow
import com.meenai.mina.ui.components.PageScaffold
import com.meenai.mina.ui.components.RowValue
import com.meenai.mina.ui.components.ValueTone
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Profile:bio data(Mina agent 回答用户、替用户办事时直接引用)。
 * 本地 SharedPreferences 保底,同时按「字段: 值」写进 agent 的 profile 记忆里。
 */
data class ProfileField(val key: String, val titleRes: Int, val agentLabel: String)

val PROFILE_FIELDS = listOf(
    ProfileField("name", R.string.profile_name, "Name"),
    ProfileField("nickname", R.string.profile_nickname, "Nickname"),
    ProfileField("birthday", R.string.profile_birthday, "Birthday"),
    ProfileField("email", R.string.profile_email, "Email"),
    ProfileField("phone", R.string.profile_phone, "Phone"),
    ProfileField("home", R.string.profile_home, "Home address"),
    ProfileField("job", R.string.profile_job, "Job title"),
    ProfileField("company", R.string.profile_company, "Company"),
    ProfileField("people", R.string.profile_people, "People"),
    ProfileField("interests", R.string.profile_interests, "Interests"),
    ProfileField("pets", R.string.profile_pets, "Pets"),
    ProfileField("notes", R.string.profile_notes, "Notes"),
)

object ProfileUiStore {
    private const val FILE = "mina_profile"

    fun read(context: Context): Map<String, String> {
        val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val out = mutableMapOf<String, String>()
        PROFILE_FIELDS.forEach { f -> sp.getString(f.key, null)?.let { if (it.isNotBlank()) out[f.key] = it } }
        return out
    }

    fun write(context: Context, key: String, value: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(key, value.trim()).apply()
    }

    /** 一处一处同步进 agent 的 profile 记忆;失败就放着,不挡界面。 */
    fun syncToAgent(context: Context, profile: ProfileStore?, key: String, value: String) {
        if (profile == null) return
        val field = PROFILE_FIELDS.firstOrNull { it.key == key } ?: return
        val label = field.agentLabel
        val entry = "$label: ${value.trim()}"
        runCatching {
            profile.snapshot().filter { it.text.contains("$label:") }.forEach { profile.delete("$label:") }
            if (value.trim().isNotBlank()) profile.add(entry)
        }
    }
}

@Composable
fun ProfileScreen(profile: ProfileStore?, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var values by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var editing by remember { mutableStateOf<ProfileField?>(null) }

    LaunchedEffect(Unit) { values = withContext(Dispatchers.IO) { ProfileUiStore.read(context) } }

    PageScaffold(stringResource(R.string.profile_title), onBack) {
        GroupCard(Modifier.padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(52.dp).clip(CircleShape).background(MinaTheme.colors.accentSoft), contentAlignment = Alignment.Center) {
                    Text((values["name"]?.trim()?.take(1) ?: "").uppercase(), style = MinaType.Title, color = MinaTheme.colors.ink)
                }
                Column(Modifier.weight(1f)) {
                    Text(values["name"].orEmpty().ifBlank { tr(R.string.profile_empty_name) }, style = MinaType.BodyStrong, color = MinaTheme.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val line = listOf(values["email"], values["phone"]).filter { !it.isNullOrBlank() }.joinToString(" · ")
                    if (line.isNotBlank()) Text(line, style = MinaType.Caption, color = MinaTheme.colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        GroupLabel(tr(R.string.profile_group_basic))
        GroupCard {
            row(values, PROFILE_FIELDS[0], first = true) { editing = it }
            row(values, PROFILE_FIELDS[1]) { editing = it }
            row(values, PROFILE_FIELDS[2]) { editing = it }
        }
        GroupLabel(tr(R.string.profile_group_contact))
        GroupCard {
            row(values, PROFILE_FIELDS[3], first = true) { editing = it }
            row(values, PROFILE_FIELDS[4]) { editing = it }
            row(values, PROFILE_FIELDS[5]) { editing = it }
        }
        GroupLabel(tr(R.string.profile_group_work))
        GroupCard {
            row(values, PROFILE_FIELDS[6], first = true) { editing = it }
            row(values, PROFILE_FIELDS[7]) { editing = it }
        }
        GroupLabel(tr(R.string.profile_group_more))
        GroupCard {
            row(values, PROFILE_FIELDS[8], first = true) { editing = it }
            row(values, PROFILE_FIELDS[9]) { editing = it }
            row(values, PROFILE_FIELDS[10]) { editing = it }
            row(values, PROFILE_FIELDS[11]) { editing = it }
        }
        FooterNote(tr(R.string.profile_help_note))
    }

    editing?.let { field ->
        val c = MinaTheme.colors
        var text by remember(field.key) { mutableStateOf(values[field.key].orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(tr(field.titleRes), style = MinaType.Title, color = c.ink) },
            text = {
                BasicTextField(
                    value = text, onValueChange = { text = it },
                    textStyle = TextStyle(color = c.ink),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.fill).padding(horizontal = 14.dp, vertical = 12.dp),
                    decorationBox = { inner -> Box { if (text.isEmpty()) Text(tr(R.string.profile_input_hint), style = MinaType.Body, color = c.ink3); inner() } },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = text.trim()
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            ProfileUiStore.write(context, field.key, v)
                            ProfileUiStore.syncToAgent(context, profile, field.key, v)
                        }
                        values = ProfileUiStore.read(context)
                        editing = null
                    }
                }) { Text(tr(R.string.profile_save)) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(tr(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ColumnScope.row(
    values: Map<String, String>, field: ProfileField, first: Boolean = false, onEdit: (ProfileField) -> Unit,
) {
    val value = values[field.key]
    ListRow(
        MinaIcons.Edit, tr(field.titleRes),
        subtitle = null, first = first,
        onClick = { onEdit(field) },
    ) {
        RowValue(value?.takeIf { it.isNotBlank() } ?: tr(R.string.profile_add_value), tone = if (value.isNullOrBlank()) ValueTone.Link else ValueTone.Plain)
    }
}

