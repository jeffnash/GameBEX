package com.gamebex.client.launch;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class OwnedFlycastTargetActivity extends Activity {
    static final String OBSERVATIONS = "owned-flycast-launch-observations";
    static final String FINISH = "finish-owned-flycast-target";
    private long instance;

    private SharedPreferences observations() { return getSharedPreferences(OBSERVATIONS, MODE_PRIVATE); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (getIntent().getBooleanExtra(FINISH, false)) { finishAndRemoveTask(); return; }
        instance = System.nanoTime();
        Uri uri = getIntent().getData();
        SharedPreferences prefs = observations();
        SharedPreferences.Editor result = prefs.edit()
            .putInt("created", prefs.getInt("created", 0) + 1)
            .putLong("instance", instance)
            .putString("delivered-uri", String.valueOf(uri))
            .putString("consumed-uri", String.valueOf(uri))
            .remove("failure");
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(getContentResolver().openFileDescriptor(uri, "r"))) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) >= 0) bytes.write(buffer, 0, count);
            result.putString("consumed-bytes", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP));
        } catch (Exception failure) {
            result.putString("failure", failure.getClass().getName() + ": " + failure.getMessage());
        }
        result.commit();
    }

    @Override protected void onNewIntent(Intent incoming) {
        super.onNewIntent(incoming);
        if (incoming.getBooleanExtra(FINISH, false)) { finishAndRemoveTask(); return; }
        // This owned consumer deliberately reads VIEW data only on creation.
        observations().edit().putString("delivered-uri", String.valueOf(incoming.getData())).commit();
    }

    @Override protected void onDestroy() {
        if (instance != 0) observations().edit().putLong("destroyed-instance", instance).commit();
        super.onDestroy();
    }
}
