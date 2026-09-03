package com.safebrowser.app;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Diperlukan agar aplikasi dapat disetel sebagai device owner lewat ADB atau MDM,
 * yang memungkinkan lock task tanpa dialog konfirmasi peserta:
 *
 *   adb shell dpm set-device-owner com.safebrowser.app/.DeviceAdminReceiver
 */
public class DeviceAdminReceiver extends android.app.admin.DeviceAdminReceiver {

    private static final String TAG = "SafeBrowserAdmin";

    @Override
    public void onEnabled(Context context, Intent intent) {
        Log.i(TAG, "Device admin diaktifkan");
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        Log.i(TAG, "Device admin dinonaktifkan");
    }
}
