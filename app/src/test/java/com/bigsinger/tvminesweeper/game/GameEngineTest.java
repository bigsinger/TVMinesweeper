package com.bigsinger.tvminesweeper.game;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 核心规则和存档的独立 JVM 回归测试。 */
public class GameEngineTest {
    @Test
    public void newGameHasNoMinesUntilFirstOpen() {
        GameEngine game = new GameEngine(GameEngine.Difficulty.BEGINNER, new Random(1));
        assertEquals(GameEngine.State.READY, game.getState());
        assertEquals(9, game.getRows());
        assertEquals(9, game.getCols());
        assertEquals(10, game.getMineCount());
        assertEquals(0, countMines(game));
        assertTrue(game.toggleFlag(0, 0));
        assertEquals(0, game.openCell(0, 0));
        assertEquals(GameEngine.State.READY, game.getState());
        assertTrue(game.toggleFlag(0, 0));
        assertTrue(game.openCell(0, 0) > 0);
        assertEquals(10, countMines(game));
    }

    @Test
    public void everyDifficultyHasExactMineCountAndSafeFirstNeighborhood() {
        for (GameEngine.Difficulty difficulty : GameEngine.Difficulty.values()) {
            for (int seed = 0; seed < 12; seed++) {
                GameEngine game = new GameEngine(difficulty, new Random(seed));
                int row = seed % 2 == 0 ? 0 : difficulty.rows / 2;
                int col = seed % 3 == 0 ? difficulty.cols - 1 : difficulty.cols / 2;
                assertTrue(game.openCell(row, col) > 0);
                assertEquals(difficulty.mines, countMines(game));
                assertEquals(0, game.getCell(row, col).getAdjacentMines());
                for (int nearbyRow = row - 1; nearbyRow <= row + 1; nearbyRow++) {
                    for (int nearbyCol = col - 1; nearbyCol <= col + 1; nearbyCol++) {
                        Cell cell = game.getCell(nearbyRow, nearbyCol);
                        if (cell != null) {
                            assertFalse(cell.isMine());
                            assertTrue(cell.isOpened());
                        }
                    }
                }
                assertAdjacentNumbers(game);
            }
        }
    }

    @Test
    public void injectedRandomReproducesBoard() {
        GameEngine first = new GameEngine(GameEngine.Difficulty.EXPERT, new Random(1234));
        GameEngine second = new GameEngine(GameEngine.Difficulty.EXPERT, new Random(1234));
        first.openCell(8, 15);
        second.openCell(8, 15);
        assertEquals(first.serialize(), second.serialize());
    }

    @Test
    public void blankExpansionOpensExactlyConnectedZerosAndTheirBorder() {
        GameEngine game = new GameEngine(GameEngine.Difficulty.INTERMEDIATE, new Random(42));
        int opened = game.openCell(8, 8);
        boolean[][] expected = new boolean[game.getRows()][game.getCols()];
        ArrayDeque<int[]> queue = new ArrayDeque<int[]>();
        expected[8][8] = true;
        queue.add(new int[]{8, 8});
        while (!queue.isEmpty()) {
            int[] location = queue.removeFirst();
            if (game.getCell(location[0], location[1]).getAdjacentMines() != 0) {
                continue;
            }
            for (int row = location[0] - 1; row <= location[0] + 1; row++) {
                for (int col = location[1] - 1; col <= location[1] + 1; col++) {
                    if (game.getCell(row, col) != null && !expected[row][col]) {
                        expected[row][col] = true;
                        queue.add(new int[]{row, col});
                    }
                }
            }
        }
        int expectedCount = 0;
        for (int row = 0; row < game.getRows(); row++) {
            for (int col = 0; col < game.getCols(); col++) {
                assertEquals(expected[row][col], game.getCell(row, col).isOpened());
                expectedCount += expected[row][col] ? 1 : 0;
            }
        }
        assertEquals(expectedCount, opened);
        assertEquals(expectedCount, game.getOpenedCount());
    }

    @Test
    public void largeEmptyRegionExpandsWithoutRecursion() {
        GameEngine game = new GameEngine(GameEngine.Difficulty.EXPERT, new Random() {
            @Override
            public int nextInt(int bound) {
                return 0;
            }
        });
        assertTrue(game.openCell(15, 29) > 300);
        assertEquals(99, countMines(game));
    }

