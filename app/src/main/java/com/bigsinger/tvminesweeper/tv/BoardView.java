package com.bigsinger.tvminesweeper.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import com.bigsinger.tvminesweeper.R;
import com.bigsinger.tvminesweeper.data.RankRepository;
import com.bigsinger.tvminesweeper.game.Cell;
import com.bigsinger.tvminesweeper.game.GameEngine;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** A single focusable TV surface; decorative panels never steal remote focus. */
public final class BoardView extends View {
    private static final float DESIGN_WIDTH = 1920f;
    private static final float DESIGN_HEIGHT = 1080f;
    private static final float OUTER_MARGIN_PX = 2f;
    private static final float RANK_WIDTH = 316f;
    private static final float INFO_WIDTH = 232f;
    private static final float PANEL_GAP = 12f;
    private static final float BOARD_PADDING = 10f;
    private static final float FOOTER_HEIGHT = 38f;
    private static final int BG = Color.rgb(6, 10, 15);
    private static final int PANEL = Color.rgb(16, 23, 32);
    private static final int LINE = Color.rgb(34, 49, 63);
    private static final int INK = Color.rgb(230, 239, 245);
    private static final int DIM = Color.rgb(139, 158, 175);
    private static final int ACCENT = Color.rgb(56, 225, 200);
    private static final int GOLD = Color.rgb(255, 209, 102);
    private static final int RED = Color.rgb(255, 107, 107);
    private static final int[] NUMBERS = {INK, 0xff64b4ff, 0xff63e699, 0xffff7a83,
            0xffc398ff, 0xffffd166, 0xff62ddea, 0xffeeeeee, 0xffb8c9dc};
    private static final Typeface NORMAL = Typeface.create("sans-serif", Typeface.NORMAL);
    private static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final Callback callback;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("MM.dd", Locale.getDefault());
    private GameEngine engine;
    private List<RankRepository.Entry> ranks = Collections.emptyList();
    private int row;
    private int col;
    private long elapsed;
    private int status = R.string.ready_status;
    private boolean paused;
    private String lastAccessibilityState = "";
    private boolean soundEnabled = true;
    private String newRecord;
    private long flashUntil;
    private float scale = 1f;
    private float offsetX;
    private float offsetY;
    private float boardLeft;
    private float boardTop;
    private float cellSize;
    private float edge = OUTER_MARGIN_PX;
    private float rankRight;
    private float infoLeft;
    private float infoRight;
    private float gameLeft;
    private float gameRight;
    private float gameBottom;
    private final RectF menuBounds = new RectF();
    private float touchX;
    private float touchY;
    private LinearGradient backdrop;


    /** Touch is optional; every action is also available through the remote. */
    public interface Callback {
        void onCellTap(int row, int col);
        void onMenuTap();
        void onResumeTap();
    }

    /** Creates the scalable 16:9 game surface. */
    public BoardView(Context context, Callback callback) {
        super(context);
        this.callback = callback;
        setFocusable(true);
        setFocusableInTouchMode(true);
        setSoundEffectsEnabled(false);
        setContentDescription(context.getString(R.string.board_description));
        backdrop = new LinearGradient(0, 0, DESIGN_WIDTH, DESIGN_HEIGHT,
                new int[]{0xff101a25, BG, 0xff0a161c}, null, Shader.TileMode.CLAMP);

    }

    /** Replaces the visible state without assigning focus to any decoration. */
    public void update(GameEngine engine, int row, int col, long elapsed, int status,
            boolean paused, boolean soundEnabled) {
        this.engine = engine;
        this.row = row;
        this.col = col;
        this.elapsed = elapsed;
        this.status = status;
        this.paused = paused;
        this.soundEnabled = soundEnabled;
        if (engine == null) { invalidate(); return; }
        int stateText = engine.getState() == GameEngine.State.READY ? R.string.a11y_ready
                : engine.getState() == GameEngine.State.PLAYING ? R.string.a11y_playing
                : engine.getState() == GameEngine.State.WON ? R.string.a11y_won : R.string.a11y_lost;
        Cell selected = engine.getCell(row, col);
        int cellText = selected.isFlagged() ? R.string.a11y_flag
                : selected.isOpened() ? R.string.a11y_open : R.string.a11y_hidden;
        String accessibilityState = getContext().getString(R.string.a11y_state,
                getContext().getString(stateText), row + 1, col + 1, engine.getOpenedCount(),
                engine.getFlagCount(), elapsed / 1000L,
                getContext().getString(paused ? R.string.a11y_yes : R.string.a11y_no),
                getContext().getString(cellText), engine.getMineCount());
        if (!accessibilityState.equals(lastAccessibilityState)) {
            lastAccessibilityState = accessibilityState;
            setContentDescription(accessibilityState);
        }
        invalidate();
    }

