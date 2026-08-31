package com.localdex.shizuku;

import android.view.Surface;
import android.view.MotionEvent;
import android.view.KeyEvent;

interface IDexUserService {
    void destroy() = 16777114; // Shizuku constant for destroy

    int createVirtualDisplay(String name, int width, int height, int dpi, in Surface surface);
    void releaseVirtualDisplay();
    int getDisplayId();

    void setDisplayWindowingMode(int displayId, int mode);
    boolean injectMotionEvent(in MotionEvent event, int displayId);
    boolean injectKeyEvent(in KeyEvent event, int displayId);
}
