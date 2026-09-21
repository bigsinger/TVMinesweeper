package com.bigsinger.tvminesweeper.data;

import com.bigsinger.tvminesweeper.game.GameEngine;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 随机雷数只改变安全格校验，通关积分继续沿用原公式。 */
public class RankRepositoryTest {
    @Test
    public void everySupportedMineCountCanProduceAValidScore() {
        int[] expectedScores = {100, 200, 300, 500, 800};
        for (GameEngine.Difficulty difficulty : GameEngine.Difficulty.values()) {
            for (int mines = difficulty.minMines; mines <= difficulty.maxMines; mines++) {
                assertEquals(expectedScores[difficulty.ordinal()], RankRepository.calculateScore(
                        difficulty.ordinal(), 100, difficulty.rows * difficulty.cols - mines));
            }
        }
    }

    @Test
    public void invalidCountsDifficultiesAndTimesAreRejected() {
        for (GameEngine.Difficulty difficulty : GameEngine.Difficulty.values()) {
            int total = difficulty.rows * difficulty.cols;
            assertEquals(0, RankRepository.calculateScore(difficulty.ordinal(), 1,
                    total - difficulty.maxMines - 1));
            assertEquals(0, RankRepository.calculateScore(difficulty.ordinal(), 1,
                    total - difficulty.minMines + 1));
            assertEquals(0, RankRepository.calculateScore(difficulty.ordinal(), -1,
                    total - difficulty.minMines));
        }
        assertEquals(0, RankRepository.calculateScore(-1, 1, 71));
        assertEquals(0, RankRepository.calculateScore(5, 1, 71));
    }

    @Test
    public void legacyWinsAndRoundingKeepOriginalScores() {
        assertEquals(10000, RankRepository.calculateScore(0, 0, 71));
        assertEquals(10000, RankRepository.calculateScore(0, 1, 71));
        assertEquals(3333, RankRepository.calculateScore(0, 3, 71));
        assertEquals(300, RankRepository.calculateScore(2, 100, 216));
        assertEquals(800, RankRepository.calculateScore(4, 100, 381));
        assertEquals(20000, RankRepository.calculateScore(1, 0, 102));
        assertEquals(0, RankRepository.calculateScore(4, Long.MAX_VALUE, 366));
    }

    @Test
    public void legacyLayoutsRemainEligibleWithoutAcceptingGapsBetweenLayouts() {
        for (int safeCells = 206; safeCells <= 216; safeCells++) {
            assertEquals(300, RankRepository.calculateScore(2, 100, safeCells));
        }
        for (int safeCells = 370; safeCells <= 390; safeCells++) {
            assertEquals(800, RankRepository.calculateScore(4, 100, safeCells));
        }
        assertEquals(0, RankRepository.calculateScore(2, 100, 205));
        assertEquals(0, RankRepository.calculateScore(2, 100, 217));
        assertEquals(0, RankRepository.calculateScore(4, 100, 369));
        assertEquals(0, RankRepository.calculateScore(4, 100, 391));
        assertEquals(0, RankRepository.calculateScore(1, 100, 216));
        assertEquals(0, RankRepository.calculateScore(2, 100, 381));
    }

    @Test
    public void legacyLeaderboardMigratesOnceWithoutChangingScoresOrMetadata() {
        MemoryPreferences preferences = legacyPreferences();
        List<RankRepository.Entry> migrated = new RankRepository(preferences).getEntries();
        assertEquals(3, migrated.size());
        assertEntry(migrated.get(0), "old-expert", 800, 4, 100, 3000);
        assertEntry(migrated.get(1), "old-medium", 300, 2, 100, 2000);
        assertEntry(migrated.get(2), "old-easy", 100, 0, 100, 1000);
        assertTrue(preferences.contains("entries_v2"));
        assertFalse(preferences.contains("entries_v1"));
        assertEquals(1, preferences.applyCount);
        assertEquals(0, preferences.commitCount);

        List<RankRepository.Entry> reopened = new RankRepository(preferences).getEntries();
        assertEntry(reopened.get(1), "old-medium", 300, 2, 100, 2000);
        assertEquals(1, preferences.applyCount);
    }

