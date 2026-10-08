package com.shunp.vitmobile;

import android.os.ParcelFileDescriptor;

interface IShizukuTouchService {
    ParcelFileDescriptor listDevices() = 0;
    ParcelFileDescriptor readEvents(String device) = 1;
    // Shizuku's reserved destroy transaction (FIRST_CALL_TRANSACTION + this ID).
    void destroy() = 16777114;
    // Explicit IDs preserve the existing transactions, including Shizuku's destroy.
    int installApk(in ParcelFileDescriptor apk, long size) = 2;
    String getLastInstallOutput() = 3;
    // Append-only; existing transaction IDs must not change.
    int runShell(String cmd) = 4;
}
