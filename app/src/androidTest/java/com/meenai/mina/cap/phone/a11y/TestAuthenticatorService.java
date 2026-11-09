package com.wangzan101.mina.cap.phone.a11y;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**Give the test account authenticator to the system(See {@link TestAuthenticator}). */
public class TestAuthenticatorService extends Service {
    private TestAuthenticator authenticator;

    @Override
    public void onCreate() {
        super.onCreate();
        authenticator = new TestAuthenticator(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return authenticator.getIBinder();
    }
}