    @Test
    public void expansionPreservesFlagsAndBlockedSafeCellPreventsWin() {
        GameEngine game = new GameEngine(GameEngine.Difficulty.BEGINNER, new Random(7));
        assertTrue(game.toggleFlag(4, 5));
        game.openCell(4, 4);
        assertFalse(game.getCell(4, 5).isOpened());
        assertTrue(game.getCell(4, 5).isFlagged());
        openAllSafeCells(game);
        assertEquals(GameEngine.State.PLAYING, game.getState());
        assertTrue(game.toggleFlag(4, 5));
        assertEquals(1, game.openCell(4, 5));
        assertEquals(GameEngine.State.WON, game.getState());
    }

    @Test
    public void flagLimitAndUnflagMaintainCounter() {
        GameEngine game = new GameEngine(GameEngine.Difficulty.BEGINNER);
        for (int index = 0; index < game.getMineCount(); index++) {
            assertTrue(game.toggleFlag(index / 9, index % 9));
        }
        assertEquals(10, game.getFlagCount());
        assertFalse(game.toggleFlag(2, 2));
        assertTrue(game.toggleFlag(0, 0));
        assertEquals(9, game.getFlagCount());
        assertTrue(game.toggleFlag(2, 2));
        assertEquals(10, game.getFlagCount());
    }

    @Test
    public void openedCellsAndOutOfBoundsCannotBeFlaggedOrReopened() {
        GameEngine game = playingGame();
        int before = game.getOpenedCount();
        assertFalse(game.toggleFlag(4, 4));
        assertEquals(0, game.openCell(4, 4));
        assertEquals(0, game.openCell(-1, 0));
        assertEquals(0, game.chord(0, Integer.MAX_VALUE));
        assertFalse(game.toggleFlag(Integer.MIN_VALUE, 0));
        assertNull(game.getCell(9, 0));
        assertNull(game.getCell(0, -1));
        assertEquals(before, game.getOpenedCount());
    }

    @Test
    public void openingEverySafeCellWinsAndAutomaticallyFlagsMines() {
        GameEngine game = playingGame();
        openAllSafeCells(game);
        assertEquals(GameEngine.State.WON, game.getState());
        assertEquals(71, game.getOpenedCount());
        assertEquals(10, game.getFlagCount());
        for (int row = 0; row < game.getRows(); row++) {
            for (int col = 0; col < game.getCols(); col++) {
                Cell cell = game.getCell(row, col);
                assertEquals(cell.isMine(), cell.isFlagged());
                assertEquals(!cell.isMine(), cell.isOpened());
            }
        }
        assertTerminalStateIsLocked(game);
    }

    @Test
    public void openingMineLosesAndRecordsExactlyTheExplodedMine() {
        GameEngine game = playingGame();
        int[] mine = findCell(game, true, false);
        int before = game.getOpenedCount();
        assertEquals(0, game.openCell(mine[0], mine[1]));
        assertEquals(before, game.getOpenedCount());
        assertEquals(GameEngine.State.LOST, game.getState());
        assertTrue(game.getCell(mine[0], mine[1]).isExploded());
        assertTrue(game.getCell(mine[0], mine[1]).isOpened());
        assertTerminalStateIsLocked(game);
    }

    @Test
    public void chordWithCorrectFlagsOpensSafeNeighbors() {
        GameEngine game = playingGame();
        int[] target = findChordTarget(game);
        int row = target[0];
        int col = target[1];
        assertEquals(0, game.chord(row, col));
        for (int nearbyRow = row - 1; nearbyRow <= row + 1; nearbyRow++) {
            for (int nearbyCol = col - 1; nearbyCol <= col + 1; nearbyCol++) {
                Cell neighbor = game.getCell(nearbyRow, nearbyCol);
                if (neighbor != null && neighbor.isMine()) {
                    assertTrue(game.toggleFlag(nearbyRow, nearbyCol));
                }
            }
        }
        int before = game.getOpenedCount();
        int opened = game.chord(row, col);
        assertTrue(opened > 0);
        assertEquals(before + opened, game.getOpenedCount());
        assertTrue(game.getState() != GameEngine.State.LOST);
        for (int nearbyRow = row - 1; nearbyRow <= row + 1; nearbyRow++) {
            for (int nearbyCol = col - 1; nearbyCol <= col + 1; nearbyCol++) {
                Cell neighbor = game.getCell(nearbyRow, nearbyCol);
                if (neighbor != null && !neighbor.isMine()) {
                    assertTrue(neighbor.isOpened());
                }
            }
        }
    }

