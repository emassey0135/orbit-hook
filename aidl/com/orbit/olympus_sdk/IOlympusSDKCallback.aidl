package com.orbit.olympus_sdk;

interface IOlympusSDKCallback {
    void onScreenReaderReady();
    void onUtteranceStart(String utteranceId);
    void onUtteranceDone(String utteranceId);
    void onUtteranceError(String utteranceId);
}
