package com.huawei.wearengine.p2p;
public interface SendCallback {
    void onSendProgress(long progress);
    void onSendResult(int result);
}
