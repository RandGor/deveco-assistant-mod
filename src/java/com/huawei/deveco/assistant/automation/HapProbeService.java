package com.huawei.deveco.assistant.automation;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

import b.d.f.a.d;
import b.d.f.a.e;
import b.d.f.a.f;
import com.huawei.wearengine.HiWear;
import com.huawei.wearengine.device.Device;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class HapProbeService extends Service {
    private static final String CHANNEL_ID = "deveco_hap_probe";
    private static final int NOTIFICATION_ID = 61004;
    private static final String TAG = HapRequestReceiver.TAG;
    private final AtomicBoolean busy = new AtomicBoolean(false);

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String id = intent == null ? null : intent.getStringExtra(HapRequestReceiver.EXTRA_ID);
        String path = intent == null ? null : intent.getStringExtra(HapRequestReceiver.EXTRA_PATH);
        String operation = intent == null ? null : intent.getStringExtra("operation");
        if (!busy.compareAndSet(false, true)) {
            Log.w(TAG, "id=" + safeId(id) + " state=BUSY");
            if ("install".equals(operation) || "uninstall".equals(operation))
                HapStatus.finish(this, id, "BUSY");
            return START_NOT_STICKY;
        }
        try {
            startForeground(NOTIFICATION_ID, notification());
            Runnable chosen = null;
            if (!HapStatus.validId(id)) {
                Log.w(TAG, "id=<invalid> state=REJECTED reason=INVALID_REQUEST_ID");
            } else if ("uninstall".equals(operation)) {
                String raw = intent.getStringExtra("bundle_name");
                final String bundleName = raw == null ? null : raw.trim();
                if (bundleName == null || bundleName.length() > 255
                        || !bundleName.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")) {
                    reject(id, "INVALID_BUNDLE_NAME");
                } else {
                    final String requestId = id;
                    chosen = new Runnable() {
                        @Override public void run() {
                            new HapUninstallWorker(HapProbeService.this, requestId, bundleName).run();
                        }
                    };
                }
            } else if (operation == null || "probe".equals(operation) || "install".equals(operation)) {
                final File hap = validateAndLog(id, path);
                if (hap != null) {
                    final String requestId = id;
                    final boolean install = "install".equals(operation);
                    chosen = new Runnable() {
                        @Override public void run() {
                            if (install) {
                                new HapInstallWorker(HapProbeService.this, requestId, hap).run();
                            } else {
                                probeDevices(requestId);
                            }
                        }
                    };
                }
            } else {
                reject(id, "INVALID_OPERATION");
            }
            if (chosen != null) {
                final Runnable work = chosen;
                new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            work.run();
                        } finally {
                            stopForeground(true);
                            busy.set(false);
                            stopSelf();
                        }
                    }
                }, "DevEcoHapWorker").start();
                return START_NOT_STICKY;
            }
        } catch (RuntimeException error) {
            Log.e(TAG, "id=" + safeId(id) + " state=SERVICE_FAILED", error);
            if ("install".equals(operation) || "uninstall".equals(operation))
                HapStatus.finish(this, id, ("uninstall".equals(operation)
                        ? "UNINSTALL_UNKNOWN" : "INSTALL_UNKNOWN") + " reason=SERVICE_FAILED");
        }
        stopForeground(true);
        busy.set(false);
        stopSelf();
        return START_NOT_STICKY;
    }
    private void probeDevices(final String id) {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicBoolean settled = new AtomicBoolean(false);
        final AtomicReference<List<Device>> devices = new AtomicReference<>();
        final AtomicReference<Exception> failure = new AtomicReference<>();
        try {
            Log.i(TAG, "id=" + id + " state=DEVICE_QUERY_BEGIN");
            f<List<Device>> task = HiWear.getDeviceClient(this).getBondedDevices();
            task.a(new e<List<Device>>() {
                @Override public void onSuccess(List<Device> value) {
                    if (settled.compareAndSet(false, true)) {
                        devices.set(value);
                        done.countDown();
                    }
                }
            });
            task.a(new d() {
                @Override public void onFailure(Exception error) {
                    if (settled.compareAndSet(false, true)) {
                        failure.set(error);
                        done.countDown();
                    }
                }
            });
            if (!done.await(20, TimeUnit.SECONDS)) {
                settled.set(true);
                Log.w(TAG, "id=" + id + " state=DEVICE_TIMEOUT");
                return;
            }
            Exception error = failure.get();
            if (error != null) {
                Log.e(TAG, "id=" + id + " state=DEVICE_QUERY_FAILED", error);
                return;
            }
            List<Device> found = devices.get();
            if (found == null) {
                Log.w(TAG, "id=" + id + " state=DEVICE_QUERY_FAILED reason=NULL_RESULT");
                return;
            }
            int connected = 0;
            for (Device device : found) {
                if (device != null && device.isConnected()) {
                    connected++;
                    Log.i(TAG, "id=" + id + " state=DEVICE_CONNECTED name="
                            + device.getName() + " uuid=" + device.getUuid());
                }
            }
            Log.i(TAG, "id=" + id + " state=DEVICE_QUERY_DONE bonded=" + found.size()
                    + " connected=" + connected);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "id=" + id + " state=DEVICE_QUERY_INTERRUPTED", error);
        } catch (RuntimeException | LinkageError error) {
            Log.e(TAG, "id=" + id + " state=DEVICE_QUERY_FAILED", error);
        }
    }

    private File validateAndLog(String id, String path) {
        if (id == null || !id.matches("[A-Za-z0-9._-]{1,64}")) {
            Log.w(TAG, "id=<invalid> state=REJECTED reason=INVALID_REQUEST_ID");
            return null;
        }
        try {
            if (path == null || path.isEmpty()) {
                reject(id, "MISSING_PATH");
                return null;
            }
            if (path.length() > 1024 || !new File(path).isAbsolute()) {
                reject(id, "INVALID_PATH");
                return null;
            }
            File hap = new File(path).getCanonicalFile();
            File haps = new File(Environment.getExternalStorageDirectory(), "haps")
                    .getCanonicalFile();
            if (!haps.equals(hap.getParentFile())) {
                reject(id, "OUTSIDE_HAPS");
                return null;
            }
            if (!hap.getName().endsWith(".hap")) {
                reject(id, "NOT_HAP");
                return null;
            }
            if (!hap.isFile() || !hap.canRead()) {
                reject(id, "NOT_FILE_OR_INACCESSIBLE");
                return null;
            }
            long bytes = hap.length();
            if (bytes <= 0) {
                reject(id, "EMPTY_FILE");
                return null;
            }
            Log.i(TAG, "id=" + id + " state=FILE_READY path=" + hap.getPath()
                    + " bytes=" + bytes);
            return hap;
        } catch (IOException error) {
            Log.e(TAG, "id=" + id + " state=REJECTED reason=PATH_IO", error);
            HapStatus.finish(this, id, "REJECTED reason=PATH_IO");
        } catch (SecurityException error) {
            Log.e(TAG, "id=" + id + " state=REJECTED reason=FILE_ACCESS_DENIED", error);
            HapStatus.finish(this, id, "REJECTED reason=FILE_ACCESS_DENIED");
        }
        return null;
    }

    private void reject(String id, String reason) {
        Log.w(TAG, "id=" + id + " state=REJECTED reason=" + reason);
        HapStatus.finish(this, id, "REJECTED reason=" + reason);
    }

    private static String safeId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9._-]{1,64}")) {
            return "<invalid>";
        }
        return id;
    }

    private Notification notification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager)
                    getSystemService(NOTIFICATION_SERVICE);
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "DevEco HAP requests", NotificationManager.IMPORTANCE_LOW));
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder.setSmallIcon(getApplicationInfo().icon)
                .setContentTitle("DevEco HAP request")
                .setContentText("Working with watch")
                .setOngoing(true)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