    /** Displays real local records and briefly highlights a newly recorded win. */
    public void setRanks(List<RankRepository.Entry> entries, String newId) {
        ranks = entries;
        newRecord = newId;
        flashUntil = SystemClock.uptimeMillis() + 1400L;
        invalidate();
    }

    /** Formats a duration without wrapping after an hour. */
    public static String formatTime(long milliseconds) {
        long seconds = Math.max(0, milliseconds / 1000L);
        return String.format(Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L);
    }

    /** Maps the persisted difficulty ordinal to a localized label. */
    public static int difficultyLabel(int ordinal) {
        return ordinal == 2 ? R.string.expert : ordinal == 1 ? R.string.intermediate : R.string.beginner;
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        scale = Math.min(w / DESIGN_WIDTH, h / DESIGN_HEIGHT);
        offsetX = (w - DESIGN_WIDTH * scale) / 2f;
        offsetY = (h - DESIGN_HEIGHT * scale) / 2f;
        // Keep only two physical pixels outside the panels at both 720p and 1080p.
        edge = OUTER_MARGIN_PX / Math.max(0.1f, scale);
        rankRight = edge + RANK_WIDTH;
        infoLeft = rankRight + PANEL_GAP;
        infoRight = infoLeft + INFO_WIDTH;
        gameLeft = infoRight + PANEL_GAP;
        gameRight = DESIGN_WIDTH - edge;
        gameBottom = DESIGN_HEIGHT - edge - FOOTER_HEIGHT;
        menuBounds.set(infoLeft + 18, 505, infoRight - 18, 567);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(BG);
        canvas.save();
        canvas.translate(offsetX, offsetY);
        canvas.scale(scale, scale);
        paint.setShader(backdrop);
        canvas.drawRect(0, 0, DESIGN_WIDTH, DESIGN_HEIGHT, paint);
        paint.setShader(null);
        drawRank(canvas);
        if (engine == null) {
            text(canvas, getContext().getString(R.string.loading), 1120, 530, 30, ACCENT, true, true);
        } else {
            drawHeader(canvas);
            drawBoard(canvas);
            drawFooter(canvas);
            if (paused) {
                drawPause(canvas);
            }
        }
        canvas.restore();
    }

    private void drawRank(Canvas canvas) {
        float left = edge + 22;
        float center = edge + RANK_WIDTH / 2f;
        float textWidth = RANK_WIDTH - 44;
        box(canvas, edge, edge, rankRight, DESIGN_HEIGHT - edge, 16, PANEL, LINE);
        fitText(canvas, getContext().getString(R.string.rank_title), left, 56, textWidth, 33, INK, true);
        fitText(canvas, getContext().getString(R.string.rank_sub), left, 92, textWidth, 21, DIM, false);
        line(canvas, left, 116, rankRight - 22, 116, LINE, 1);
        line(canvas, left, 116, left + 72, 116, ACCENT, 3);
        if (ranks.isEmpty()) {
            mine(canvas, center, 414, 38, ACCENT);
            text(canvas, getContext().getString(R.string.rank_empty), center, 504, 30, INK, true, true);
            text(canvas, getContext().getString(R.string.rank_empty_sub), center, 553, 38, ACCENT, true, true);
            fitText(canvas, getContext().getString(R.string.rank_empty_hint), left, 612, textWidth, 25, DIM, false);
            for (int i = 0; i < 3; i++) {
                box(canvas, center - 75 + i * 56, 673, center - 33 + i * 56, 715, 7, 0xff1e2b38, LINE);
            }
        } else {
            for (int i = 0; i < ranks.size() && i < 8; i++) {
                RankRepository.Entry entry = ranks.get(i);
                float top = 139 + i * 103;
                boolean fresh = entry.id.equals(newRecord) && SystemClock.uptimeMillis() < flashUntil;
                int medal = i == 0 ? GOLD : i == 1 ? 0xffc9d4e0 : i == 2 ? 0xffd6a071 : DIM;
                box(canvas, edge + 10, top, rankRight - 10, top + 94, 11,
                        i == 0 ? 0xff25231c : 0xff15202a, fresh ? ACCENT : i < 3 ? 0xff454235 : LINE);
                text(canvas, String.valueOf(i + 1), edge + 33, top + 39, 28, medal, true, true);
                text(canvas, String.valueOf(entry.score), edge + 63, top + 41, 36, ACCENT, true, false);
                String info = getContext().getString(R.string.rank_meta,
                        getContext().getString(difficultyLabel(entry.difficulty)),
                        formatTime(entry.seconds * 1000L), dateFormat.format(new Date(entry.date)));
                fitText(canvas, info, left, top + 76, textWidth, 23, DIM, false);
                if (fresh) { postInvalidateDelayed(100L); }
            }
        }
        line(canvas, left, 986, rankRight - 22, 986, LINE, 1);
        String[] footer = getContext().getString(R.string.rank_footer).split("\n");
        for (int i = 0; i < footer.length; i++) {
            fitText(canvas, footer[i], left, 1024 + i * 29, textWidth, 23, DIM, false);
        }
    }

