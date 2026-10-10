package com.huawei.deveco.assistant.automation;

import android.content.Context;
import android.util.Log;

import b.d.f.a.d;
import b.d.f.a.e;
import b.d.f.a.f;
import com.huawei.wearengine.HiWear;
import com.huawei.wearengine.device.Device;
import com.huawei.wearengine.p2p.Message;
import com.huawei.wearengine.p2p.P2pClient;
import com.huawei.wearengine.p2p.Receiver;
import com.huawei.wearengine.p2p.SendCallback;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class HapUninstallWorker {
    private static final String TAG = HapRequestReceiver.TAG;
    private final Context context;
    private final String id;
    private final String bundleName;

    HapUninstallWorker(Context context, String id, String bundleName) {
        this.context = context;
        this.id = id;
        this.bundleName = bundleName;
    }

    void run() {
        P2pClient p2p = null;
        Receiver receiver = null;
        String persisted = "UNINSTALL_UNKNOWN reason=UNCONFIRMED";
        try {
            Device device = singleConnectedDevice();
            if (device == null) {
                persisted = "DEVICE_NOT_READY";
                return;
            }
            p2p = HiWear.getP2pClient(context)
                    .setPeerPkgName("in.huawei.bundlems")
                    .setPeerFingerPrint("SystemApp");

            final CountDownLatch finalReply = new CountDownLatch(1);
            final AtomicBoolean commandStarted = new AtomicBoolean(false);
            final AtomicBoolean startLogged = new AtomicBoolean(false);
            final AtomicReference<String> finalState = new AtomicReference<>();
            receiver = new Receiver() {
                @Override public void onReceiveMessage(Message message) {
                    byte[] data = message == null ? null : message.getData();
                    if (data == null || data.length < 2) return;
                    int kind = data[0] & 0xff;
                    int code = data[1] & 0xff;
                    if (kind == 0x68) {
                        if (startLogged.compareAndSet(false, true))
                            Log.i(TAG, "id=" + id + " state=WATCH_UNINSTALL_STARTED");
                    } else if (commandStarted.get() && kind == 0x69
                            && finalState.compareAndSet(null, "UNINSTALL_SUCCEEDED")) {
                        finalReply.countDown();
                    } else if (commandStarted.get() && kind == 0x6a
                            && finalState.compareAndSet(null, "UNINSTALL_FAILED code=" + code)) {
                        finalReply.countDown();
                    }
                }
            };
            awaitTask(p2p.registerReceiver(device, receiver), 20);
            Log.i(TAG, "id=" + id + " state=RECEIVER_REGISTERED");

            byte[] name = bundleName.getBytes(StandardCharsets.UTF_8);
            byte[] command = new byte[name.length + 1];
            command[0] = 1;
            System.arraycopy(name, 0, command, 1, name.length);
            Message request = new Message.Builder().setPayload(command).build();
            final AtomicReference<Exception> commandError = new AtomicReference<>();
            commandStarted.set(true);
            f<Object> task = p2p.send(device, request, new SendCallback() {
                @Override public void onSendProgress(long progress) {}
                @Override public void onSendResult(int sendCode) {
                    Log.i(TAG, "id=" + id + " state=COMMAND_SEND_RESULT code=" + sendCode);
                }
            });
            task.a(new d() {
                @Override public void onFailure(Exception error) {
                    commandError.set(error);
                    finalReply.countDown();
                }
            });
            Log.i(TAG, "id=" + id + " state=UNINSTALL_COMMAND_SUBMITTED bundle=" + bundleName);
            if (!finalReply.await(180, TimeUnit.SECONDS)) {
                persisted = "UNINSTALL_UNKNOWN reason=WATCH_REPLY_TIMEOUT";
                Log.w(TAG, "id=" + id + " state=" + persisted);
                return;
            }
            String state = finalState.get();
            if (state != null) {
                persisted = state;
                Log.i(TAG, "id=" + id + " state=" + state);
            } else {
                persisted = "UNINSTALL_UNKNOWN reason=COMMAND_SUBMISSION_FAILED";
                Log.e(TAG, "id=" + id + " state=" + persisted, commandError.get());
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            persisted = "UNINSTALL_UNKNOWN reason=INTERRUPTED";
            Log.e(TAG, "id=" + id + " state=" + persisted, error);
        } catch (IOException error) {
            persisted = "REGISTER_FAILED";
            Log.e(TAG, "id=" + id + " state=" + persisted, error);
        } catch (RuntimeException | LinkageError error) {
            persisted = "UNINSTALL_UNKNOWN reason=SDK_ERROR";
            Log.e(TAG, "id=" + id + " state=" + persisted, error);
        } finally {
            HapStatus.finish(context, id, persisted);
            if (p2p != null && receiver != null) {
                try {
                    p2p.unregisterReceiver(receiver);
                } catch (RuntimeException error) {
                    Log.w(TAG, "id=" + id + " state=UNREGISTER_FAILED", error);
                }
            }
        }
    }

    private Device singleConnectedDevice() throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<List<Device>> devices = new AtomicReference<>();
        final AtomicReference<Exception> failure = new AtomicReference<>();
        f<List<Device>> task = HiWear.getDeviceClient(context).getBondedDevices();
        task.a(new e<List<Device>>() {
            @Override public void onSuccess(List<Device> value) {
                devices.set(value);
                done.countDown();
            }
        });
        task.a(new d() {
            @Override public void onFailure(Exception error) {
                failure.set(error);
                done.countDown();
            }
        });
        if (!done.await(20, TimeUnit.SECONDS)) {
            Log.w(TAG, "id=" + id + " state=DEVICE_TIMEOUT");
            return null;
        }
        if (failure.get() != null) {
            Log.e(TAG, "id=" + id + " state=DEVICE_QUERY_FAILED", failure.get());
            return null;
        }
        List<Device> connected = new ArrayList<>();
        List<Device> found = devices.get();
        if (found != null) {
            for (Device device : found) {
                if (device != null && device.isConnected()) connected.add(device);
            }
        }
        Log.i(TAG, "id=" + id + " state=DEVICE_QUERY_DONE bonded="
                + (found == null ? 0 : found.size()) + " connected=" + connected.size());
        if (connected.size() != 1) {
            Log.w(TAG, "id=" + id + " state="
                    + (connected.isEmpty() ? "NO_CONNECTED_DEVICE" : "MULTIPLE_CONNECTED_DEVICES"));
            return null;
        }
        return connected.get(0);
    }

    private void awaitTask(f<Object> task, int seconds)
            throws InterruptedException, IOException {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Exception> failure = new AtomicReference<>();
        task.a(new e<Object>() {
            @Override public void onSuccess(Object ignored) { done.countDown(); }
        });
        task.a(new d() {
            @Override public void onFailure(Exception error) {
                failure.set(error);
                done.countDown();
            }
        });
        if (!done.await(seconds, TimeUnit.SECONDS)) throw new IOException("register timeout");
        if (failure.get() != null) throw new IOException("register failed", failure.get());
    }
}
