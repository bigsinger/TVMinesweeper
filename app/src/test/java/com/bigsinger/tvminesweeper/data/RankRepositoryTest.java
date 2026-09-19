package com.bigsinger.tvminesweeper.data;

import com.bigsinger.tvminesweeper.game.GameEngine;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** 随机雷数只改变安全格校验，通关积分继续沿用原公式。 */
public class RankRepositoryTest {
    @Test
    public void everySupportedMineCountCanProduceAValidScore() {
        int[] expectedScores = {100, 300, 800};
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
        assertEquals(0, RankRepository.calculateScore(3, 1, 71));
    }

    @Test
    public void legacyWinsAndRoundingKeepOriginalScores() {
        assertEquals(10000, RankRepository.calculateScore(0, 0, 71));
        assertEquals(10000, RankRepository.calculateScore(0, 1, 71));
        assertEquals(3333, RankRepository.calculateScore(0, 3, 71));
        assertEquals(300, RankRepository.calculateScore(1, 100, 216));
        assertEquals(800, RankRepository.calculateScore(2, 100, 381));
    }
}