    @Test
    public void chordWithWrongFlagCanTriggerMine() {
        GameEngine game = playingGame();
        int[] target = findChordTarget(game);
        boolean missedMine = false;
        boolean wrongFlag = false;
        for (int row = target[0] - 1; row <= target[0] + 1; row++) {
            for (int col = target[1] - 1; col <= target[1] + 1; col++) {
                Cell cell = game.getCell(row, col);
                if (cell == null || cell.isOpened()) {
                    continue;
                }
                if (cell.isMine()) {
                    if (!missedMine) {
                        missedMine = true;
                    } else {
                        assertTrue(game.toggleFlag(row, col));
                    }
                } else if (!wrongFlag) {
                    assertTrue(game.toggleFlag(row, col));
                    wrongFlag = true;
                }
            }
        }
        assertTrue(missedMine && wrongFlag);
        assertEquals(game.getCell(target[0], target[1]).getAdjacentMines(), game.getFlagCount());
        game.chord(target[0], target[1]);
        assertEquals(GameEngine.State.LOST, game.getState());
    }

    @Test
    public void closedAndEmptyCellsCannotChord() {
        GameEngine game = playingGame();
        int[] closed = findCell(game, false, false);
        String before = game.serialize();
        assertEquals(0, game.chord(closed[0], closed[1]));
        assertEquals(0, game.chord(4, 4));
        assertEquals(before, game.serialize());
    }

    @Test
    public void saveRestoresReadyPlayingWonAndLost() {
        GameEngine ready = new GameEngine(GameEngine.Difficulty.EXPERT);
        ready.toggleFlag(0, 0);
        assertRoundTrip(ready);
        GameEngine playing = playingGame();
        int[] closed = findCell(playing, false, false);
        playing.toggleFlag(closed[0], closed[1]);
        assertRoundTrip(playing);
        GameEngine won = playingGame();
        openAllSafeCells(won);
        assertRoundTrip(won);
        GameEngine lost = playingGame();
        int[] mine = findCell(lost, true, false);
        lost.openCell(mine[0], mine[1]);
        assertRoundTrip(lost);
    }

    @Test
    public void restoredGameContinuesWithOriginalMineLayout() {
        GameEngine game = playingGame();
        GameEngine restored = GameEngine.restore(game.serialize());
        assertNotNull(restored);
        int[] closed = findCell(game, false, false);
        assertEquals(game.openCell(closed[0], closed[1]), restored.openCell(closed[0], closed[1]));
        assertEquals(game.serialize(), restored.serialize());
    }

    @Test
    public void malformedAndInconsistentSavesAreRejected() {
        String valid = playingGame().serialize();
        assertNull(GameEngine.restore(null));
        assertNull(GameEngine.restore(""));
        assertNull(GameEngine.restore(valid + "|unexpected"));
        assertNull(GameEngine.restore(valid.substring(0, valid.length() - 1)));
        assertNull(GameEngine.restore(replacePart(valid, 0, "TVM2")));
        assertNull(GameEngine.restore(replacePart(valid, 1, "CUSTOM")));
        assertNull(GameEngine.restore(replacePart(valid, 2, "INVALID")));
        assertNull(GameEngine.restore(replacePart(valid, 2, "READY")));
        assertNull(GameEngine.restore(replacePart(valid, 2, "WON")));
        assertNull(GameEngine.restore(replacePart(valid, 2, "LOST")));
        assertNull(GameEngine.restore(replacePart(valid, 3, "-1")));
        assertNull(GameEngine.restore(replacePart(valid, 3, "11")));
        assertNull(GameEngine.restore(replacePart(valid, 3, "1")));
        assertNull(GameEngine.restore(replacePart(valid, 4, "9999999999999999")));
        assertNull(GameEngine.restore(replacePart(valid, 4, "-1")));
        assertNull(GameEngine.restore(replacePart(valid, 5, "480")));
        assertNull(GameEngine.restore(replacePart(valid, 5, "-1")));
        String payload = valid.split("\\|", -1)[6];
        assertNull(GameEngine.restore(replacePart(valid, 6, "z" + payload.substring(1))));
        assertNull(GameEngine.restore(replacePart(valid, 6, "6" + payload.substring(1))));
        assertNull(GameEngine.restore(replacePart(valid, 6, "8" + payload.substring(1))));
        assertNull(GameEngine.restore(replacePart(valid, 6, payload.replace('1', '0'))));
        StringBuilder oversized = new StringBuilder();
        for (int index = 0; index < 601; index++) {
            oversized.append('x');
        }
        assertNull(GameEngine.restore(oversized.toString()));
    }

