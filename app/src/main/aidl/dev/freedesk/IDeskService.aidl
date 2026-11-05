package dev.freedesk;

import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

// Runs inside the Shizuku "user service" process (shell uid 2000).
interface IDeskService {
    // Reserved by Shizuku: must exist and must be 16777114.
    void destroy() = 16777114;

    int createDisplay(in Surface surface, int width, int height, int dpi, float refreshRate, boolean secure) = 1;
    void resizeDisplay(int displayId, int width, int height, int dpi) = 2;
    void setSurface(int displayId, in Surface surface) = 3;
    void releaseDisplay(int displayId) = 4;
    // Returns "" on success, otherwise the error text printed by `am start`.
    String launch(int displayId, String component, boolean newTask) = 5;
    oneway void injectMotion(int displayId, in MotionEvent event) = 6;
    oneway void injectKey(int displayId, in KeyEvent event) = 7;
    String diagnose() = 8;
    String applySettings(boolean enable) = 9;
}
