package dev.evoker.homeholdcts;
interface IWakeWatcher {
    void destroy() = 16777114;
    String startWatching(boolean returnToOrigin, boolean powerEnabled, boolean hotwordEnabled) = 1;
    String stopWatching() = 2;
    String status() = 3;
}
