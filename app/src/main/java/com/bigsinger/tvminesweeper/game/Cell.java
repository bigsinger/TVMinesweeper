package com.bigsinger.tvminesweeper.game;

/** 棋盘格；界面只能读取状态，规则由游戏引擎统一维护。 */
public final class Cell {
    boolean mine;
    boolean opened;
    boolean flagged;
    boolean exploded;
    int adjacentMines;

    /** 返回该格是否有雷；未生成雷区时为 false。 */
    public boolean isMine() {
        return mine;
    }

    /** 返回该格是否已经翻开。 */
    public boolean isOpened() {
        return opened;
    }

    /** 返回该格是否插旗。 */
    public boolean isFlagged() {
        return flagged;
    }

    /** 返回该格是否为本局触发的地雷。 */
    public boolean isExploded() {
        return exploded;
    }

    /** 返回周围八格的地雷数量。 */
    public int getAdjacentMines() {
        return adjacentMines;
    }
}
