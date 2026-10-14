package com.orbit.screenreader;

interface IUARTKeypadCallback {
    boolean onInternalKeyEvent(in Bundle event);
    void onAdjustVolume(boolean increase);
}
