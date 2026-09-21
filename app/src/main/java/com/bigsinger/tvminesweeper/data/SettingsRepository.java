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
    private static final String KEY_LEGACY_DIFFICULTY = "difficulty";
    private static final String KEY_DIFFICULTY = "difficulty_v2";
    private static final String KEY_SOUND = "sound_enabled";
    private static final String KEY_GAME = "saved_game_v1";
    private static final int SAVE_VERSION = 1;
    private static final int DEFAULT_DIFFICULTY = 0;
    private static final String[] DIFFICULTY_IDS = {"beginner", "intermediate", "advanced", "hard", "challenge"};
    private static final int[] LEGACY_DIFFICULTIES = {0, 2, 4};
    private static final int MAX_STATE_LENGTH = 65536;
    private static final int MAX_ROWS = 19;
    private static final int MAX_CURRENT_COLUMNS = 24;
    private static final int MAX_LEGACY_ROWS = 16;
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
        this(context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE));
    }

    SettingsRepository(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    /** 返回五档难度下标；旧三档设置仅迁移一次，缺失或损坏时使用初级。 */
    public synchronized int getDifficulty() {
        try {
            if (preferences.contains(KEY_DIFFICULTY)) {
                String id = preferences.getString(KEY_DIFFICULTY, "");
                for (int index = 0; index < DIFFICULTY_IDS.length; index++) {
                    if (DIFFICULTY_IDS[index].equals(id)) {
                        return index;
                    }
                }
                Log.w(TAG, "Ignoring invalid difficulty identifier");
                return DEFAULT_DIFFICULTY;
            }
            int difficulty = migrateLegacyDifficulty(
                    preferences.getInt(KEY_LEGACY_DIFFICULTY, DEFAULT_DIFFICULTY));
            if (difficulty < 0) {
                Log.w(TAG, "Ignoring invalid legacy difficulty setting");
                difficulty = DEFAULT_DIFFICULTY;
            }
            setDifficulty(difficulty);
            return difficulty;
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to read difficulty setting", exception);
        }
        return DEFAULT_DIFFICULTY;
    }

    /** 用固定标识保存难度；超出 0 至 4 的值会回退为初级。 */
    public synchronized void setDifficulty(int difficulty) {
        int value = difficulty;
        if (value < DEFAULT_DIFFICULTY || value >= DIFFICULTY_IDS.length) {
            Log.w(TAG, "Resetting invalid difficulty setting");
            value = DEFAULT_DIFFICULTY;
        }
        try {
            preferences.edit().putString(KEY_DIFFICULTY, DIFFICULTY_IDS[value])
                    .remove(KEY_LEGACY_DIFFICULTY).apply();
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
                || elapsedMillis < 0L || !isValidCursor(row, col)) {
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
                    || !isValidCursor(row, col)) {
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

    static int migrateLegacyDifficulty(int legacyDifficulty) {
        return legacyDifficulty >= 0 && legacyDifficulty < LEGACY_DIFFICULTIES.length
                ? LEGACY_DIFFICULTIES[legacyDifficulty] : -1;
    }

    private static boolean isValidCursor(int row, int col) {
        // 外层快照接受新棋盘或保留的旧棋盘；精确尺寸仍由恢复后的引擎约束。
        return row >= 0 && col >= 0 && ((row < MAX_ROWS && col < MAX_CURRENT_COLUMNS)
                || (row < MAX_LEGACY_ROWS && col < MAX_COLUMNS));
    }
}
