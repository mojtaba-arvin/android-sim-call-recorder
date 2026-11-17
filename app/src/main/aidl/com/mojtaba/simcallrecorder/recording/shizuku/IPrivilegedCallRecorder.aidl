package com.mojtaba.simcallrecorder.recording.shizuku;

import android.os.ParcelFileDescriptor;

interface IPrivilegedCallRecorder {
    int getHostUid();

    // Returns the actual channel count (2 or 1); 0 means initialization/capture was rejected.
    int startVoiceCallCapture(in ParcelFileDescriptor output, int sampleRate, int preferredChannels);

    void stopVoiceCallCapture();

    boolean isCapturing();

    String getLastError();

    // Reserved Shizuku UserService destroy transaction.
    void destroy() = 16777114;
}
