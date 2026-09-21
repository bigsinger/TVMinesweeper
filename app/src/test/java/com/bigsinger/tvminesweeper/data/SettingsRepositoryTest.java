package com.bigsinger.tvminesweeper.data;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 验证三档升级为五档后的真实存储迁移和新旧棋盘快照边界。 */
public class SettingsRepositoryTest {
    @Test
    public void eachLegacyDifficultyMovesToItsEquivalentLevelOnlyOnce() {
        int[] expectedLevels = {0, 2, 4};
        String[] expectedIds = {"beginner", "advanced", "challenge"};
        for (int oldLevel = 0; oldLevel < expectedLevels.length; oldLevel++) {
            MemoryPreferences preferences = new MemoryPreferences();
            preferences.values.put("difficulty", oldLevel);
            assertEquals(expectedLevels[oldLevel], new SettingsRepository(preferences).getDifficulty());
            assertEquals(expectedIds[oldLevel], preferences.getString("difficulty_v2", null));
            assertFalse(preferences.contains("difficulty"));
            assertEquals(1, preferences.applyCount);
            assertEquals(0, preferences.commitCount);

            // 重新创建仓库模拟再次启动；已经迁移的高级不能再被解释为旧高级。
            assertEquals(expectedLevels[oldLevel], new SettingsRepository(preferences).getDifficulty());
            assertEquals(1, preferences.applyCount);
        }
    }

    @Test
    public void newIntermediateStaysIntermediateEvenWhenAnOldKeyExists() {
        MemoryPreferences preferences = new MemoryPreferences();
        preferences.values.put("difficulty", 2);
        SettingsRepository repository = new SettingsRepository(preferences);
        repository.setDifficulty(1);
        assertEquals("intermediate", preferences.getString("difficulty_v2", null));
        assertFalse(preferences.contains("difficulty"));
        preferences.values.put("difficulty", 1);
        assertEquals(1, new SettingsRepository(preferences).getDifficulty());
        assertEquals(1, preferences.applyCount);
    }

    @Test
    public void allFiveNewSettingsSurviveRestart() {
        MemoryPreferences preferences = new MemoryPreferences();
        SettingsRepository repository = new SettingsRepository(preferences);
        for (int difficulty = 0; difficulty < 5; difficulty++) {
            repository.setDifficulty(difficulty);
            assertEquals(difficulty, new SettingsRepository(preferences).getDifficulty());
        }
    }

    @Test
    public void migrationFailureKeepsTheOriginalSettingForASafeRetry() {
        MemoryPreferences preferences = new MemoryPreferences();
        preferences.values.put("difficulty", 1);
        preferences.failNextApply = true;
        assertEquals(2, new SettingsRepository(preferences).getDifficulty());
        assertTrue(preferences.contains("difficulty"));
        assertFalse(preferences.contains("difficulty_v2"));
        assertEquals(2, new SettingsRepository(preferences).getDifficulty());
        assertEquals("advanced", preferences.getString("difficulty_v2", null));
        assertFalse(preferences.contains("difficulty"));
    }

    @Test
    public void malformedNewSettingDoesNotResurrectAnOldDifficulty() {
        MemoryPreferences preferences = new MemoryPreferences();
        preferences.values.put("difficulty", 2);
        preferences.values.put("difficulty_v2", "unknown-future-level");
        assertEquals(0, new SettingsRepository(preferences).getDifficulty());
        preferences.values.put("difficulty_v2", 1);
        assertEquals(0, new SettingsRepository(preferences).getDifficulty());
        SettingsRepository repository = new SettingsRepository(preferences);
        repository.setDifficulty(5);
        assertEquals(0, repository.getDifficulty());
        repository.setDifficulty(-1);
        assertEquals(0, repository.getDifficulty());
    }

    @Test
    public void malformedLegacySettingHasASafeDefault() {
        MemoryPreferences preferences = new MemoryPreferences();
        preferences.values.put("difficulty", 3);
        assertEquals(0, new SettingsRepository(preferences).getDifficulty());
        assertEquals("beginner", preferences.getString("difficulty_v2", null));
        preferences.values.remove("difficulty_v2");
        preferences.values.put("difficulty", "broken-type");
        assertEquals(0, new SettingsRepository(preferences).getDifficulty());
    }

    @Test
    public void newNineteenthRowAndOldThirtiethColumnBothSurviveRestart() {
        MemoryPreferences preferences = new MemoryPreferences();
        SettingsRepository repository = new SettingsRepository(preferences);
        repository.saveGame("new-board\n\"quoted\"", 123456L, 18, 23);
        SettingsRepository.SavedGame newBoard = new SettingsRepository(preferences).loadGame();
        assertNotNull(newBoard);
        assertEquals("new-board\n\"quoted\"", newBoard.engineState);
        assertEquals(123456L, newBoard.elapsedMillis);
        assertEquals(18, newBoard.row);
        assertEquals(23, newBoard.col);

        repository.saveGame("legacy-expert-board", 654321L, 15, 29);
        SettingsRepository.SavedGame oldBoard = new SettingsRepository(preferences).loadGame();
        assertNotNull(oldBoard);
        assertEquals("legacy-expert-board", oldBoard.engineState);
        assertEquals(654321L, oldBoard.elapsedMillis);
        assertEquals(15, oldBoard.row);
        assertEquals(29, oldBoard.col);
    }

    @Test
    public void invalidCursorCannotReplaceAValidGameOrExpandBeyondEitherLayout() {
        MemoryPreferences preferences = new MemoryPreferences();
        SettingsRepository repository = new SettingsRepository(preferences);
        repository.saveGame("valid", 100L, 18, 23);
        int[][] invalidCursors = {{-1, 0}, {0, -1}, {19, 0}, {0, 30}, {18, 29}, {16, 24}};
        for (int[] cursor : invalidCursors) {
            repository.saveGame("invalid", 200L, cursor[0], cursor[1]);
            SettingsRepository.SavedGame remaining = repository.loadGame();
            assertNotNull(remaining);
            assertEquals("valid", remaining.engineState);
            assertEquals(18, remaining.row);
            assertEquals(23, remaining.col);
        }
    }

    @Test
    public void damagedSnapshotIsClearedWhilePreferencesStayIntact() throws Exception {
        MemoryPreferences preferences = new MemoryPreferences();
        SettingsRepository repository = new SettingsRepository(preferences);
        repository.setDifficulty(4);
        repository.setSoundEnabled(false);
        repository.saveGame("valid", 100L, 18, 23);
        JSONObject encoded = new JSONObject(preferences.getString("saved_game_v1", null));
        encoded.put("col", 29);
        preferences.values.put("saved_game_v1", encoded.toString());
        assertNull(repository.loadGame());
        assertFalse(preferences.contains("saved_game_v1"));
        assertEquals(4, repository.getDifficulty());
        assertFalse(repository.isSoundEnabled());

        preferences.values.put("saved_game_v1", "not-json");
        assertNull(repository.loadGame());
        preferences.values.put("saved_game_v1", true);
        assertNull(repository.loadGame());
        encoded.put("col", 23).put("elapsedMillis", -1);
        preferences.values.put("saved_game_v1", encoded.toString());
        assertNull(repository.loadGame());
    }
}
