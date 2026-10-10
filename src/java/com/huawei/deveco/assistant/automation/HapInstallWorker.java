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
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class HapInstallWorker {
    private static final String TAG = HapRequestReceiver.TAG;
    private static final int FILE_SENT = 207;
    private static final int CONNECTION_LOST = 0x769f2449;
    private static final long MAX_BIN_BYTES = 256L * 1024 * 1024;
    private final Context context;
    private final String id;
    private final File hap;

    HapInstallWorker(Context context, String id, File hap) {
        this.context = context;
        this.id = id;
        this.hap = hap;
    }

    void run() {
        P2pClient p2p = null;
        Receiver receiver = null;
        File bin = null;
        String persisted = "INSTALL_UNKNOWN reason=UNCONFIRMED";
        String stage = "PREPARE";
        try {
            Device device = singleConnectedDevice();
            if (device == null) {
                persisted = "DEVICE_NOT_READY";
                return;
            }
            bin = extractSingleBin();
            Log.i(TAG, "id=" + id + " state=BIN_READY bytes=" + bin.length());
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
                    if (kind == 0x65) {
                        if (startLogged.compareAndSet(false, true))
                            Log.i(TAG, "id=" + id + " state=WATCH_INSTALL_STARTED");
                    } else if (commandStarted.get() && kind == 0x66
                            && finalState.compareAndSet(null, "INSTALL_SUCCEEDED")) {
                        finalReply.countDown();
                    } else if (commandStarted.get() && kind == 0x67
                            && finalState.compareAndSet(null, "INSTALL_FAILED code=" + code)) {
                        finalReply.countDown();
                    }
                }
            };
            stage = "REGISTER";
            awaitTask(p2p.registerReceiver(device, receiver), 20, "REGISTER_FAILED");
            Log.i(TAG, "id=" + id + " state=RECEIVER_REGISTERED");

            final CountDownLatch fileReply = new CountDownLatch(1);
            final AtomicInteger fileCode = new AtomicInteger(Integer.MIN_VALUE);
            SendCallback fileCallback = new SendCallback() {
                @Override public void onSendProgress(long progress) {}
                @Override public void onSendResult(int result) {
                    if (fileCode.compareAndSet(Integer.MIN_VALUE, result)) {
                        fileReply.countDown();
                    }
                }
            };
            Message fileMessage = new Message.Builder().setPayload(bin).build();
            f<Object> fileTask = p2p.send(device, fileMessage, fileCallback);
            fileTask.a(new d() {
                @Override public void onFailure(Exception error) {
                    Log.e(TAG, "id=" + id + " state=FILE_SUBMISSION_FAILED", error);
                    fileReply.countDown();
                }
            });
            Log.i(TAG, "id=" + id + " state=FILE_SEND_BEGIN");
            if (!fileReply.await(180, TimeUnit.SECONDS)) {
                persisted = "INSTALL_UNKNOWN reason=FILE_SEND_TIMEOUT";
                Log.w(TAG, "id=" + id + " state=INSTALL_UNKNOWN reason=FILE_SEND_TIMEOUT");
                return;
            }
            int result = fileCode.get();
            if (result == Integer.MIN_VALUE) {
                persisted = "FILE_SEND_FAILED reason=SUBMISSION_FAILED";
                Log.w(TAG, "id=" + id + " state=" + persisted);
                return;
            }
            if (result != FILE_SENT) {
                String reason = result == CONNECTION_LOST ? "CONNECTION_LOST" : "SEND_CODE_" + result;
                persisted = "FILE_SEND_FAILED reason=" + reason;
                Log.w(TAG, "id=" + id + " state=FILE_SEND_FAILED reason=" + reason);
                return;
            }
            Log.i(TAG, "id=" + id + " state=FILE_SENT code=" + result);

            byte[] fileName = bin.getName().getBytes(StandardCharsets.UTF_8);
            byte[] command = new byte[fileName.length + 1];
            System.arraycopy(fileName, 0, command, 1, fileName.length);
            Message installMessage = new Message.Builder().setPayload(command).build();
            final AtomicReference<Exception> commandError = new AtomicReference<>();
            commandStarted.set(true);
            f<Object> installTask = p2p.send(device, installMessage, new SendCallback() {
                @Override public void onSendProgress(long progress) {}
                @Override public void onSendResult(int sendCode) {
                    Log.i(TAG, "id=" + id + " state=COMMAND_SEND_RESULT code=" + sendCode);
                }
            });
            installTask.a(new d() {
                @Override public void onFailure(Exception error) {
                    commandError.set(error);
                    finalReply.countDown();
                }
            });
            Log.i(TAG, "id=" + id + " state=INSTALL_COMMAND_SUBMITTED file=" + bin.getName());
            if (!finalReply.await(180, TimeUnit.SECONDS)) {
                persisted = "INSTALL_UNKNOWN reason=WATCH_REPLY_TIMEOUT";
                Log.w(TAG, "id=" + id + " state=INSTALL_UNKNOWN reason=WATCH_REPLY_TIMEOUT");
                return;
            }
            String state = finalState.get();
            if (state != null) {
                persisted = state;
                Log.i(TAG, "id=" + id + " state=" + state);
            } else {
                persisted = "INSTALL_UNKNOWN reason=COMMAND_SUBMISSION_FAILED";
                Log.e(TAG, "id=" + id + " state=INSTALL_UNKNOWN reason=COMMAND_SUBMISSION_FAILED",
                        commandError.get());
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            persisted = "INSTALL_UNKNOWN reason=INTERRUPTED";
            Log.e(TAG, "id=" + id + " state=INSTALL_UNKNOWN reason=INTERRUPTED", error);
        } catch (IOException error) {
            persisted = stage + "_FAILED";
            Log.e(TAG, "id=" + id + " state=" + persisted, error);
        } catch (RuntimeException | LinkageError error) {
            persisted = "INSTALL_UNKNOWN reason=SDK_ERROR";
            Log.e(TAG, "id=" + id + " state=INSTALL_UNKNOWN reason=SDK_ERROR", error);
        } finally {
            HapStatus.finish(context, id, persisted);
            if (p2p != null && receiver != null) {
                try {
                    p2p.unregisterReceiver(receiver);
                } catch (RuntimeException error) {
                    Log.w(TAG, "id=" + id + " state=UNREGISTER_FAILED", error);
                }
            }
            if (bin != null && !bin.delete()) {
                Log.w(TAG, "id=" + id + " state=CACHE_DELETE_FAILED");
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

    private File extractSingleBin() throws IOException {
        File dir = new File(context.getCacheDir(), "hap-install");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create cache directory");
        File output = new File(dir, "entry.bin");
        if (output.exists() && !output.delete()) throw new IOException("Cannot clear stale bin");
        try (ZipFile archive = new ZipFile(hap)) {
            ZipEntry entry = null;
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry item = entries.nextElement();
                if (item.isDirectory()) continue;
                if (entry != null || !item.getName().endsWith(".bin"))
                    throw new IOException("HAP must contain exactly one .bin file");
                entry = item;
            }
            if (entry == null) throw new IOException("No .bin in HAP");
            long total = 0;
            try (InputStream input = archive.getInputStream(entry);
                 FileOutputStream target = new FileOutputStream(output)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_BIN_BYTES) throw new IOException("BIN too large");
                    target.write(buffer, 0, read);
                }
            } catch (IOException error) {
                output.delete();
                throw error;
            }
            if (total == 0) {
                output.delete();
                throw new IOException("Empty BIN");
            }
        }
        return output;
    }

    private void awaitTask(f<Object> task, int seconds, String failedState)
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
        if (!done.await(seconds, TimeUnit.SECONDS)) throw new IOException(failedState + ": timeout");
        if (failure.get() != null) throw new IOException(failedState, failure.get());
    }
}
