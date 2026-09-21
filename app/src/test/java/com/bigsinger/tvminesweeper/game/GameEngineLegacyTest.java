package com.bigsinger.tvminesweeper.game;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 五档新局及已发布三档存档的跨版本兼容测试。 */
public class GameEngineLegacyTest {
    private static final String[] OLD_NAMES = {"BEGINNER", "INTERMEDIATE", "EXPERT"};
    private static final GameEngine.Difficulty[] MAPPED = {GameEngine.Difficulty.BEGINNER,
            GameEngine.Difficulty.ADVANCED, GameEngine.Difficulty.CHALLENGE};
    private static final String[] LAYOUTS = {"CURRENT", "LEGACY_INTERMEDIATE", "LEGACY_EXPERT"};
    private static final int[] OLD_ROWS = {9, 16, 16};
    private static final int[] OLD_COLS = {9, 16, 30};
    private static final int[] FIXED_MINES = {10, 40, 99};
    private static final int[] OLD_MIN_MINES = {10, 40, 90};
    private static final int[] OLD_MAX_MINES = {15, 50, 110};

    @Test
    public void fiveNewDifficultiesUseTheirCanonicalDimensionsAndRanges() {
        int[][] expected = {{9, 9, 10, 15}, {10, 12, 18, 24}, {13, 16, 34, 44},
                {16, 20, 58, 72}, {19, 24, 90, 108}};
        GameEngine.Difficulty[] difficulties = GameEngine.Difficulty.values();
        assertEquals(5, difficulties.length);
        for (int index = 0; index < difficulties.length; index++) {
            GameEngine.Difficulty difficulty = difficulties[index];
            assertEquals(expected[index][0], difficulty.rows);
            assertEquals(expected[index][1], difficulty.cols);
            assertEquals(expected[index][2], difficulty.minMines);
            assertEquals(expected[index][3], difficulty.maxMines);
            GameEngine game = new GameEngine(difficulty, new Random(42));
            assertEquals(difficulty.rows, game.getRows());
            assertEquals(difficulty.cols, game.getCols());
            assertEquals("CURRENT", game.serialize().split("\\|", -1)[7]);
        }
    }

    @Test
    public void everyOldFixedAndRandomReadySaveKeepsDimensionsFlagsAndChosenCount() {
        for (int level = 0; level < OLD_NAMES.length; level++) {
            for (int version = 1; version <= 2; version++) {
                int[] counts = version == 1 ? new int[]{FIXED_MINES[level]}
                        : new int[]{OLD_MIN_MINES[level], OLD_MAX_MINES[level]};
                for (int count : counts) {
                    GameEngine restored = GameEngine.restore(oldSave(level, version, count, true));
                    assertLegacyMetadata(restored, level, count);
                    assertEquals(GameEngine.State.READY, restored.getState());
                    assertEquals(0, restored.getOpenedCount());
                    assertEquals(2, restored.getFlagCount());
                    assertTrue(restored.getCell(0, 0).isFlagged());
                    assertTrue(restored.getCell(OLD_ROWS[level] - 1, OLD_COLS[level] - 1).isFlagged());
                    for (int attempt = 0; attempt < 3; attempt++) {
                        String saved = restored.serialize();
                        restored = GameEngine.restore(saved);
                        assertLegacyMetadata(restored, level, count);
                        assertEquals(saved, restored.serialize());
                    }
                    restored.openCell(OLD_ROWS[level] / 2, OLD_COLS[level] / 2);
                    assertEquals(count, countMines(restored));
                    assertEquals(0, restored.getCell(OLD_ROWS[level] / 2,
                            OLD_COLS[level] / 2).getAdjacentMines());
                    assertNotNull(GameEngine.restore(restored.serialize()));
                }
            }
        }
    }

    @Test
    public void oldPlayingLayoutsSurviveMigrationResaveAndContinueToWin() {
        for (int level = 0; level < OLD_NAMES.length; level++) {
            for (int version = 1; version <= 2; version++) {
                int[] counts = version == 1 ? new int[]{FIXED_MINES[level]}
                        : new int[]{OLD_MIN_MINES[level], OLD_MAX_MINES[level]};
                for (int count : counts) {
                    String old = oldSave(level, version, count, false);
                    GameEngine restored = GameEngine.restore(old);
                    assertLegacyMetadata(restored, level, count);
                    assertEquals(GameEngine.State.PLAYING, restored.getState());
                    assertEquals(OLD_ROWS[level] * OLD_COLS[level] - count - 1,
                            restored.getOpenedCount());
                    assertEquals(2, restored.getFlagCount());
                    String[] oldParts = old.split("\\|", -1);
                    assertEquals(oldParts[oldParts.length - 1], restored.serialize().split("\\|", -1)[8]);
                    assertEquals(count, countMines(restored));
                    String migrated = restored.serialize();
                    restored = GameEngine.restore(migrated);
                    assertLegacyMetadata(restored, level, count);
                    assertEquals(migrated, restored.serialize());
                    assertTrue(restored.toggleFlag(OLD_ROWS[level] - 1, OLD_COLS[level] - 1));
                    assertEquals(1, restored.openCell(OLD_ROWS[level] - 1, OLD_COLS[level] - 1));
                    assertEquals(GameEngine.State.WON, restored.getState());
                    assertEquals(count, restored.getFlagCount());
                    assertEquals(restored.serialize(), GameEngine.restore(restored.serialize()).serialize());
                    GameEngine newGame = new GameEngine(restored.getDifficulty());
                    assertEquals(MAPPED[level].rows, newGame.getRows());
                    assertEquals(MAPPED[level].cols, newGame.getCols());
                    assertEquals("CURRENT", newGame.serialize().split("\\|", -1)[7]);
                }
            }
        }
    }

