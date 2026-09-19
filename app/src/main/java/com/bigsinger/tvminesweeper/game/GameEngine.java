package com.bigsinger.tvminesweeper.game;

import java.util.ArrayDeque;
import java.util.Random;

/** 不依赖 Android 的扫雷规则：首次安全、空白展开、插旗、数字快开和存档。 */
public final class GameEngine {
    private static final String SAVE_VERSION = "TVM1";
    private static final int SAVE_PART_COUNT = 7;
    private static final int MAX_SAVE_LENGTH = 600;
    private static final int MINE_BIT = 1;
    private static final int OPENED_BIT = 2;
    private static final int FLAGGED_BIT = 4;
    private static final int EXPLODED_BIT = 8;

    /** 对局状态；结束后仅允许读取和存档。 */
    public enum State {
        READY, PLAYING, WON, LOST
    }

    /** 经典难度；行列顺序适配横屏电视。 */
    public enum Difficulty {
        BEGINNER(9, 9, 10),
        INTERMEDIATE(16, 16, 40),
        EXPERT(16, 30, 99);

        public final int rows;
        public final int cols;
        public final int mines;

        Difficulty(int rows, int cols, int mines) {
            this.rows = rows;
            this.cols = cols;
            this.mines = mines;
        }
    }

    private final Difficulty difficulty;
    private final Random random;
    private final Cell[] cells;
    private State state = State.READY;
    private int flagCount;
    private int openedCount;
    private int firstOpenedIndex = -1;

    /** 创建等待首次翻开的新对局。 */
    public GameEngine(Difficulty difficulty) {
        this(difficulty, new Random());
    }

    /** 注入随机源，便于重现雷区和验证规则。 */
    public GameEngine(Difficulty difficulty, Random random) {
        if (difficulty == null || random == null) {
            throw new IllegalArgumentException("Difficulty and random must not be null");
        }
        this.difficulty = difficulty;
        this.random = random;
        cells = new Cell[difficulty.rows * difficulty.cols];
        for (int index = 0; index < cells.length; index++) {
            cells[index] = new Cell();
        }
    }

    /** 返回当前难度。 */
    public Difficulty getDifficulty() {
        return difficulty;
    }

    /** 返回当前对局状态。 */
    public State getState() {
        return state;
    }

    /** 返回棋盘行数。 */
    public int getRows() {
        return difficulty.rows;
    }

    /** 返回棋盘列数。 */
    public int getCols() {
        return difficulty.cols;
    }

    /** 返回本局地雷总数。 */
    public int getMineCount() {
        return difficulty.mines;
    }

    /** 返回已插旗数量。 */
    public int getFlagCount() {
        return flagCount;
    }

    /** 返回已翻开的安全格数量，不计触发的地雷。 */
    public int getOpenedCount() {
        return openedCount;
    }

    /** 返回指定格，越界时返回 null。 */
    public Cell getCell(int row, int col) {
        return isInside(row, col) ? cells[indexOf(row, col)] : null;
    }

    /**
     * 翻开指定格，返回本次翻开的安全格数。
     * 首次翻开时才生成雷区，保证该格和周围八格安全；已翻开、插旗或越界时无操作。
     */
    public int openCell(int row, int col) {
        if (isFinished() || !isInside(row, col)) {
            return 0;
        }
        int index = indexOf(row, col);
        Cell cell = cells[index];
        if (cell.opened || cell.flagged) {
            return 0;
        }
        if (state == State.READY) {
            firstOpenedIndex = index;
            generateMines(row, col);
            state = State.PLAYING;
        }
        int before = openedCount;
        reveal(index);
        finishIfWon();
        return openedCount - before;
    }

    /** 切换未翻开格的旗帜，成功返回 true；旗帜总数不超过地雷总数。 */
    public boolean toggleFlag(int row, int col) {
        if (isFinished() || !isInside(row, col)) {
            return false;
        }
        Cell cell = cells[indexOf(row, col)];
        if (cell.opened || (!cell.flagged && flagCount >= difficulty.mines)) {
            return false;
        }
        cell.flagged = !cell.flagged;
        flagCount += cell.flagged ? 1 : -1;
        return true;
    }

