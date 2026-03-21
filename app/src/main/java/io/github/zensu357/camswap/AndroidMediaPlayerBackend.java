package io.github.zensu357.camswap;

import android.media.MediaPlayer;
import android.os.ParcelFileDescriptor;
import android.view.Surface;

import io.github.zensu357.camswap.utils.LogUtil;
import io.github.zensu357.camswap.utils.VideoManager;

/**
 * Local-file playback backend wrapping {@link android.media.MediaPlayer}.
 * Drop-in replacement for the raw MediaPlayer usage previously scattered
 * across MediaPlayerManager — behaviour is identical.
 */
public final class AndroidMediaPlayerBackend implements SurfacePlayerBackend {

    private MediaPlayer player;
    private Surface outputSurface;
    private Listener listener;
    private boolean looping = true;
    private float volume = 0f;
    private MediaSourceDescriptor currentSource;

    public AndroidMediaPlayerBackend() {
        player = new MediaPlayer();
    }

    @Override
    public void setOutputSurface(Surface surface) {
        this.outputSurface = surface;
        if (player != null) {
            player.setSurface(surface);
        }
    }

    @Override
    public void open(MediaSourceDescriptor source) {
        this.currentSource = source;
        try {
            player.reset();
            player.setLooping(looping);
            player.setVolume(volume, volume);

            // Data source: prefer Provider PFD, fallback to file path
            if (source.useProviderPfd) {
                ParcelFileDescriptor pfd = VideoManager.getVideoPFD();
                if (pfd != null) {
                    player.setDataSource(pfd.getFileDescriptor());
                    pfd.close();
                } else {
                    player.setDataSource(source.localPath);
                }
            } else {
                player.setDataSource(source.localPath);
            }

            if (outputSurface != null) {
                player.setSurface(outputSurface);
            }

            player.setOnPreparedListener(mp -> {
                mp.start();
                if (listener != null) listener.onReady();
            });
            player.setOnCompletionListener(mp -> {
                if (listener != null) listener.onCompletion();
            });
            player.setOnErrorListener((mp, what, extra) -> {
                if (listener != null) {
                    listener.onError("MediaPlayer error what=" + what + " extra=" + extra, null);
                }
                return true;
            });

            player.prepare();
            player.start();
        } catch (Exception e) {
            LogUtil.log("【CS】AndroidMediaPlayerBackend.open 异常: " + e);
            if (listener != null) {
                listener.onError("open failed", e);
            }
        }
    }

    @Override
    public void restart() {
        if (player == null || currentSource == null) return;
        try {
            if (player.isPlaying()) player.stop();
            player.reset();
            open(currentSource);
        } catch (Exception e) {
            LogUtil.log("【CS】AndroidMediaPlayerBackend.restart 异常: " + e);
        }
    }

    @Override
    public void stop() {
        if (player == null) return;
        try {
            if (player.isPlaying()) player.stop();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void release() {
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {
            }
            player.release();
            player = null;
        }
    }

    @Override
    public boolean isPlaying() {
        if (player == null) return false;
        try {
            return player.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public long getCurrentPositionMs() {
        if (player == null) return 0;
        try {
            return player.getCurrentPosition();
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public long getDurationMs() {
        if (player == null) return 0;
        try {
            return player.getDuration();
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public void setLooping(boolean looping) {
        this.looping = looping;
        if (player != null) {
            player.setLooping(looping);
        }
    }

    @Override
    public void setVolume(float volume) {
        this.volume = volume;
        if (player != null) {
            player.setVolume(volume, volume);
        }
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Expose underlying MediaPlayer for legacy code that needs direct access. */
    public MediaPlayer getRawPlayer() {
        return player;
    }
}
