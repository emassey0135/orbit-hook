// The interface of Orbit's screen reader service, for interoperability. The order of the methods
// sets their transaction codes, so it must match Orbit's screen reader.
package com.orbit.screenreader;

import com.orbit.screenreader.IUARTKeypadCallback;
import com.orbit.screenreader.ISettingsCallback;
import com.orbit.olympus_sdk.IOlympusSDKCallback;

interface IScreenReaderService {
    void onFocusChanged(in Bundle widget);
    void onStateChanged(in Bundle widget);
    void onItemChanged(in Bundle widget);
    void onCharacterTyped(in Bundle data);
    void onCharacterDeleted(in Bundle data);
    void onDialogOpened(in Bundle data);
    void onDialogClosed(in Bundle data);
    void onDialogReturnedToFocus(in Bundle data);
    void onAppOpened(in Bundle data);
    void onContextMenuOpened(in Bundle data);
    void onContextMenuClosed(in Bundle data);
    void onTextInserted(in Bundle data);
    void onValidationMessage(String message);
    void onOperationResult(String message);
    void speakText(String text);
    void speakTextWithParams(in Bundle params);
    void stopSpeaking();
    void speakTextWithUtteranceId(String text, String utteranceId);
    void logEvent(in List<String> lines);
    void onEditBoxContentChanged(in Bundle data);
    void onProgressChanged(in Bundle data);
    void onProgressFinished(in Bundle data);
    void onIndeterminateProgressStarted(in Bundle data);
    void onIndeterminateProgressStopped(in Bundle data);
    void registerSDKCallback(IOlympusSDKCallback callback);
    void registerKeypadCallback(String packageName, IUARTKeypadCallback callback);
    void unregisterKeypadCallback(String packageName);
    void registerSettingsCallback(ISettingsCallback callback);
    void unregisterSettingsCallback(ISettingsCallback callback);
    void notifyAppForeground(String packageName);
    void notifyAppBackground(String packageName);
    void setPlayingMedia(boolean playing);
    String getFirmwareVersion();
    String getBootloaderVersion();
    String getSerialNumber();
    int getCharacterCommitTimeout();
    int getCharacterCommitFeedback();
    void playSound(int soundId);
    void vibrate(long millis);
    void powerOff();
    void reboot();
    int getBatteryPercentage();
    void sendData(byte command, in byte[] payload);
    int canSpeakLanguage(String languageTag);
}
