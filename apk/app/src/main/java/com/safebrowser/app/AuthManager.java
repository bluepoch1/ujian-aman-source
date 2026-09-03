package com.safebrowser.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import org.json.JSONObject;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class AuthManager {

    private AuthManager() { }

    private static final String TAG = "SafeBrowser.Auth";
    private static final String PREFS = "sb_auth";
    private static final String KEY_ACCESS = "access_token";
    private static final String KEY_REFRESH = "refresh_token";
    private static final String KEY_EXPIRES_AT = "expires_at";
    private static final String KEY_USER_ID = "user_id";

    private static final long REFRESH_MARGIN_MS = 60_000L;

    static final MediaType JSON = MediaType.get("application/json; charset=utf-8");


    static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
    public interface TokenCallback {
        void onToken(String accessToken);
        void onError(String message);
    }

    @Nullable
    public static String userId(Context c) {
        return prefs(c).getString(KEY_USER_ID, null);
    }

    public static void clear(Context c) {
        prefs(c).edit().clear().apply();
    }

    public static void withToken(Context context, TokenCallback callback) {
        SharedPreferences p = prefs(context);
        String access = p.getString(KEY_ACCESS, null);
        long expiresAt = p.getLong(KEY_EXPIRES_AT, 0);

        if (access != null && System.currentTimeMillis() < expiresAt - REFRESH_MARGIN_MS) {
            callback.onToken(access);
            return;
        }

        String refresh = p.getString(KEY_REFRESH, null);
        if (refresh != null) {
            refresh(context, refresh, new TokenCallback() {
                @Override
                public void onToken(String token) {
                    callback.onToken(token);
                }

                @Override
                public void onError(String message) {
                    signInAnonymously(context, callback);
                }
            });
            return;
        }

        signInAnonymously(context, callback);
    }

    private static void signInAnonymously(Context context, TokenCallback callback) {
        Request request = new Request.Builder()
                .url(BuildConfig.SUPABASE_URL + "/auth/v1/signup")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create("{}", JSON))
                .build();

        HTTP.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError("network");
            }

            @Override
            public void onResponse(Call call, Response response) {
                handleAuthResponse(context, response, callback);
            }
        });
    }

    private static void refresh(Context context, String refreshToken,
                                TokenCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("refresh_token", refreshToken);
        } catch (Exception ignored) {
        }

        Request request = new Request.Builder()
                .url(BuildConfig.SUPABASE_URL
                        + "/auth/v1/token?grant_type=refresh_token")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        HTTP.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError("network");
            }

            @Override
            public void onResponse(Call call, Response response) {
                handleAuthResponse(context, response, callback);
            }
        });
    }

    private static void handleAuthResponse(Context context, Response response,
                                           TokenCallback callback) {
        try (ResponseBody body = response.body()) {
            if (body == null) {
                Log.w(TAG, "Auth response body is null");
                callback.onError("network");
                return;
            }
            String raw = body.string();

            if (!response.isSuccessful()) {
                Log.w(TAG, "Auth failed: " + response.code());
                if (raw.contains("anonymous_provider_disabled")) {
                    callback.onError("anon_disabled");
                } else {
                    callback.onError("auth");
                }
                return;
            }

            JSONObject json = new JSONObject(raw);
            String access = json.optString("access_token", "");
            String refresh = json.optString("refresh_token", "");
            long expiresIn = json.optLong("expires_in", 3600);

            if (access.isEmpty()) {
                Log.w(TAG, "Auth response missing access_token");
                callback.onError("auth");
                return;
            }

            String userId = "";
            JSONObject user = json.optJSONObject("user");
            if (user != null) userId = user.optString("id", "");

            Log.i(TAG, "Auth successful for user: " + userId.substring(0, Math.min(userId.length(), 8)) + "...");

            prefs(context).edit()
                    .putString(KEY_ACCESS, access)
                    .putString(KEY_REFRESH, refresh)
                    .putString(KEY_USER_ID, userId)
                    .putLong(KEY_EXPIRES_AT,
                            System.currentTimeMillis() + expiresIn * 1000L)
                    .apply();

            callback.onToken(access);

        } catch (Exception e) {
            Log.e(TAG, "Auth parse error", e);
            callback.onError("parse");
        }
    }

    @Nullable
    public static String validatePassword(String password) {
        return SupabaseClient.validatePasswordStrength(password);
    }

    public static void logSecurityEvent(String event, String details) {
        Log.w(TAG, "SECURITY EVENT: " + event + " - " + details);
    }

    private static SharedPreferences prefs(Context c) {
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            return EncryptedSharedPreferences.create(
                    PREFS,
                    masterKeyAlias,
                    c.getApplicationContext(),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | IOException e) {
            // SECURITY: Never fallback to plaintext - force re-login
            // Delete any plaintext file that might exist from old versions
            c.getApplicationContext().getSharedPreferences(PREFS + "_plain", Context.MODE_PRIVATE)
                .edit().clear().apply();
            // Return empty SharedPreferences that will trigger fresh login
            return c.getApplicationContext()
                    .getSharedPreferences(PREFS + "_failed", Context.MODE_PRIVATE);
        }
    }
}
