// SPDX-License-Identifier: GPL-3.0-only
package dev.evoker.homeholdcts;
public final class ShizukuApp extends android.app.Application {
    public ShizukuApp() { rikka.shizuku.ShizukuProvider.disableAutomaticSuiInitialization(); }
}
