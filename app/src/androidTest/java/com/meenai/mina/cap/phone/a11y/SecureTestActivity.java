package com.wangzan101.mina.cap.phone.a11y;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.TextView;

/**
 *Pages with anti-screenshot flag:Draw black on virtual screen,But the accessibility tree is still readable.
 *The test APK page runs in the test package's own process, without the Kotlin standard library.,So write it in Java.
 */
public class SecureTestActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        TextView text = new TextView(this);
        text.setText("secure page");
        text.setTextSize(32f);
        setContentView(text);
    }
}
