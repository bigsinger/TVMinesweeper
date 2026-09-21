package com.bigsinger.tvminesweeper.game;

import java.util.ArrayDeque;
import java.util.Random;

/** 不依赖 Android 的扫雷规则：首次安全、空白展开、插旗、数字快开和存档。 */
public final class GameEngine {
    private static final String SAVE_VERSION = "TVM3";
    private static final int SAVE_PART_COUNT = 9;
    private static final int MAX_SAVE_LENGTH = 600;
    private static final int MINE_BIT = 1;
    private static final int OPENED_BIT = 2;
    private static final int FLAGGED_BIT = 4;
    private static final int EXPLODED_BIT = 8;

    /** 对局状态；结束后仅允许读取和存档。 */
    public enum State {
        READY, PLAYING, WON, LOST
    }

    /** 新局的五档规格；旧局通过独立布局标记保留实际行列数。 */
    public enum Difficulty {
        BEGINNER(9, 9, 10, 15),
        INTERMEDIATE(10, 12, 18, 24),
        ADVANCED(13, 16, 34, 44),
        HARD(16, 20, 58, 72),
        CHALLENGE(19, 24, 90, 108);

        public final int rows;
        public final int cols;
        public final int minMines;
        public final int maxMines;

        Difficulty(int rows, int cols, int minMines, int maxMines) {
            this.rows = rows;
            this.cols = cols;
            this.minMines = minMines;
            this.maxMines = maxMines;
        }
    }

    /** 仅允许已发布过的布局，避免存档携带任意尺寸或与难度矛盾的雷数范围。 */
    private enum Layout {
        CURRENT(null, 0, 0, 0, 0),
        LEGACY_INTERMEDIATE(Difficulty.ADVANCED, 16, 16, 40, 50),
        LEGACY_EXPERT(Difficulty.CHALLENGE, 16, 30, 90, 110);

        final Difficulty difficulty;
        final int rows;
        final int cols;
        final int minMines;
        final int maxMines;

        Layout(Difficulty difficulty, int rows, int cols, int minMines, int maxMines) {
            this.difficulty = difficulty;
            this.rows = rows;
            this.cols = cols;
            this.minMines = minMines;
            this.maxMines = maxMines;
        }
    }

