package com.mojtaba.simcallrecorder.recording.shizuku;

import android.os.ParcelFileDescriptor;

interface IPrivilegedCallRecorder {
    int getHostUid() = 1;

    // Returns the actual channel count (2 or 1); 0 means initialization/capture was rejected.
    int startVoiceCallCapture(in ParcelFileDescriptor output, int sampleRate, int preferredChannels) = 2;

    void stopVoiceCallCapture() = 3;

    boolean isCapturing() = 4;

    String getLastError() = 5;

    // True only when the UserService established the required shell audio attribution.
    boolean isCaptureReady() = 6;

    // Reserved Shizuku UserService destroy transaction.
    void destroy() = 16777114;
}
