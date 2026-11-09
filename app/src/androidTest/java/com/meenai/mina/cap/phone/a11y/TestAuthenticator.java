package com.wangzan101.mina.cap.phone.a11y;

import android.accounts.AbstractAccountAuthenticator;
import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManager;
import android.content.Context;
import android.os.Bundle;

/**
 *Account authenticator for testing:Have the test package appear in the system's account authenticator table,Used to verify the criterion "the login page of the account authenticator remains on the home screen".
 *Does not support any operations itself,Only register types.
 */
public class TestAuthenticator extends AbstractAccountAuthenticator {
    TestAuthenticator(Context context) {
        super(context);
    }

    @Override
    public Bundle editProperties(AccountAuthenticatorResponse response, String accountType) {
        return unsupported();
    }

    @Override
    public Bundle addAccount(AccountAuthenticatorResponse response, String accountType, String authTokenType,
                             String[] requiredFeatures, Bundle options) {
        return unsupported();
    }

    @Override
    public Bundle confirmCredentials(AccountAuthenticatorResponse response, Account account, Bundle options) {
        return unsupported();
    }

    @Override
    public Bundle getAuthToken(AccountAuthenticatorResponse response, Account account, String authTokenType,
                               Bundle options) {
        return unsupported();
    }

    @Override
    public String getAuthTokenLabel(String authTokenType) {
        return null;
    }

    @Override
    public Bundle updateCredentials(AccountAuthenticatorResponse response, Account account, String authTokenType,
                                    Bundle options) {
        return unsupported();
    }

    @Override
    public Bundle hasFeatures(AccountAuthenticatorResponse response, Account account, String[] features) {
        return unsupported();
    }

    private Bundle unsupported() {
        Bundle b = new Bundle();
        b.putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION);
        b.putString(AccountManager.KEY_ERROR_MESSAGE, "unsupported");
        return b;
    }
}
