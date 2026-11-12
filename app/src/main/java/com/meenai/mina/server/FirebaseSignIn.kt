package com.meenai.mina.server

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.meenai.mina.BuildConfig
import kotlinx.coroutines.tasks.await

/** Supplies the Firebase ID token used on every server request. */
class FirebaseTokenProvider : MinaTokenProvider {
    override suspend fun accessToken(): String? = FirebaseAuthFlow.idToken(force = false)
    override suspend fun refresh(): String? = FirebaseAuthFlow.idToken(force = true)
}

/**
 * Google sign-in through Firebase Auth and the Credential Manager: the Google
 * account chooser opens as an in-app popup, no browser round-trip. The returned
 * Google ID token is exchanged for a Firebase session whose ID token is what
 * the backend verifies. Firebase initializes manually from BuildConfig values
 * (parsed from google-services.json at build time), so builds without cloud
 * config behave exactly as before.
 */
object FirebaseAuthFlow {

    /** Manual init when cloud config is baked in; without it Mina Cloud stays off. */
    fun ensureInit(context: Context): Boolean {
        if (BuildConfig.MINA_FIREBASE_PROJECT_ID.isEmpty()) return false
        if (FirebaseApp.getApps(context).isNotEmpty()) return true
        return runCatching {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId(BuildConfig.MINA_FIREBASE_APP_ID)
                    .setApiKey(BuildConfig.MINA_FIREBASE_API_KEY)
                    .setProjectId(BuildConfig.MINA_FIREBASE_PROJECT_ID)
                    .build(),
            )
        }.isSuccess
    }

    fun signedIn(): Boolean = runCatching { FirebaseAuth.getInstance().currentUser != null }.getOrDefault(false)

    fun email(): String? = runCatching { FirebaseAuth.getInstance().currentUser?.email }.getOrNull()

    /** The Firebase SDK keeps the ID token fresh (auto-refresh on and off the wire). */
    suspend fun idToken(force: Boolean): String? = runCatching {
        FirebaseAuth.getInstance().currentUser?.getIdToken(force)?.await()?.token
    }.getOrNull()

    suspend fun signOut(context: Context) {
        runCatching { FirebaseAuth.getInstance().signOut() }
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
    }

    /**
     * In-app Google account chooser popup (Credential Manager), then a Firebase
     * session from the returned Google ID token. Throws with a readable message
     * when the user cancels, no Google account exists, or the exchange fails.
     */
    suspend fun signIn(context: Context): FirebaseUser {
        if (!ensureInit(context)) error("Mina Cloud is not configured")
        val manager = CredentialManager.create(context)
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(
                GetGoogleIdOption.Builder()
                    .setServerClientId(BuildConfig.MINA_FIREBASE_WEB_CLIENT_ID)
                    .setFilterByAuthorizedAccounts(false)
                    .build(),
            )
            .build()
        val response = try {
            manager.getCredential(context, request)
        } catch (e: GetCredentialCancellationException) {
            error("sign-in cancelled")
        } catch (e: NoCredentialException) {
            error("no Google account is available on this device")
        } catch (e: GetCredentialException) {
            error(e.message ?: "Google sign-in failed")
        }
        val googleToken = (response.credential as? GoogleIdTokenCredential)?.idToken
            ?: error("unexpected credential type")
        val auth = FirebaseAuth.getInstance()
        return auth.signInWithCredential(GoogleAuthProvider.getCredential(googleToken, null)).await().user
            ?: error("Firebase sign-in returned no user")
    }
}