    @Test
    public void randomMalformedTextNeverCrashesRestore() {
        Random random = new Random(55);
        for (int attempt = 0; attempt < 300; attempt++) {
            int size = random.nextInt(650);
            StringBuilder text = new StringBuilder(size);
            for (int index = 0; index < size; index++) {
                text.append((char) random.nextInt(128));
            }
            assertNull(GameEngine.restore(text.toString()));
        }
    }

    private static GameEngine playingGame() {
        GameEngine game = new GameEngine(GameEngine.Difficulty.BEGINNER, new Random(42));
        game.openCell(4, 4);
        assertEquals(GameEngine.State.PLAYING, game.getState());
        return game;
    }

    private static int countMines(GameEngine game) {
        int count = 0;
        for (int row = 0; row < game.getRows(); row++) {
            for (int col = 0; col < game.getCols(); col++) {
                count += game.getCell(row, col).isMine() ? 1 : 0;
            }
        }
        return count;
    }

    private static void assertAdjacentNumbers(GameEngine game) {
        for (int row = 0; row < game.getRows(); row++) {
            for (int col = 0; col < game.getCols(); col++) {
                int count = 0;
                for (int nearbyRow = row - 1; nearbyRow <= row + 1; nearbyRow++) {
                    for (int nearbyCol = col - 1; nearbyCol <= col + 1; nearbyCol++) {
                        Cell cell = game.getCell(nearbyRow, nearbyCol);
                        if ((nearbyRow != row || nearbyCol != col) && cell != null && cell.isMine()) {
                            count++;
                        }
                    }
                }
                assertEquals(count, game.getCell(row, col).getAdjacentMines());
            }
        }
    }

    private static void openAllSafeCells(GameEngine game) {
        for (int row = 0; row < game.getRows(); row++) {
            for (int col = 0; col < game.getCols(); col++) {
                if (!game.getCell(row, col).isMine()) {
                    game.openCell(row, col);
                }
            }
        }
    }

    private static int[] findCell(GameEngine game, boolean mine, boolean opened) {
        for (int row = 0; row < game.getRows(); row++) {
            for (int col = 0; col < game.getCols(); col++) {
                Cell cell = game.getCell(row, col);
                if (cell.isMine() == mine && cell.isOpened() == opened) {
                    return new int[]{row, col};
                }
            }
        }
        throw new AssertionError("Expected matching cell");
    }

    private static int[] findChordTarget(GameEngine game) {
        for (int row = 0; row < game.getRows(); row++) {
            for (int col = 0; col < game.getCols(); col++) {
                Cell cell = game.getCell(row, col);
                if (!cell.isOpened() || cell.getAdjacentMines() == 0) {
                    continue;
                }
                for (int nearbyRow = row - 1; nearbyRow <= row + 1; nearbyRow++) {
                    for (int nearbyCol = col - 1; nearbyCol <= col + 1; nearbyCol++) {
                        Cell neighbor = game.getCell(nearbyRow, nearbyCol);
                        if (neighbor != null && !neighbor.isMine() && !neighbor.isOpened()) {
                            return new int[]{row, col};
                        }
                    }
                }
            }
        }
        throw new AssertionError("Expected opened number with closed safe neighbor");
    }

    private static void assertTerminalStateIsLocked(GameEngine game) {
        String before = game.serialize();
        for (int row = 0; row < game.getRows(); row++) {
            for (int col = 0; col < game.getCols(); col++) {
                assertEquals(0, game.openCell(row, col));
                assertEquals(0, game.chord(row, col));
                assertFalse(game.toggleFlag(row, col));
            }
        }
        assertEquals(before, game.serialize());
    }

    private static void assertRoundTrip(GameEngine game) {
        GameEngine restored = GameEngine.restore(game.serialize());
        assertNotNull(restored);
        assertEquals(game.serialize(), restored.serialize());
        assertEquals(game.getDifficulty(), restored.getDifficulty());
        for (int row = 0; row < game.getRows(); row++) {
            for (int col = 0; col < game.getCols(); col++) {
                assertEquals(game.getCell(row, col).getAdjacentMines(),
                        restored.getCell(row, col).getAdjacentMines());
            }
        }
    }

    private static String replacePart(String saved, int index, String replacement) {
        String[] parts = saved.split("\\|", -1);
        parts[index] = replacement;
        StringBuilder result = new StringBuilder();
        for (int part = 0; part < parts.length; part++) {
            if (part > 0) {
                result.append('|');
            }
            result.append(parts[part]);
        }
        return result.toString();
    }
}
