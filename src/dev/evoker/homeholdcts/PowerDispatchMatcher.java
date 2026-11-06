// SPDX-License-Identifier: GPL-3.0-only
package dev.evoker.homeholdcts;

/** Matches the ColorOS system-server speech-service branch paired with a real power long press. */
final class PowerDispatchMatcher {
    private static final long WINDOW_MS = 750;

    static boolean isSpeechServiceStart(int uid, String line) {
        return uid == 1000 && line != null
                && line.startsWith("ActivityManager: Background started FGS: Allowed ")
                && line.contains("act=heytap.intent.action.ACTIVATE_SPEECH_ASSIST")
                && line.contains("cmp=com.heytap.speechassist/.core.SpeechService");
    }

    static boolean isPaired(long first, long second) {
        return first >= 0 && second >= 0 && Math.abs(first - second) <= WINDOW_MS;
    }

    private PowerDispatchMatcher() {}
}