    @Test
    public void migratedOldBoardCanLoseWithoutChangingLayoutOnNextRestore() {
        for (int level = 0; level < OLD_NAMES.length; level++) {
            GameEngine restored = GameEngine.restore(oldSave(level, 2, OLD_MAX_MINES[level], false));
            assertNotNull(restored);
            assertFalse(restored.getCell(0, 1).isFlagged());
            restored.openCell(0, 1);
            assertEquals(GameEngine.State.LOST, restored.getState());
            assertTrue(restored.getCell(0, 1).isExploded());
            String saved = restored.serialize();
            restored = GameEngine.restore(saved);
            assertNotNull(restored);
            assertEquals(saved, restored.serialize());
        }
    }

    @Test
    public void onlyKnownLayoutDifficultyPairsAndCorrectPayloadDimensionsRestore() {
        for (int level = 0; level < OLD_NAMES.length; level++) {
            GameEngine restored = GameEngine.restore(oldSave(level, 2, OLD_MIN_MINES[level], false));
            assertNotNull(restored);
            String saved = restored.serialize();
            assertNull(GameEngine.restore(replacePart(saved, 7, "CUSTOM_30_30")));
            assertNull(GameEngine.restore(replacePart(saved, 7, "16,30")));
            assertNull(GameEngine.restore(replacePart(saved, 7, "")));
            for (String layout : LAYOUTS) {
                if (!layout.equals(LAYOUTS[level])) {
                    assertNull(GameEngine.restore(replacePart(saved, 7, layout)));
                }
            }
            for (GameEngine.Difficulty difficulty : GameEngine.Difficulty.values()) {
                if (difficulty != MAPPED[level]) {
                    assertNull(GameEngine.restore(replacePart(saved, 1, difficulty.name())));
                }
            }
            assertNull(GameEngine.restore(replacePart(saved, 1, "EXPERT")));
            assertNull(GameEngine.restore(replacePart(saved, 8, saved.split("\\|", -1)[8] + "0")));
        }
    }

    @Test
    public void oldVersionsRejectNewNamesWrongDimensionsAndOutOfRangeCounts() {
        for (int level = 0; level < OLD_NAMES.length; level++) {
            String ready = oldSave(level, 2, OLD_MIN_MINES[level], true);
            assertNull(GameEngine.restore(replacePart(ready, 6, String.valueOf(OLD_MIN_MINES[level] - 1))));
            assertNull(GameEngine.restore(replacePart(ready, 6, String.valueOf(OLD_MAX_MINES[level] + 1))));
            assertNull(GameEngine.restore(replacePart(ready, 1, "ADVANCED")));
            assertNull(GameEngine.restore(replacePart(ready, 1, "HARD")));
            assertNull(GameEngine.restore(replacePart(ready, 1, "CHALLENGE")));
            String playing = oldSave(level, 2, OLD_MIN_MINES[level], false);
            assertNull(GameEngine.restore(replacePart(playing, 6, String.valueOf(OLD_MIN_MINES[level] + 1))));
            assertNull(GameEngine.restore(replacePart(playing, 7, "000000000")));
        }
    }

    private static void assertLegacyMetadata(GameEngine game, int level, int count) {
        assertNotNull(game);
        assertEquals(MAPPED[level], game.getDifficulty());
        assertEquals(OLD_ROWS[level], game.getRows());
        assertEquals(OLD_COLS[level], game.getCols());
        assertEquals(count, game.getMineCount());
        assertEquals(LAYOUTS[level], game.serialize().split("\\|", -1)[7]);
        assertEquals("TVM3", game.serialize().split("\\|", -1)[0]);
    }

    /** 独立构造可达旧局：地雷在顶部，最后一格误插旗，其余安全格均已翻开。 */
    private static String oldSave(int level, int version, int mines, boolean ready) {
        int total = OLD_ROWS[level] * OLD_COLS[level];
        StringBuilder payload = new StringBuilder(total);
        for (int index = 0; index < total; index++) {
            int value = ready ? 0 : index < mines ? 1 : 2;
            if (index == 0 || index == total - 1) {
                value = (value & ~2) | 4;
            }
            payload.append(Character.forDigit(value, 16));
        }
        return "TVM" + version + "|" + OLD_NAMES[level] + "|" + (ready ? "READY" : "PLAYING")
                + "|2|" + (ready ? 0 : total - mines - 1) + "|" + (ready ? -1 : total - 2)
                + "|" + (version == 2 ? mines + "|" : "") + payload;
    }

    private static int countMines(GameEngine game) {
        int result = 0;
        for (int row = 0; row < game.getRows(); row++) {
            for (int col = 0; col < game.getCols(); col++) {
                result += game.getCell(row, col).isMine() ? 1 : 0;
            }
        }
        return result;
    }

    private static String replacePart(String saved, int index, String value) {
        String[] parts = saved.split("\\|", -1);
        parts[index] = value;
        StringBuilder result = new StringBuilder();
        for (int part = 0; part < parts.length; part++) {
            if (part != 0) {
                result.append('|');
            }
            result.append(parts[part]);
        }
        return result.toString();
    }
}