    @Test
    public void newIntermediateRecordKeepsItsMeaningAfterRestartAndStaleLegacyData() {
        MemoryPreferences preferences = legacyPreferences();
        RankRepository repository = new RankRepository(preferences);
        String id = repository.addWin(1, 50, 102);
        assertNotNull(id);
        // 旧键即使再次出现，也不能覆盖已经写入的新格式或重复映射新中级。
        preferences.values.put("entries_v1", legacyJson());
        List<RankRepository.Entry> entries = new RankRepository(preferences).getEntries();
        assertEquals(4, entries.size());
        assertEntry(entries.get(1), id, 400, 1, 50, entries.get(1).date);
        assertEntry(entries.get(2), "old-medium", 300, 2, 100, 2000);
    }

    @Test
    public void legacyActiveGamesCanAddWinsAlongsideAllNewDifficulties() {
        MemoryPreferences preferences = new MemoryPreferences();
        RankRepository repository = new RankRepository(preferences);
        assertNotNull(repository.addWin(2, 100, 206));
        assertNotNull(repository.addWin(4, 100, 390));
        assertNotNull(repository.addWin(1, 100, 96));
        assertNotNull(repository.addWin(3, 100, 248));
        assertNull(repository.addWin(2, 100, 205));
        assertEquals(4, new RankRepository(preferences).getEntries().size());
    }

    @Test
    public void damagedLegacyRowsDoNotDiscardValidRowsOrInventNewDifficulty() {
        MemoryPreferences preferences = new MemoryPreferences();
        preferences.values.put("entries_v1", "[null,{\"id\":\"valid\",\"score\":300,"
                + "\"difficulty\":1,\"seconds\":100,\"date\":2000},"
                + "{\"id\":\"unknown-old-difficulty\",\"score\":500,\"difficulty\":3,"
                + "\"seconds\":100,\"date\":3000},{\"id\":\"damaged-score\",\"score\":999,"
                + "\"difficulty\":2,\"seconds\":100,\"date\":4000}]");
        List<RankRepository.Entry> entries = new RankRepository(preferences).getEntries();
        assertEquals(1, entries.size());
        assertEntry(entries.get(0), "valid", 300, 2, 100, 2000);
        assertFalse(preferences.contains("entries_v1"));
        assertEquals(1, new RankRepository(preferences).getEntries().size());
    }

    @Test
    public void malformedCurrentDataNeverFallsBackToLegacyOrdinals() {
        MemoryPreferences preferences = legacyPreferences();
        preferences.values.put("entries_v2", true);
        assertTrue(new RankRepository(preferences).getEntries().isEmpty());
        preferences.values.put("entries_v2", "not-json");
        RankRepository repository = new RankRepository(preferences);
        assertTrue(repository.getEntries().isEmpty());
        assertNotNull(repository.addWin(1, 100, 102));
        List<RankRepository.Entry> entries = new RankRepository(preferences).getEntries();
        assertEquals(1, entries.size());
        assertEquals(1, entries.get(0).difficulty);
    }

    @Test
    public void interruptedMigrationCanRetryWithoutChangingAnyLegacyRecord() {
        MemoryPreferences preferences = legacyPreferences();
        preferences.failNextApply = true;
        List<RankRepository.Entry> firstRead = new RankRepository(preferences).getEntries();
        assertEquals(3, firstRead.size());
        assertTrue(preferences.contains("entries_v1"));
        assertFalse(preferences.contains("entries_v2"));
        List<RankRepository.Entry> retried = new RankRepository(preferences).getEntries();
        assertEntry(retried.get(1), "old-medium", 300, 2, 100, 2000);
        assertFalse(preferences.contains("entries_v1"));
    }

    private static MemoryPreferences legacyPreferences() {
        MemoryPreferences preferences = new MemoryPreferences();
        preferences.values.put("entries_v1", legacyJson());
        return preferences;
    }

    private static String legacyJson() {
        return "[{\"id\":\"old-easy\",\"score\":100,\"difficulty\":0,\"seconds\":100,\"date\":1000},"
                + "{\"id\":\"old-medium\",\"score\":300,\"difficulty\":1,\"seconds\":100,\"date\":2000},"
                + "{\"id\":\"old-expert\",\"score\":800,\"difficulty\":2,\"seconds\":100,\"date\":3000}]";
    }

    private static void assertEntry(RankRepository.Entry entry, String id, int score, int difficulty,
            long seconds, long date) {
        assertEquals(id, entry.id);
        assertEquals(score, entry.score);
        assertEquals(difficulty, entry.difficulty);
        assertEquals(seconds, entry.seconds);
        assertEquals(date, entry.date);
    }
}
