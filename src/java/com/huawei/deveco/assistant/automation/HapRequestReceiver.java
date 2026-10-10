package com.huawei.deveco.assistant.automation;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public final class HapRequestReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.huawei.deveco.assistant.action.HAP_REQUEST";
    public static final String EXTRA_PATH = "hap_path";
    public static final String EXTRA_ID = "request_id";
    public static final String TAG = "DevEcoHapMod";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String id = intent.getStringExtra(EXTRA_ID);
        if (HapStatus.ACTION.equals(intent.getAction())) {
            String response = HapStatus.read(context, id);
            Log.i(TAG, response);
            if (isOrderedBroadcast()) {
                setResultCode(HapStatus.resultCode(response));
                setResultData(response);
            }
            return;
        }
        if (!ACTION.equals(intent.getAction())) return;

        String path = intent.getStringExtra(EXTRA_PATH);
        String bundleName = intent.getStringExtra("bundle_name");
        String operation = intent.getStringExtra("operation");
        String logId = id == null ? "<missing>" : id.replaceAll("[^A-Za-z0-9._-]", "_");
        if (logId.length() > 64) logId = "<too-long>";
        Log.i(TAG, "id=" + logId + " state=RECEIVED operation=" + operation);
        if ("install".equals(operation) || "uninstall".equals(operation))
            HapStatus.begin(context, id, operation);

        Intent work = new Intent(context, HapProbeService.class);
        work.putExtra(EXTRA_ID, id);
        work.putExtra(EXTRA_PATH, path);
        work.putExtra("bundle_name", bundleName);
        work.putExtra("operation", operation);
        try {
            ComponentName started = Build.VERSION.SDK_INT >= 26
                    ? context.startForegroundService(work)
                    : context.startService(work);
            if (started == null) throw new IllegalStateException("Service not found");
            if (isOrderedBroadcast()) {
                setResultCode(1);
                setResultData(id);
            }
        } catch (RuntimeException error) {
            Log.e(TAG, "id=" + logId + " state=START_FAILED", error);
            if ("install".equals(operation) || "uninstall".equals(operation))
                HapStatus.finish(context, id, "START_FAILED");
            if (isOrderedBroadcast()) {
                setResultCode(3);
                setResultData("START_FAILED");
            }
        }
    }
}
