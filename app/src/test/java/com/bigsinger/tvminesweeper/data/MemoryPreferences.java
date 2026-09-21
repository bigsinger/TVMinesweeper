package com.bigsinger.tvminesweeper.data;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 本地迁移测试使用的内存偏好存储，保留类型错误与原子编辑语义。 */
final class MemoryPreferences implements SharedPreferences {
    final Map<String, Object> values = new HashMap<String, Object>();
    int applyCount;
    int commitCount;
    boolean failNextApply;

    @Override public Map<String, ?> getAll() { return new HashMap<String, Object>(values); }
    @Override public String getString(String key, String fallback) {
        return values.containsKey(key) ? (String) values.get(key) : fallback;
    }
    @SuppressWarnings("unchecked")
    @Override public Set<String> getStringSet(String key, Set<String> fallback) {
        return values.containsKey(key) ? new HashSet<String>((Set<String>) values.get(key)) : fallback;
    }
    @Override public int getInt(String key, int fallback) {
        return values.containsKey(key) ? (Integer) values.get(key) : fallback;
    }
    @Override public long getLong(String key, long fallback) {
        return values.containsKey(key) ? (Long) values.get(key) : fallback;
    }
    @Override public float getFloat(String key, float fallback) {
        return values.containsKey(key) ? (Float) values.get(key) : fallback;
    }
    @Override public boolean getBoolean(String key, boolean fallback) {
        return values.containsKey(key) ? (Boolean) values.get(key) : fallback;
    }
    @Override public boolean contains(String key) { return values.containsKey(key); }
    @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
    @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
    @Override public Editor edit() { return new MemoryEditor(); }

    private final class MemoryEditor implements Editor {
        private final Map<String, Object> changes = new HashMap<String, Object>();
        private boolean clear;

        @Override public Editor putString(String key, String value) { changes.put(key, value); return this; }
        @Override public Editor putStringSet(String key, Set<String> value) {
            changes.put(key, value == null ? null : new HashSet<String>(value));
            return this;
        }
        @Override public Editor putInt(String key, int value) { changes.put(key, value); return this; }
        @Override public Editor putLong(String key, long value) { changes.put(key, value); return this; }
        @Override public Editor putFloat(String key, float value) { changes.put(key, value); return this; }
        @Override public Editor putBoolean(String key, boolean value) { changes.put(key, value); return this; }
        @Override public Editor remove(String key) { changes.put(key, null); return this; }
        @Override public Editor clear() { clear = true; return this; }
        @Override public boolean commit() { commitCount++; writeChanges(); return true; }
        @Override public void apply() { applyCount++; writeChanges(); }

        private void writeChanges() {
            if (failNextApply) {
                failNextApply = false;
                throw new IllegalStateException("Simulated preference write failure");
            }
            if (clear) { values.clear(); }
            for (Map.Entry<String, Object> entry : changes.entrySet()) {
                if (entry.getValue() == null) {
                    values.remove(entry.getKey());
                } else {
                    values.put(entry.getKey(), entry.getValue());
                }
            }
        }
    }
}
