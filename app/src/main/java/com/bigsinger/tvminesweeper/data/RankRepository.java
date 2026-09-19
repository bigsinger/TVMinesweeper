package com.bigsinger.tvminesweeper.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 保存本机真实通关成绩；首次读取可由调用方放在后台执行。 */
public final class RankRepository {
    private static final String TAG = "TVMinesweeper";
    private static final String PREFERENCES = "minesweeper_ranks";
    private static final String KEY_ENTRIES = "entries_v1";
    private static final int MAX_ENTRIES = 8;
    private static final int MAX_STORED_LENGTH = 32768;
    private static final int[] SAFE_CELLS = {71, 216, 381};
    private static final int[] DIFFICULTY_WEIGHTS = {1, 3, 8};
    private static final int SCORE_BASE = 10000;
    private static final Comparator<Entry> RANK_ORDER = new Comparator<Entry>() {
        @Override
        public int compare(Entry first, Entry second) {
            if (first.score != second.score) {
                return first.score > second.score ? -1 : 1;
            }
            if (first.seconds != second.seconds) {
                return first.seconds < second.seconds ? -1 : 1;
            }
            if (first.date != second.date) {
                return first.date > second.date ? -1 : 1;
            }
            return first.id.compareTo(second.id);
        }
    };

    private final SharedPreferences preferences;

    /** 一条不可变的通关成绩，日期使用 Unix 时间戳（毫秒）。 */
    public static final class Entry {
        /** 通关记录的唯一标识，用于界面高亮。 */
        public final String id;
        /** 按难度及用时计算的积分。 */
        public final int score;
        /** 初级、中级、高级分别为 0、1、2。 */
        public final int difficulty;
        /** 实际游戏用时，不包含暂停时段。 */
        public final long seconds;
        /** 通关日期的毫秒时间戳。 */
        public final long date;

        private Entry(String id, int score, int difficulty, long seconds, long date) {
            this.id = id;
            this.score = score;
            this.difficulty = difficulty;
            this.seconds = seconds;
            this.date = date;
        }
    }

    /** 使用应用私有 SharedPreferences 保存成绩，不申请任何权限。 */
    public RankRepository(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    /** 返回按分数排序的前八条成绩快照；损坏的条目不会显示在排行榜上。 */
    public synchronized List<Entry> getEntries() {
        return Collections.unmodifiableList(readEntries());
    }

    /**
     * 在胜利状态转换时调用一次，保存前八名并返回本次记录 ID。
     * safeCells 必须是本局已经翻开的安全格数量；未完全通关返回 null。
     * 未进入前八名时仍返回 ID，界面仅高亮实际存在于返回列表中的记录。
     */
    public synchronized String addWin(int difficulty, long seconds, int safeCells) {
        if (!isValidWin(difficulty, seconds, safeCells)) {
            Log.w(TAG, "Ignoring invalid win result");
            return null;
        }
        String id = UUID.randomUUID().toString();
        List<Entry> entries = readEntries();
        entries.add(new Entry(id, calculateScore(difficulty, seconds, safeCells), difficulty,
                seconds, System.currentTimeMillis()));
        sortAndTrim(entries);
        try {
            JSONArray array = new JSONArray();
            for (Entry entry : entries) {
                JSONObject item = new JSONObject();
                item.put("id", entry.id);
                item.put("score", entry.score);
                item.put("difficulty", entry.difficulty);
                item.put("seconds", entry.seconds);
                item.put("date", entry.date);
                array.put(item);
            }
            preferences.edit().putString(KEY_ENTRIES, array.toString()).apply();
            return id;
        } catch (JSONException exception) {
            Log.e(TAG, "Unable to encode win result", exception);
        } catch (RuntimeException exception) {
            Log.e(TAG, "Unable to save win result", exception);
        }
        return null;
    }

    /** 沿用原型：四舍五入（难度权重 × 10000 ÷ 秒数），不足一秒按一秒计算。 */
    public static int calculateScore(int difficulty, long seconds, int safeCells) {
        if (!isValidWin(difficulty, seconds, safeCells)) {
            return 0;
        }
        return (int) Math.round(DIFFICULTY_WEIGHTS[difficulty] * (double) SCORE_BASE
                / Math.max(1L, seconds));
    }

    private List<Entry> readEntries() {
        List<Entry> entries = new ArrayList<Entry>();
        try {
            String encoded = preferences.getString(KEY_ENTRIES, "[]");
            if (encoded == null || encoded.length() > MAX_STORED_LENGTH) {
                Log.w(TAG, "Ignoring invalid leaderboard size");
                return entries;
            }
            JSONArray array = new JSONArray(encoded);
            Set<String> ids = new HashSet<String>();
            for (int index = 0; index < array.length(); index++) {
                try {
                    JSONObject item = array.getJSONObject(index);
                    String id = item.getString("id");
                    int difficulty = item.getInt("difficulty");
                    long seconds = item.getLong("seconds");
                    long date = item.getLong("date");
                    int score = item.getInt("score");
                    if (id.length() == 0 || id.length() > 100 || difficulty < 0
                            || difficulty >= SAFE_CELLS.length || seconds < 0L || date <= 0L
                            || score != calculateScore(difficulty, seconds, SAFE_CELLS[difficulty])) {
                        Log.w(TAG, "Ignoring invalid leaderboard entry");
                        continue;
                    }
                    if (ids.add(id)) {
                        entries.add(new Entry(id, score, difficulty, seconds, date));
                    }
                } catch (JSONException exception) {
                    Log.w(TAG, "Ignoring damaged leaderboard entry", exception);
                }
            }
        } catch (JSONException exception) {
            Log.w(TAG, "Unable to decode leaderboard", exception);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to read leaderboard", exception);
        }
        sortAndTrim(entries);
        return entries;
    }

    private static boolean isValidWin(int difficulty, long seconds, int safeCells) {
        return difficulty >= 0 && difficulty < SAFE_CELLS.length && seconds >= 0L
                && safeCells == SAFE_CELLS[difficulty];
    }

    private static void sortAndTrim(List<Entry> entries) {
        Collections.sort(entries, RANK_ORDER);
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.size() - 1);
        }
    }
}
