package com.wangzan101.mina.cap.phone.a11y;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 *Full screen login/Verification page:There is no dialog box theme, no password box, and no excludeFromRecents,
 *The shape is the same as a normal App page. Only the item "The package it is in is an account authenticator without a desktop icon" can be recognized and requires the user to handle it manually.
 */
public class FullScreenAuthTestActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Sign in to continue");
        title.setTextSize(22f);
        root.addView(title);

        TextView body = new TextView(this);
        body.setText("This account page needs you to confirm on this phone.");
        body.setTextSize(16f);
        body.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(body);

        Button confirm = new Button(this);
        confirm.setText("Confirm");
        confirm.setOnClickListener(v -> finish());
        root.addView(confirm);

        setContentView(root);
    }
}