    /**
     * 数字周围旗数等于数字时，翻开其余邻格，返回本次翻开的安全格数。
     * 错误旗帜可能使未标记的地雷被触发；旗数不足或状态不适合时无操作。
     */
    public int chord(int row, int col) {
        if (state != State.PLAYING || !isInside(row, col)) {
            return 0;
        }
        Cell center = cells[indexOf(row, col)];
        if (!center.opened || center.mine || center.adjacentMines == 0) {
            return 0;
        }
        int nearbyFlags = 0;
        for (int nearbyRow = row - 1; nearbyRow <= row + 1; nearbyRow++) {
            for (int nearbyCol = col - 1; nearbyCol <= col + 1; nearbyCol++) {
                Cell neighbor = getCell(nearbyRow, nearbyCol);
                if (neighbor != null && neighbor.flagged) {
                    nearbyFlags++;
                }
            }
        }
        if (nearbyFlags != center.adjacentMines) {
            return 0;
        }
        int before = openedCount;
        for (int nearbyRow = row - 1; nearbyRow <= row + 1; nearbyRow++) {
            for (int nearbyCol = col - 1; nearbyCol <= col + 1; nearbyCol++) {
                if (isInside(nearbyRow, nearbyCol)) {
                    reveal(indexOf(nearbyRow, nearbyCol));
                    if (state == State.LOST) {
                        return openedCount - before;
                    }
                }
            }
        }
        finishIfWon();
        return openedCount - before;
    }

    /** 生成带版本号的紧凑文本，支持未开局、进行中、胜利和失败状态。 */
    public String serialize() {
        StringBuilder result = new StringBuilder(cells.length + 64);
        result.append(SAVE_VERSION).append('|').append(difficulty.name()).append('|')
                .append(state.name()).append('|').append(flagCount).append('|')
                .append(openedCount).append('|').append(firstOpenedIndex).append('|');
        for (Cell cell : cells) {
            int value = (cell.mine ? MINE_BIT : 0) | (cell.opened ? OPENED_BIT : 0)
                    | (cell.flagged ? FLAGGED_BIT : 0) | (cell.exploded ? EXPLODED_BIT : 0);
            result.append(Character.forDigit(value, 16));
        }
        return result.toString();
    }

    /** 恢复并校验存档；未知版本、截断、越界或互相矛盾的状态统一返回 null。 */
    public static GameEngine restore(String saved) {
        if (saved == null || saved.length() > MAX_SAVE_LENGTH) {
            return null;
        }
        String[] parts = saved.split("\\|", -1);
        if (parts.length != SAVE_PART_COUNT || !SAVE_VERSION.equals(parts[0])) {
            return null;
        }
        try {
            GameEngine engine = new GameEngine(Difficulty.valueOf(parts[1]));
            engine.state = State.valueOf(parts[2]);
            engine.flagCount = Integer.parseInt(parts[3]);
            engine.openedCount = Integer.parseInt(parts[4]);
            engine.firstOpenedIndex = Integer.parseInt(parts[5]);
            if (parts[6].length() != engine.cells.length) {
                return null;
            }
            for (int index = 0; index < engine.cells.length; index++) {
                char encoded = parts[6].charAt(index);
                int value = Character.digit(encoded, 16);
                if (value < 0 || encoded > 'f') {
                    return null;
                }
                Cell cell = engine.cells[index];
                cell.mine = (value & MINE_BIT) != 0;
                cell.opened = (value & OPENED_BIT) != 0;
                cell.flagged = (value & FLAGGED_BIT) != 0;
                cell.exploded = (value & EXPLODED_BIT) != 0;
            }
            if (!engine.isValidSave()) {
                return null;
            }
            engine.calculateAdjacentMines();
            return engine;
        } catch (IllegalArgumentException invalidSave) {
            // 旧版本或损坏偏好设置不应阻止应用启动；由调用方回退为新局。
            return null;
        }
    }

    private boolean isInside(int row, int col) {
        return row >= 0 && row < difficulty.rows && col >= 0 && col < difficulty.cols;
    }

    private int indexOf(int row, int col) {
        return row * difficulty.cols + col;
    }

    private boolean isFinished() {
        return state == State.WON || state == State.LOST;
    }

    private void generateMines(int safeRow, int safeCol) {
        int[] candidates = new int[cells.length];
        int candidateCount = 0;
        for (int row = 0; row < difficulty.rows; row++) {
            for (int col = 0; col < difficulty.cols; col++) {
                if (Math.abs(row - safeRow) > 1 || Math.abs(col - safeCol) > 1) {
                    candidates[candidateCount++] = indexOf(row, col);
                }
            }
        }
        // 部分 Fisher-Yates 洗牌，固定运行次数，避免拒绝采样的无界重试。
        for (int placed = 0; placed < difficulty.mines; placed++) {
            int selected = placed + random.nextInt(candidateCount - placed);
            int mineIndex = candidates[selected];
            candidates[selected] = candidates[placed];
            candidates[placed] = mineIndex;
            cells[mineIndex].mine = true;
        }
        calculateAdjacentMines();
    }

