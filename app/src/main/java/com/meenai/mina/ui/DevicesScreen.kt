package com.meenai.mina.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.meenai.mina.R
import com.meenai.mina.ui.components.FooterNote
import com.meenai.mina.ui.components.GroupCard
import com.meenai.mina.ui.components.GroupLabel
import com.meenai.mina.ui.components.ListRow
import com.meenai.mina.ui.components.PageScaffold
import com.meenai.mina.ui.components.RowChevron
import com.meenai.mina.ui.components.RowValue
import com.meenai.mina.ui.components.ValueTone
import com.meenai.mina.ui.theme.MinaIcons

/** 这台手机上 Mina 的各能力当前有没有授权;都是读系统,不在应用里存。 */
private class DevicePerms(
    val calendar: Boolean, val callLog: Boolean, val contacts: Boolean, val sms: Boolean,
    val mic: Boolean, val calls: Boolean, val listener: Boolean, val background: Boolean,
)

private fun permissionStates(context: Context): DevicePerms {
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    return DevicePerms(
        calendar = granted(Manifest.permission.READ_CALENDAR),
        callLog = granted(Manifest.permission.READ_CALL_LOG),
        contacts = granted(Manifest.permission.READ_CONTACTS),
        sms = granted(Manifest.permission.READ_SMS),
        mic = granted(Manifest.permission.RECORD_AUDIO),
        calls = granted(Manifest.permission.CALL_PHONE),
        listener = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName),
        background = context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true,
    )
}

/** 各能力的系统设置入口:权限页打不开就退回应用信息页。 */
private fun tryStart(context: Context, intent: Intent): Boolean =
    runCatching { context.startActivity(intent); true }.getOrDefault(false)

private fun openAppPermissions(context: Context) {
    // 系统没有公开的「单个权限页」入口,打开本应用的应用信息页,权限组就在里面
    tryStart(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
}

private fun openNotificationAccess(context: Context) {
    tryStart(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
}

private fun openBattery(context: Context) {
    if (!tryStart(context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName)))) {
        tryStart(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

/**
 * 设置 › 设备:这台手机的能力清单,和 Muse 的设备页一样——每行写当前状态,点哪一行就打开
 * 对应的系统设置页,允许/撤销都在系统里做;从系统设置回来时重读一遍状态。
 */
@Composable
fun DevicesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.lifecycle.addObserver(obs)
        onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }
    val perms = remember(tick) { permissionStates(context) }
    // 点一下直接弹系统的允许对话框;系统不再弹(永久拒绝)时才退回应用信息页
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        tick++
        val denied = result.filterValues { !it }.keys
        val act = context as? Activity
        if (denied.isNotEmpty() && act != null && denied.all { !ActivityCompat.shouldShowRequestPermissionRationale(act, it) }) {
            openAppPermissions(context)
        }
    }
    fun requestPerm(vararg ps: String) {
        val need = ps.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) { tick++; return }
        permLauncher.launch(need.toTypedArray())
    }
    PageScaffold(stringResource(R.string.devices_title), onBack) {
        GroupLabel(stringResource(R.string.devices_this_device))
        GroupCard {
            ListRow(
                MinaIcons.Phone, "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                "Android ${Build.VERSION.RELEASE}", first = true,
            ) { }
        }
        GroupLabel(stringResource(R.string.devices_manage))
        GroupCard {
            PermRow(MinaIcons.Clock, R.string.perm_calendar, perms.calendar, first = true, onClick = { requestPerm(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR) })
            PermRow(MinaIcons.Phone, R.string.perm_call_history, perms.callLog, onClick = { requestPerm(Manifest.permission.READ_CALL_LOG) })
            PermRow(MinaIcons.Lines, R.string.perm_contacts, perms.contacts, onClick = { requestPerm(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS) })
            PermRow(MinaIcons.Message, R.string.perm_messages, perms.sms, onClick = { requestPerm(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS) })
            PermRow(MinaIcons.Mic, R.string.perm_microphone, perms.mic, onClick = { requestPerm(Manifest.permission.RECORD_AUDIO) })
            PermRow(MinaIcons.Phone, R.string.perm_make_calls, perms.calls, R.string.devices_calls_sub, onClick = { requestPerm(Manifest.permission.CALL_PHONE) })
            PermRow(MinaIcons.Bell, R.string.perm_phone_notifications, perms.listener, R.string.devices_notif_sub, onClick = { openNotificationAccess(context) })
            PermRow(MinaIcons.Refresh, R.string.perm_run_background, perms.background, R.string.devices_background_sub, onClick = { openBattery(context) })
        }
        FooterNote(stringResource(R.string.devices_perms_sub))
    }
}

/** 一行能力:标题 + 当前状态,点开系统设置去改。 */
@Composable
private fun PermRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: Int,
    on: Boolean,
    sub: Int? = null,
    first: Boolean = false,
    onClick: () -> Unit,
) {
    ListRow(icon, stringResource(title), sub?.let { stringResource(it) }, first = first, onClick = onClick) {
        RowValue(stringResource(if (on) R.string.state_on else R.string.state_off), if (on) ValueTone.Ok else ValueTone.Bad)
        RowChevron()
    }
}
