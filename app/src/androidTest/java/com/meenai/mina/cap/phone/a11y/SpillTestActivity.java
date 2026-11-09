package com.wangzan101.mina.cap.phone.a11y;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;

/**
 *click button,Handed over to a service in another process({@link SpillLaunchService})bring NEW_TASKOpen {@link #EXTRA_TARGET}The app’s startup page:
 *and App through the background process(Service, SDK, jump)Same as opening another app,The launched process has no page on Mina's screen,The system puts it on the home screen by default.
 *Used to reproduce "open to the user's home screen".(When opening from the application context in the same process,Android displays the screen where the top page of this process is located.,No spillage.)
 *The test APK page runs in the test package's own process, without the Kotlin standard library.,So write it in Java.
 */
public class SpillTestActivity extends Activity {
    public static final String EXTRA_TARGET = "target";
    /**Add another button when given:This dialog-style page is also opened through the background service. */
    public static final String EXTRA_DIALOG = "dialog";
    /**Add another button when given:Open the full-screen account login page through the background service(The test package is an account authenticator). */
    public static final String EXTRA_AUTH = "auth";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String extra = getIntent().getStringExtra(EXTRA_TARGET);
        String target = extra != null ? extra : "com.android.settings";
        String dialog = getIntent().getStringExtra(EXTRA_DIALOG);
        String auth = getIntent().getStringExtra(EXTRA_AUTH);
        Button button = new Button(this);
        button.setText("open elsewhere");
        button.setTextSize(32f);
        button.setOnClickListener(v ->
            startService(new Intent(this, SpillLaunchService.class).putExtra(EXTRA_TARGET, target)));
        if (dialog == null && auth == null) {
            setContentView(button);
            return;
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(button);
        if (dialog != null) {
            Button dialogButton = new Button(this);
            dialogButton.setText("open dialog");
            dialogButton.setTextSize(32f);
            dialogButton.setOnClickListener(v ->
                startService(new Intent(this, SpillLaunchService.class).putExtra(EXTRA_DIALOG, dialog)));
            root.addView(dialogButton);
        }
        if (auth != null) {
            Button authButton = new Button(this);
            authButton.setText("open auth");
            authButton.setTextSize(32f);
            authButton.setOnClickListener(v ->
                startService(new Intent(this, SpillLaunchService.class).putExtra(EXTRA_AUTH, auth)));
            root.addView(authButton);
        }
        setContentView(root);
    }
}