    private final Difficulty difficulty;
    private final Layout layout;
    private final int rows;
    private final int cols;
    private final Random random;
    private final int mineCount;
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
        this(difficulty, random, chooseMineCount(difficulty, random), Layout.CURRENT);
    }

    private GameEngine(Difficulty difficulty, Random random, int mineCount, Layout layout) {
        if (difficulty == null || random == null) {
            throw new IllegalArgumentException("Difficulty and random must not be null");
        }
        boolean current = layout == Layout.CURRENT;
        if (!current && layout.difficulty != difficulty) {
            throw new IllegalArgumentException("Legacy layout does not match difficulty");
        }
        int minMines = current ? difficulty.minMines : layout.minMines;
        int maxMines = current ? difficulty.maxMines : layout.maxMines;
        if (mineCount < minMines || mineCount > maxMines) {
            throw new IllegalArgumentException("Mine count is outside the difficulty range");
        }
        this.difficulty = difficulty;
        this.layout = layout;
        this.rows = current ? difficulty.rows : layout.rows;
        this.cols = current ? difficulty.cols : layout.cols;
        this.random = random;
        this.mineCount = mineCount;
        cells = new Cell[rows * cols];
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

    /** 返回本局实际行数；迁移的旧局可与新局难度规格不同。 */
    public int getRows() {
        return rows;
    }

    /** 返回本局实际列数；迁移的旧局可与新局难度规格不同。 */
    public int getCols() {
        return cols;
    }

    /** 返回本局地雷总数。 */
    public int getMineCount() {
        return mineCount;
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
        if (cell.opened || (!cell.flagged && flagCount >= mineCount)) {
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
                .append(openedCount).append('|').append(firstOpenedIndex).append('|')
                .append(mineCount).append('|').append(layout.name()).append('|');
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
        boolean legacyFixed = parts.length == 7 && "TVM1".equals(parts[0]);
        boolean legacyRandom = parts.length == 8 && "TVM2".equals(parts[0]);
        boolean current = parts.length == SAVE_PART_COUNT && SAVE_VERSION.equals(parts[0]);
        if (!legacyFixed && !legacyRandom && !current) {
            return null;
        }
        try {
            Difficulty difficulty;
            Layout layout;
            int fixedMineCount = 0;
            if (current) {
                difficulty = Difficulty.valueOf(parts[1]);
                layout = Layout.valueOf(parts[7]);
            } else if ("BEGINNER".equals(parts[1])) {
                difficulty = Difficulty.BEGINNER;
                layout = Layout.CURRENT;
                fixedMineCount = 10;
            } else if ("INTERMEDIATE".equals(parts[1])) {
                difficulty = Difficulty.ADVANCED;
                layout = Layout.LEGACY_INTERMEDIATE;
                fixedMineCount = 40;
            } else if ("EXPERT".equals(parts[1])) {
                difficulty = Difficulty.CHALLENGE;
                layout = Layout.LEGACY_EXPERT;
                fixedMineCount = 99;
            } else {
                return null;
            }
            int savedMineCount = legacyFixed ? fixedMineCount : Integer.parseInt(parts[6]);
            GameEngine engine = new GameEngine(difficulty, new Random(), savedMineCount, layout);
            engine.state = State.valueOf(parts[2]);
            engine.flagCount = Integer.parseInt(parts[3]);
            engine.openedCount = Integer.parseInt(parts[4]);
            engine.firstOpenedIndex = Integer.parseInt(parts[5]);
            String payload = parts[current ? 8 : legacyFixed ? 6 : 7];
            if (payload.length() != engine.cells.length) {
                return null;
            }
            for (int index = 0; index < engine.cells.length; index++) {
                char encoded = payload.charAt(index);
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

    private static int chooseMineCount(Difficulty difficulty, Random random) {
        if (difficulty == null || random == null) {
            throw new IllegalArgumentException("Difficulty and random must not be null");
        }
        return difficulty.minMines + random.nextInt(difficulty.maxMines - difficulty.minMines + 1);
    }

    private boolean isInside(int row, int col) {
        return row >= 0 && row < rows && col >= 0 && col < cols;
    }

    private int indexOf(int row, int col) {
        return row * cols + col;
    }

    private boolean isFinished() {
        return state == State.WON || state == State.LOST;
    }

    private void generateMines(int safeRow, int safeCol) {
        int[] candidates = new int[cells.length];
        int candidateCount = 0;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                if (Math.abs(row - safeRow) > 1 || Math.abs(col - safeCol) > 1) {
                    candidates[candidateCount++] = indexOf(row, col);
                }
            }
        }
        // 部分 Fisher-Yates 洗牌，固定运行次数，避免拒绝采样的无界重试。
        for (int placed = 0; placed < mineCount; placed++) {
            int selected = placed + random.nextInt(candidateCount - placed);
            int mineIndex = candidates[selected];
            candidates[selected] = candidates[placed];
            candidates[placed] = mineIndex;
            cells[mineIndex].mine = true;
        }
        calculateAdjacentMines();
    }

    private void calculateAdjacentMines() {
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
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
            int row = current / cols;
            int col = current % cols;
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
        if (state == State.PLAYING && openedCount == cells.length - mineCount) {
            state = State.WON;
            for (Cell cell : cells) {
                if (cell.mine) {
                    cell.flagged = true;
                }
            }
            flagCount = mineCount;
        }
    }

    private boolean isValidSave() {
        if (flagCount < 0 || flagCount > mineCount || openedCount < 0
                || openedCount > cells.length - mineCount) {
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
        if (actualMines != mineCount || firstOpenedIndex < 0 || firstOpenedIndex >= cells.length
                || !cells[firstOpenedIndex].opened || openedCount == 0) {
            return false;
        }
        int firstRow = firstOpenedIndex / cols;
        int firstCol = firstOpenedIndex % cols;
        for (int row = firstRow - 1; row <= firstRow + 1; row++) {
            for (int col = firstCol - 1; col <= firstCol + 1; col++) {
                Cell cell = getCell(row, col);
                if (cell != null && cell.mine) {
                    return false;
                }
            }
        }
        if (state == State.WON) {
            return explosions == 0 && openedCount == cells.length - mineCount
                    && flagCount == mineCount;
        }
        if (openedCount == cells.length - mineCount) {
            return false;
        }
        return state == State.LOST ? explosions == 1 : explosions == 0;
    }
}
