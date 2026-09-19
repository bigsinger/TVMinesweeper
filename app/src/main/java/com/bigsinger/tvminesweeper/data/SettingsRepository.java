package com.bigsinger.tvminesweeper.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

/** 保存难度、音效以及可恢复的游戏快照；首次读取可放在后台执行。 */
public final class SettingsRepository {
    private static final String TAG = "TVMinesweeper";
    private static final String PREFERENCES = "minesweeper_settings";
    private static final String KEY_DIFFICULTY = "difficulty";
    private static final String KEY_SOUND = "sound_enabled";
    private static final String KEY_GAME = "saved_game_v1";
    private static final int SAVE_VERSION = 1;
    private static final int DEFAULT_DIFFICULTY = 0;
    private static final int MAX_DIFFICULTY = 2;
    private static final int MAX_STATE_LENGTH = 65536;
    private static final int MAX_ROWS = 16;
    private static final int MAX_COLUMNS = 30;

    private final SharedPreferences preferences;

    /** 不可变的恢复快照，棋盘内容由 GameEngine 自行验证。 */
    public static final class SavedGame {
        /** 引擎序列化的棋盘状态。 */
        public final String engineState;
        /** 累计游戏时长（毫秒），不包含应用暂停时间。 */
        public final long elapsedMillis;
        /** 暂停前的光标行。 */
        public final int row;
        /** 暂停前的光标列。 */
        public final int col;

        private SavedGame(String engineState, long elapsedMillis, int row, int col) {
            this.engineState = engineState;
            this.elapsedMillis = elapsedMillis;
            this.row = row;
            this.col = col;
        }
    }

    /** 使用应用私有 SharedPreferences 保存设置及进度。 */
    public SettingsRepository(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    /** 返回上次选择的难度；缺失或损坏时使用初级。 */
    public int getDifficulty() {
        try {
            int difficulty = preferences.getInt(KEY_DIFFICULTY, DEFAULT_DIFFICULTY);
            if (difficulty >= DEFAULT_DIFFICULTY && difficulty <= MAX_DIFFICULTY) {
                return difficulty;
            }
            Log.w(TAG, "Ignoring invalid difficulty setting");
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to read difficulty setting", exception);
        }
        return DEFAULT_DIFFICULTY;
    }

    /** 保存难度；超出 0 至 2 的值会回退为初级。 */
    public void setDifficulty(int difficulty) {
        int value = difficulty;
        if (value < DEFAULT_DIFFICULTY || value > MAX_DIFFICULTY) {
            Log.w(TAG, "Resetting invalid difficulty setting");
            value = DEFAULT_DIFFICULTY;
        }
        try {
            preferences.edit().putInt(KEY_DIFFICULTY, value).apply();
        } catch (RuntimeException exception) {
            Log.e(TAG, "Unable to save difficulty setting", exception);
        }
    }

    /** 返回音效开关，首次运行默认开启。 */
    public boolean isSoundEnabled() {
        try {
            return preferences.getBoolean(KEY_SOUND, true);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to read sound setting", exception);
            return true;
        }
    }

    /** 保存音效开关。 */
    public void setSoundEnabled(boolean enabled) {
        try {
            preferences.edit().putBoolean(KEY_SOUND, enabled).apply();
        } catch (RuntimeException exception) {
            Log.e(TAG, "Unable to save sound setting", exception);
        }
    }

    /** 将棋盘、计时与光标作为同一快照保存，避免恢复时出现不同步。 */
    public synchronized void saveGame(String engineState, long elapsedMillis, int row, int col) {
        if (engineState == null || engineState.length() == 0 || engineState.length() > MAX_STATE_LENGTH
                || elapsedMillis < 0L || row < 0 || row >= MAX_ROWS || col < 0 || col >= MAX_COLUMNS) {
            Log.w(TAG, "Ignoring invalid game snapshot");
            return;
        }
        try {
            JSONObject saved = new JSONObject();
            saved.put("version", SAVE_VERSION);
            saved.put("engineState", engineState);
            saved.put("elapsedMillis", elapsedMillis);
            saved.put("row", row);
            saved.put("col", col);
            preferences.edit().putString(KEY_GAME, saved.toString()).apply();
        } catch (JSONException exception) {
            Log.e(TAG, "Unable to encode game snapshot", exception);
        } catch (RuntimeException exception) {
            Log.e(TAG, "Unable to save game snapshot", exception);
        }
    }

    /** 读取最近快照；不存在或损坏时返回 null，不影响新游戏。 */
    public synchronized SavedGame loadGame() {
        try {
            String encoded = preferences.getString(KEY_GAME, null);
            if (encoded == null) {
                return null;
            }
            if (encoded.length() > MAX_STATE_LENGTH * 2) {
                Log.w(TAG, "Ignoring oversized game snapshot");
                clearGame();
                return null;
            }
            JSONObject saved = new JSONObject(encoded);
            String engineState = saved.getString("engineState");
            long elapsedMillis = saved.getLong("elapsedMillis");
            int row = saved.getInt("row");
            int col = saved.getInt("col");
            if (saved.getInt("version") != SAVE_VERSION || engineState.length() == 0
                    || engineState.length() > MAX_STATE_LENGTH || elapsedMillis < 0L
                    || row < 0 || row >= MAX_ROWS || col < 0 || col >= MAX_COLUMNS) {
                Log.w(TAG, "Ignoring invalid game snapshot");
                clearGame();
                return null;
            }
            return new SavedGame(engineState, elapsedMillis, row, col);
        } catch (JSONException exception) {
            Log.w(TAG, "Unable to decode game snapshot", exception);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to read game snapshot", exception);
        }
        clearGame();
        return null;
    }

    /** 移除已结束或已放弃的棋盘快照，保留难度、音效和排行榜。 */
    public synchronized void clearGame() {
        try {
            preferences.edit().remove(KEY_GAME).apply();
        } catch (RuntimeException exception) {
            Log.e(TAG, "Unable to clear game snapshot", exception);
        }
    }
}
