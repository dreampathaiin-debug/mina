package com.meenai.mina.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 上次崩溃的现场:把 crash-latest.txt 原样亮出来,用户能截图或分享给开发;清掉后才进正常界面。 */
@Composable
fun CrashReportScreen(text: String, onClear: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier.fillMaxSize().background(Color(0xFF0E1117)).statusBarsPadding().padding(20.dp),
    ) {
        Text("Mina crashed last time", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "上次启动时崩溃了。把下面的错误日志发给 Fo(截图或点分享),然后点清除继续用。",
            color = Color(0xFFA8B0BC), fontSize = 13.sp,
        )
        Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
            Text(
                text,
                Modifier.verticalScroll(rememberScrollState()),
                color = Color(0xFF9EE493), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                            "Share crash log",
                        ),
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1C1C1E)),
                shape = RoundedCornerShape(50),
                modifier = Modifier.weight(1f),
            ) { Text("分享日志", color = Color.White) }
            Button(
                onClick = { clipboard.setText(AnnotatedString(text)) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1C1C1E)),
                shape = RoundedCornerShape(50),
                modifier = Modifier.weight(1f),
            ) { Text("复制", color = Color.White) }
            Button(
                onClick = onClear,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A3242)),
                shape = RoundedCornerShape(50),
                modifier = Modifier.weight(1f),
            ) { Text("清除继续", color = Color.White) }
        }
    }
}
