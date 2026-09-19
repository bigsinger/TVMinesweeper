package com.bigsinger.tvminesweeper.util;

import android.content.Context;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.SystemClock;
import android.util.Log;

import com.bigsinger.tvminesweeper.R;

import java.util.HashSet;
import java.util.Set;

/** 使用 API 18 可用的 SoundPool 播放短小的原创合成音效。 */
public final class SoundEffects {
    /** 光标移动。 */
    public static final int MOVE = 0;
    /** 翻开安全格。 */
    public static final int OPEN = 1;
    /** 插旗。 */
    public static final int FLAG = 2;
    /** 取消旗帜。 */
    public static final int UNFLAG = 3;
    /** 通关。 */
    public static final int WIN = 4;
    /** 踩雷。 */
    public static final int LOSE = 5;
    /** 打开菜单或切换菜单选项。 */
    public static final int MENU = 6;

    private static final String TAG = "TVMinesweeper";
    private static final int MAX_STREAMS = 4;
    private static final long MOVE_INTERVAL_MILLIS = 65L;
    private static final int[] RESOURCES = {R.raw.move, R.raw.open, R.raw.flag,
            R.raw.unflag, R.raw.win, R.raw.lose, R.raw.menu};
    private static final float[] VOLUMES = {0.18f, 0.36f, 0.40f, 0.32f, 0.48f, 0.42f, 0.32f};

    private final Set<Integer> loadedSamples = new HashSet<Integer>();
    private final int[] samples = new int[RESOURCES.length];
    private final int[] playingStreams = new int[RESOURCES.length];
    private SoundPool soundPool;
    private boolean enabled = true;
    private boolean paused;
    private long lastMoveMillis = -MOVE_INTERVAL_MILLIS;

    /** 异步加载音频；尚未加载或音频设备不可用时直接略过播放。 */
    @SuppressWarnings("deprecation")
    public SoundEffects(Context context) {
        try {
            soundPool = new SoundPool(MAX_STREAMS, AudioManager.STREAM_MUSIC, 0);
            soundPool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
                @Override
                public void onLoadComplete(SoundPool pool, int sampleId, int status) {
                    synchronized (SoundEffects.this) {
                        if (pool != soundPool) {
                            return;
                        }
                        if (status == 0) {
                            loadedSamples.add(sampleId);
                        } else {
                            Log.w(TAG, "Sound sample failed to load: " + status);
                        }
                    }
                }
            });
            for (int index = 0; index < RESOURCES.length; index++) {
                samples[index] = soundPool.load(context.getApplicationContext(), RESOURCES[index], 1);
                if (samples[index] == 0) {
                    Log.w(TAG, "Unable to queue sound resource: " + RESOURCES[index]);
                }
            }
        } catch (RuntimeException exception) {
            Log.w(TAG, "Sound effects unavailable", exception);
            release();
        }
    }

    /** 切换音效；关闭时立即停止正在播放的所有提示音。 */
    public synchronized void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            stopStreams();
        }
    }

    /** 播放一个事件，移动声音限频以避免遥控器长按时叠音。 */
    public synchronized void play(int event) {
        if (!enabled || paused || soundPool == null || event < 0 || event >= samples.length
                || !loadedSamples.contains(samples[event])) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (event == MOVE) {
            if (now - lastMoveMillis < MOVE_INTERVAL_MILLIS) {
                return;
            }
            lastMoveMillis = now;
        }
        try {
            float volume = VOLUMES[event];
            int priority = event == WIN || event == LOSE ? 2 : 1;
            if (playingStreams[event] != 0) {
                soundPool.stop(playingStreams[event]);
            }
            int stream = soundPool.play(samples[event], volume, volume, priority, 0, 1.0f);
            playingStreams[event] = stream;
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to play sound effect", exception);
        }
    }

    /** 进入后台时暂停播放器并清除当前提示音，防止恢复时重播旧提示。 */
    public synchronized void pause() {
        paused = true;
        if (soundPool != null) {
            try {
                soundPool.autoPause();
            } catch (RuntimeException exception) {
                Log.w(TAG, "Unable to pause sound effects", exception);
            }
            stopStreams();
        }
    }

    /** 返回前台时恢复接受新的声音事件，不重播已过时的提示。 */
    public synchronized void resume() {
        paused = false;
    }

    /** 停止全部音效并释放播放器；Activity 销毁时调用，重复调用安全。 */
    public synchronized void release() {
        SoundPool pool = soundPool;
        soundPool = null;
        loadedSamples.clear();
        for (int index = 0; index < playingStreams.length; index++) {
            playingStreams[index] = 0;
        }
        if (pool != null) {
            try {
                pool.setOnLoadCompleteListener(null);
            } catch (RuntimeException exception) {
                Log.w(TAG, "Unable to detach sound listener", exception);
            }
            try {
                pool.release();
            } catch (RuntimeException exception) {
                Log.w(TAG, "Unable to release sound effects", exception);
            }
        }
    }

    private void stopStreams() {
        if (soundPool == null) {
            return;
        }
        for (int index = 0; index < playingStreams.length; index++) {
            if (playingStreams[index] != 0) {
                try {
                    soundPool.stop(playingStreams[index]);
                } catch (RuntimeException exception) {
                    Log.w(TAG, "Unable to stop sound effect", exception);
                }
                playingStreams[index] = 0;
            }
        }
    }
}