    private void drawHeader(Canvas canvas) {
        float center = (infoLeft + infoRight) / 2f;
        float left = infoLeft + 22;
        float width = INFO_WIDTH - 44;
        box(canvas, infoLeft, edge, infoRight, DESIGN_HEIGHT - edge, 16, PANEL, LINE);
        fitText(canvas, getContext().getString(R.string.brand), left, 56, width, 33, INK, true);
        line(canvas, left, 91, infoRight - 22, 91, LINE, 1);
        text(canvas, getContext().getString(R.string.mines_left), center, 143, 25, DIM, false, true);
        text(canvas, String.format(Locale.US, "%03d", engine.getMineCount() - engine.getFlagCount()),
                center, 209, 64, GOLD, true, true);
        text(canvas, getContext().getString(R.string.time_label), center, 269, 25, DIM, false, true);
        fitText(canvas, formatTime(elapsed), left, 328, width, 56, ACCENT, true);
        line(canvas, left, 364, infoRight - 22, 364, LINE, 1);
        text(canvas, getContext().getString(difficultyLabel(engine.getDifficulty().ordinal())),
                center, 407, 32, INK, true, true);
        text(canvas, engine.getCols() + " × " + engine.getRows(), center, 447, 29, DIM, false, true);
        text(canvas, getContext().getString(R.string.mine_total, engine.getMineCount()),
                center, 484, 25, DIM, false, true);
        box(canvas, menuBounds.left, menuBounds.top, menuBounds.right, menuBounds.bottom,
                10, 0xff173b3b, 0xff358c81);
        text(canvas, getContext().getString(R.string.menu_key), center, 546, 29, ACCENT, true, true);
        int safeCells = engine.getRows() * engine.getCols() - engine.getMineCount();
        text(canvas, getContext().getString(R.string.safe_cells), center, 626, 25, DIM, false, true);
        text(canvas, engine.getOpenedCount() + " / " + safeCells, center, 674, 35, INK, true, true);
        float progress = engine.getOpenedCount() / (float) safeCells;
        box(canvas, left, 698, infoRight - 22, 704, 3, LINE, Color.TRANSPARENT);
        if (progress > 0) {
            box(canvas, left, 698, left + width * progress, 704, 3, ACCENT, Color.TRANSPARENT);
        }
        line(canvas, left, 742, infoRight - 22, 742, LINE, 1);
        int[] tips = {R.string.side_move, R.string.side_flag, R.string.side_open, R.string.side_menu};
        for (int i = 0; i < tips.length; i++) {
            fitText(canvas, getContext().getString(tips[i]), left, 792 + i * 46, width, 26, DIM, false);
        }
        fitText(canvas, getContext().getString(soundEnabled ? R.string.sound_on : R.string.sound_off),
                left, 1018, width, 24, DIM, false);
        fitText(canvas, getContext().getString(R.string.side_back), left, 1053, width, 23, DIM, false);
    }
    private void drawBoard(Canvas canvas) {
        int rows = engine.getRows();
        int cols = engine.getCols();
        cellSize = Math.min((gameRight - gameLeft - 2 * BOARD_PADDING) / cols,
                (gameBottom - edge - 2 * BOARD_PADDING) / rows);
        float width = cols * cellSize;
        float height = rows * cellSize;
        boardLeft = (gameLeft + gameRight - width) / 2f;
        boardTop = (edge + gameBottom - height) / 2f;
        box(canvas, boardLeft - BOARD_PADDING, boardTop - BOARD_PADDING,
                boardLeft + width + BOARD_PADDING, boardTop + height + BOARD_PADDING,
                18, 0xff091019, LINE);
        boolean lost = engine.getState() == GameEngine.State.LOST;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                Cell cell = engine.getCell(r, c);
                float x = boardLeft + c * cellSize;
                float y = boardTop + r * cellSize;
                float edge = cellSize - 3;
                int fill = cell.isOpened() ? 0xff101923 : 0xff2d3b48;
                if (cell.isFlagged()) { fill = 0xff3a3020; }
                if (lost && cell.isMine()) { fill = cell.isExploded() ? 0xffb53743 : 0xff42252b; }
                box(canvas, x + 1, y + 1, x + edge, y + edge, Math.min(6, cellSize / 9), fill,
                        cell.isOpened() ? 0xff1b2b38 : 0xff40505f);
                if (!cell.isOpened() && !cell.isFlagged() && !(lost && cell.isMine())) {
                    line(canvas, x + 7, y + 3, x + edge - 6, y + 3, 0xff53616c, 1);
                }
                float centerX = x + (cellSize - 2) / 2f;
                float centerY = y + (cellSize - 2) / 2f;
                if (cell.isFlagged()) {
                    flag(canvas, centerX, centerY, cellSize * 0.27f, GOLD);
                    if (lost && !cell.isMine()) {
                        line(canvas, x + 7, y + 7, x + edge - 6, y + edge - 6, RED, 3);
                        line(canvas, x + edge - 6, y + 7, x + 7, y + edge - 6, RED, 3);
                    }
                } else if (lost && cell.isMine()) {
                    mine(canvas, centerX, centerY, cellSize * 0.22f, cell.isExploded() ? INK : RED);
                } else if (cell.isOpened() && cell.getAdjacentMines() > 0) {
                    centeredText(canvas, String.valueOf(cell.getAdjacentMines()), centerX, centerY,
                            Math.min(56, cellSize * 0.77f), NUMBERS[cell.getAdjacentMines()]);
                }
            }
        }
        float fx = boardLeft + col * cellSize;
        float fy = boardTop + row * cellSize;
        box(canvas, fx - 3, fy - 3, fx + cellSize + 1, fy + cellSize + 1, 8, Color.TRANSPARENT,
                0x5538e1c8, 8);
        box(canvas, fx - 1, fy - 1, fx + cellSize - 1, fy + cellSize - 1, 6, Color.TRANSPARENT, ACCENT, 3);
        text(canvas, getContext().getString(R.string.cursor_position, row + 1, col + 1),
                gameRight - 6, DESIGN_HEIGHT - edge - 9, 22, DIM, false, false, Paint.Align.RIGHT);
    }
    private void drawFooter(Canvas canvas) {
        int color = engine.getState() == GameEngine.State.LOST ? RED : ACCENT;
        float baseline = DESIGN_HEIGHT - edge - 9;
        circle(canvas, gameLeft + 7, baseline - 8, 4, color);
        boolean finished = engine.getState() == GameEngine.State.WON || engine.getState() == GameEngine.State.LOST;
        fitText(canvas, getContext().getString(finished ? R.string.review_hint : status),
                gameLeft + 23, baseline, gameRight - gameLeft - 270, 25, color, true);
    }

    private void drawPause(Canvas canvas) {
        float center = (gameLeft + gameRight) / 2f;
        box(canvas, gameLeft, edge, gameRight, gameBottom, 18, 0xea09111b, LINE);
        text(canvas, getContext().getString(R.string.paused_title), center, 456, 49, INK, true, true);
        text(canvas, getContext().getString(R.string.paused_sub), center, 519, 28, DIM, false, true);
        box(canvas, center - 190, 564, center + 190, 645, 13, 0xff163e3b, ACCENT, 2);
        text(canvas, getContext().getString(R.string.paused_action), center, 617, 31, ACCENT, true, true);
    }
    private void flag(Canvas canvas, float x, float y, float size, int color) {
        line(canvas, x - size * 0.3f, y - size, x - size * 0.3f, y + size, INK, 2.5f);
        line(canvas, x - size * 0.75f, y + size, x + size * 0.45f, y + size, INK, 2.5f);
        path.reset();
        path.moveTo(x - size * 0.25f, y - size);
        path.lineTo(x + size, y - size * 0.45f);
        path.lineTo(x - size * 0.25f, y + size * 0.1f);
        path.close();
        paint.setColor(color);
        canvas.drawPath(path, paint);
    }

    private void mine(Canvas canvas, float x, float y, float radius, int color) {
        for (int i = 0; i < 4; i++) {
            double angle = i * Math.PI / 4;
            float dx = (float) Math.cos(angle) * radius * 1.4f;
            float dy = (float) Math.sin(angle) * radius * 1.4f;
            line(canvas, x - dx, y - dy, x + dx, y + dy, color, Math.max(2, radius * 0.15f));
        }
        circle(canvas, x, y, radius, color);
        circle(canvas, x - radius * 0.28f, y - radius * 0.3f, radius * 0.22f, 0xffefffff);
    }

    private void centeredText(Canvas canvas, String value, float x, float y, float size, int color) {
        textPaint.setTypeface(BOLD);
        textPaint.setTextSize(size);
        textPaint.setColor(color);
        textPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(value, x, y - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint);
    }

    private void fitText(Canvas canvas, String value, float x, float y, float width, float size,
            int color, boolean bold) {
        textPaint.setTypeface(bold ? BOLD : NORMAL);
        textPaint.setTextSize(size);
        float measured = textPaint.measureText(value);
        text(canvas, value, x, y, measured > width ? size * width / measured : size, color, bold, false);
    }

    private void text(Canvas canvas, String value, float x, float y, float size, int color,
            boolean bold, boolean centered) {
        text(canvas, value, x, y, size, color, bold, centered,
                centered ? Paint.Align.CENTER : Paint.Align.LEFT);
    }

    private void text(Canvas canvas, String value, float x, float y, float size, int color,
            boolean bold, boolean centered, Paint.Align alignment) {
        textPaint.setTypeface(bold ? BOLD : NORMAL);
        textPaint.setTextSize(size);
        textPaint.setColor(color);
        textPaint.setTextAlign(alignment);
        canvas.drawText(value, x, y, textPaint);
    }

    private void circle(Canvas canvas, float x, float y, float radius, int color) {
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(x, y, radius, paint);
    }

    private void line(Canvas canvas, float x, float y, float endX, float endY, int color, float width) {
        paint.setColor(color);
        paint.setStrokeWidth(width);
        canvas.drawLine(x, y, endX, endY, paint);
    }

    private void box(Canvas canvas, float left, float top, float right, float bottom, float radius,
            int fill, int stroke) {
        box(canvas, left, top, right, bottom, radius, fill, stroke, 1);
    }

    private void box(Canvas canvas, float left, float top, float right, float bottom, float radius,
            int fill, int stroke, float strokeWidth) {
        rect.set(left, top, right, bottom);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(fill);
        canvas.drawRoundRect(rect, radius, radius, paint);
        if (stroke != Color.TRANSPARENT) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(strokeWidth);
            paint.setColor(stroke);
            canvas.drawRoundRect(rect, radius, radius, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            touchX = event.getX();
            touchY = event.getY();
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_UP) {
            if (Math.abs(event.getX() - touchX) < 30 && Math.abs(event.getY() - touchY) < 30) {
                performClick();
            }
            return true;
        }
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        if (engine == null || scale <= 0) { return true; }
        float x = (touchX - offsetX) / scale;
        float y = (touchY - offsetY) / scale;
        if (menuBounds.contains(x, y) || (x >= infoLeft && x <= infoRight && y >= 374 && y <= 567)) {
            callback.onMenuTap();
        } else if (paused) {
            callback.onResumeTap();
        } else if (x >= boardLeft && y >= boardTop && x < boardLeft + engine.getCols() * cellSize
                && y < boardTop + engine.getRows() * cellSize) {
            callback.onCellTap((int) ((y - boardTop) / cellSize), (int) ((x - boardLeft) / cellSize));
        }
        return true;
    }
}
