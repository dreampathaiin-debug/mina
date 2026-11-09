package com.wangzan101.mina.cap.phone.a11y;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/**
 *Normal page,Used to simulate another app coming to the foreground.
 *The test APK page runs in the test package's own process, without the Kotlin standard library.,So write it in Java.
 */
public class PlainTestActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        text.setText("plain page");
        text.setTextSize(32f);
        setContentView(text);
    }
}
