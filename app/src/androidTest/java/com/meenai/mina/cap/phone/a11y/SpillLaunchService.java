package com.wangzan101.mina.cap.phone.a11y;

import android.app.ActivityOptions;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import android.view.Display;

/**
 *Open the app's startup page in a separate process,Open and stop.
 *The components of the test APK run in the test package's own process, without the Kotlin standard library,So write it in Java.
 */
public class SpillLaunchService extends Service {
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String auth = intent != null ? intent.getStringExtra(SpillTestActivity.EXTRA_AUTH) : null;
        String dialog = intent != null ? intent.getStringExtra(SpillTestActivity.EXTRA_DIALOG) : null;
        String component = auth != null ? auth : dialog;
        if (component != null) {
            //Dialog style or full screen login page:Landed on the user's home screen. The test package itself has a page on Mina's screen,
            //When you don’t specify a screen, the system will also put it there.;Background process when there is no page on the home screen,The system puts it on the home screen by default,Specify the home screen like that here.
            Intent launch = new Intent().setComponent(new ComponentName(getPackageName(), component))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch, ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle());
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        String target = intent != null ? intent.getStringExtra(SpillTestActivity.EXTRA_TARGET) : null;
        Intent launch = target != null ? getPackageManager().getLaunchIntentForPackage(target) : null;
        if (launch != null) startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        stopSelf(startId);
        return START_NOT_STICKY;
    }
}
