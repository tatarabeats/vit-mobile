package com.shunp.vitmobile;

import android.os.ParcelFileDescriptor;

interface IShizukuTouchService {
    ParcelFileDescriptor listDevices() = 0;
    ParcelFileDescriptor readEvents(String device) = 1;
    // Shizuku's reserved destroy transaction (FIRST_CALL_TRANSACTION + this ID).
    void destroy() = 16777114;
}
