package com.oxydroid;

/** Implemented by a running download so the UI (via {@link SteamDownloadState}) can stop it. */
public interface Cancellable {
    void cancel();
}