    private void calculateAdjacentMines() {
        for (int row = 0; row < difficulty.rows; row++) {
            for (int col = 0; col < difficulty.cols; col++) {
                int count = 0;
                for (int nearbyRow = row - 1; nearbyRow <= row + 1; nearbyRow++) {
                    for (int nearbyCol = col - 1; nearbyCol <= col + 1; nearbyCol++) {
                        Cell neighbor = getCell(nearbyRow, nearbyCol);
                        if ((nearbyRow != row || nearbyCol != col) && neighbor != null && neighbor.mine) {
                            count++;
                        }
                    }
                }
                cells[indexOf(row, col)].adjacentMines = count;
            }
        }
    }

    private void reveal(int index) {
        Cell target = cells[index];
        if (target.opened || target.flagged) {
            return;
        }
        if (target.mine) {
            target.opened = true;
            target.exploded = true;
            state = State.LOST;
            return;
        }
        ArrayDeque<Integer> pending = new ArrayDeque<Integer>();
        target.opened = true;
        openedCount++;
        pending.addLast(index);
        while (!pending.isEmpty()) {
            int current = pending.removeFirst();
            if (cells[current].adjacentMines != 0) {
                continue;
            }
            int row = current / difficulty.cols;
            int col = current % difficulty.cols;
            for (int nearbyRow = row - 1; nearbyRow <= row + 1; nearbyRow++) {
                for (int nearbyCol = col - 1; nearbyCol <= col + 1; nearbyCol++) {
                    Cell neighbor = getCell(nearbyRow, nearbyCol);
                    if (neighbor != null && !neighbor.opened && !neighbor.flagged && !neighbor.mine) {
                        neighbor.opened = true;
                        openedCount++;
                        pending.addLast(indexOf(nearbyRow, nearbyCol));
                    }
                }
            }
        }
    }

    private void finishIfWon() {
        if (state == State.PLAYING && openedCount == cells.length - difficulty.mines) {
            state = State.WON;
            for (Cell cell : cells) {
                if (cell.mine) {
                    cell.flagged = true;
                }
            }
            flagCount = difficulty.mines;
        }
    }

    private boolean isValidSave() {
        if (flagCount < 0 || flagCount > difficulty.mines || openedCount < 0
                || openedCount > cells.length - difficulty.mines) {
            return false;
        }
        int actualFlags = 0;
        int actualOpened = 0;
        int actualMines = 0;
        int explosions = 0;
        for (Cell cell : cells) {
            if (cell.opened && cell.flagged) {
                return false;
            }
            if (cell.exploded != (cell.mine && cell.opened)) {
                return false;
            }
            actualFlags += cell.flagged ? 1 : 0;
            actualOpened += cell.opened && !cell.mine ? 1 : 0;
            actualMines += cell.mine ? 1 : 0;
            explosions += cell.exploded ? 1 : 0;
        }
        if (actualFlags != flagCount || actualOpened != openedCount) {
            return false;
        }
        if (state == State.READY) {
            return actualMines == 0 && openedCount == 0 && explosions == 0 && firstOpenedIndex == -1;
        }
        if (actualMines != difficulty.mines || firstOpenedIndex < 0 || firstOpenedIndex >= cells.length
                || !cells[firstOpenedIndex].opened || openedCount == 0) {
            return false;
        }
        int firstRow = firstOpenedIndex / difficulty.cols;
        int firstCol = firstOpenedIndex % difficulty.cols;
        for (int row = firstRow - 1; row <= firstRow + 1; row++) {
            for (int col = firstCol - 1; col <= firstCol + 1; col++) {
                Cell cell = getCell(row, col);
                if (cell != null && cell.mine) {
                    return false;
                }
            }
        }
        if (state == State.WON) {
            return explosions == 0 && openedCount == cells.length - difficulty.mines
                    && flagCount == difficulty.mines;
        }
        if (openedCount == cells.length - difficulty.mines) {
            return false;
        }
        return state == State.LOST ? explosions == 1 : explosions == 0;
    }
}
