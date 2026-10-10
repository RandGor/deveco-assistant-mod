package com.huawei.deveco.assistant.automation;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

final class HapStatus {
    static final String ACTION = "com.huawei.deveco.assistant.action.HAP_STATUS";
    private static final String PREFS = "hap_install_status";
    private static final long STALE_MS = 10 * 60 * 1000L;

    private HapStatus() {}

    static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9._-]{1,64}");
    }

    static void begin(Context context, String id, String operation) {
        put(context, id, "IN_PROGRESS operation=" + operation);
    }

    static void finish(Context context, String id, String state) {
        put(context, id, state);
    }

    private static void put(Context context, String id, String state) {
        if (!validId(id)) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.edit().putString("state_" + id, state)
                .putLong("time_" + id, System.currentTimeMillis()).commit()) {
            Log.e(HapRequestReceiver.TAG, "id=" + id + " state=STATUS_SAVE_FAILED");
        }
    }

    static String read(Context context, String id) {
        if (!validId(id)) return "id=<invalid> state=INVALID_REQUEST_ID";
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String state = prefs.getString("state_" + id, null);
        if (state == null) return "id=" + id + " state=NOT_FOUND";
        long time = prefs.getLong("time_" + id, 0);
        if (state.startsWith("IN_PROGRESS") && System.currentTimeMillis() - time > STALE_MS) {
            state = (state.contains("operation=uninstall") ? "UNINSTALL_UNKNOWN" : "INSTALL_UNKNOWN")
                    + " reason=STALE_IN_PROGRESS";
        }
        return "id=" + id + " state=" + state;
    }

    static int resultCode(String status) {
        if (status.contains("state=INSTALL_SUCCEEDED")
                || status.contains("state=UNINSTALL_SUCCEEDED")) return 1;
        if (status.contains("state=IN_PROGRESS")) return 0;
        if (status.contains("state=INSTALL_UNKNOWN")
                || status.contains("state=UNINSTALL_UNKNOWN")) return 3;
        if (status.contains("state=NOT_FOUND")) return 4;
        return 2;
    }
}
