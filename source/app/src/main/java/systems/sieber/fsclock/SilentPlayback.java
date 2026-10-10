package systems.sieber.fsclock;

import android.media.MediaPlayer;

/**
 * Every moving wallpaper in this app is a picture that moves, never a sound — on every car,
 * every mode and every screen (main, passenger, cluster), with no exception.
 *
 * A car is the worst place to get this wrong: whatever the driver is listening to — radio,
 * Bluetooth, the music app — owns the speakers, and a wallpaper that so much as asks for them
 * pauses that music, or leaves it paused for good once a music app reads "lost focus" as "the
 * user moved on". So every video path here is a bare {@link MediaPlayer} at volume zero, and
 * none of them ever requests audio focus.
 *
 * Do NOT use a {@code VideoView} for a wallpaper or a preview: it requests AUDIOFOCUS_GAIN every
 * time it opens a clip — that was the "the passenger wallpaper stopped my music" report — and
 * before Android 8 there is no switch to stop it. A TextureView/Surface plus a MediaPlayer
 * passed through {@link #mute} is the pattern (WallpaperView, MediaWallpaperService and
 * LeopardPickerActivity all follow it).
 */
final class SilentPlayback {

    private SilentPlayback() { }

    /**
     * Volume zero. Call it once the data source is set (before prepare) and again once prepared,
     * so not one buffer is ever audible. Safe to call any number of times.
     */
    static void mute(MediaPlayer mp) {
        if(mp == null) return;
        try { mp.setVolume(0f, 0f); } catch(Throwable ignored) { }
    }
}
