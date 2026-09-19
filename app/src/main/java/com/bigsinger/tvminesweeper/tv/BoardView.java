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
                getContext().getString(cellText));
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
        box(canvas, 96, 54, 300, 1026, 18, PANEL, LINE);
        fitText(canvas, getContext().getString(R.string.rank_title), 114, 103, 168, 26, INK, true);
        fitText(canvas, getContext().getString(R.string.rank_sub), 114, 136, 168, 18, DIM, false);
        line(canvas, 114, 155, 282, 155, LINE, 1);
        line(canvas, 114, 155, 174, 155, ACCENT, 3);
        if (ranks.isEmpty()) {
            mine(canvas, 198, 402, 29, ACCENT);
            fitText(canvas, getContext().getString(R.string.rank_empty), 114, 489, 168, 25, INK, true);
            text(canvas, getContext().getString(R.string.rank_empty_sub), 198, 533, 30, ACCENT, true, true);
            fitText(canvas, getContext().getString(R.string.rank_empty_hint), 114, 591, 168, 20, DIM, false);
            for (int i = 0; i < 3; i++) {
                box(canvas, 137 + i * 45, 645, 169 + i * 45, 677, 6, 0xff1e2b38, LINE);
            }
        } else {
            for (int i = 0; i < ranks.size() && i < 8; i++) {
                RankRepository.Entry entry = ranks.get(i);
                float top = 172 + i * 91;
                boolean fresh = entry.id.equals(newRecord) && SystemClock.uptimeMillis() < flashUntil;
                int medal = i == 0 ? GOLD : i == 1 ? 0xffc9d4e0 : i == 2 ? 0xffd6a071 : DIM;
                box(canvas, 108, top, 288, top + 84, 10, i == 0 ? 0xff25231c : 0xff15202a,
                        fresh ? ACCENT : i < 3 ? 0xff454235 : LINE);
                text(canvas, String.valueOf(i + 1), 128, top + 31, 22, medal, true, true);
                text(canvas, String.valueOf(entry.score), 149, top + 31, 28, ACCENT, true, false);
                String info = getContext().getString(difficultyLabel(entry.difficulty)) + " · "
                        + formatTime(entry.seconds * 1000L);
                fitText(canvas, info, 123, top + 56, 151, 21, DIM, false);
                text(canvas, dateFormat.format(new Date(entry.date)), 123, top + 77, 18, DIM, false, false);
                if (fresh) { postInvalidateDelayed(100L); }
            }
        }
        line(canvas, 114, 934, 282, 934, LINE, 1);
        String[] footer = getContext().getString(R.string.rank_footer).split("\n");
        for (int i = 0; i < footer.length; i++) {
            fitText(canvas, footer[i], 114, 969 + i * 29, 168, 20, DIM, false);
        }
    }

    private void drawHeader(Canvas canvas) {
        box(canvas, 316, 54, 492, 1026, 18, PANEL, LINE);
        fitText(canvas, getContext().getString(R.string.brand), 334, 104, 140, 27, INK, true);
        line(canvas, 334, 128, 474, 128, LINE, 1);
        text(canvas, getContext().getString(R.string.mines_left), 404, 173, 22, DIM, false, true);
        text(canvas, String.format(Locale.US, "%03d", engine.getMineCount() - engine.getFlagCount()),
                404, 230, 53, GOLD, true, true);
        text(canvas, getContext().getString(R.string.time_label), 404, 289, 22, DIM, false, true);
        fitText(canvas, formatTime(elapsed), 334, 340, 140, 42, ACCENT, true);
        line(canvas, 334, 374, 474, 374, LINE, 1);
        text(canvas, getContext().getString(difficultyLabel(engine.getDifficulty().ordinal())),
                404, 420, 29, INK, true, true);
        text(canvas, engine.getCols() + " × " + engine.getRows(), 404, 458, 24, DIM, false, true);
        text(canvas, getContext().getString(R.string.mine_total, engine.getMineCount()),
                404, 491, 22, DIM, false, true);
        box(canvas, 339, 512, 469, 559, 9, 0xff173b3b, 0xff358c81);
        text(canvas, getContext().getString(R.string.menu_key), 404, 544, 23, ACCENT, true, true);
        int safeCells = engine.getRows() * engine.getCols() - engine.getMineCount();
        text(canvas, getContext().getString(R.string.safe_cells), 404, 615, 22, DIM, false, true);
        text(canvas, engine.getOpenedCount() + " / " + safeCells, 404, 656, 28, INK, true, true);
        float progress = engine.getOpenedCount() / (float) safeCells;
        box(canvas, 337, 676, 471, 681, 2, LINE, Color.TRANSPARENT);
        if (progress > 0) {
            box(canvas, 337, 676, 337 + 134 * progress, 681, 2, ACCENT, Color.TRANSPARENT);
        }
        line(canvas, 334, 718, 474, 718, LINE, 1);
        int[] tips = {R.string.side_move, R.string.side_open, R.string.side_flag, R.string.side_volume};
        for (int i = 0; i < tips.length; i++) {
            fitText(canvas, getContext().getString(tips[i]), 332, 765 + i * 44, 144, 23, DIM, false);
        }
        fitText(canvas, getContext().getString(soundEnabled ? R.string.sound_on : R.string.sound_off),
                334, 967, 140, 20, DIM, false);
        fitText(canvas, getContext().getString(R.string.side_back), 334, 1001, 140, 20, DIM, false);
    }
    private void drawBoard(Canvas canvas) {
        int rows = engine.getRows();
        int cols = engine.getCols();
        cellSize = Math.min(100f, Math.min(1280f / cols, 896f / rows));
        float width = cols * cellSize;
        float height = rows * cellSize;
        boardLeft = 1168 - width / 2f;
        boardTop = 518 - height / 2f;
        box(canvas, boardLeft - 16, boardTop - 16, boardLeft + width + 16, boardTop + height + 16,
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
                            Math.min(48, cellSize * 0.77f), NUMBERS[cell.getAdjacentMines()]);
                }
            }
        }
        float fx = boardLeft + col * cellSize;
        float fy = boardTop + row * cellSize;
        box(canvas, fx - 3, fy - 3, fx + cellSize + 1, fy + cellSize + 1, 8, Color.TRANSPARENT,
                0x5538e1c8, 8);
        box(canvas, fx - 1, fy - 1, fx + cellSize - 1, fy + cellSize - 1, 6, Color.TRANSPARENT, ACCENT, 3);
        text(canvas, getContext().getString(R.string.cursor_position, row + 1, col + 1),
                1824, 1017, 21, DIM, false, false, Paint.Align.RIGHT);
    }
    private void drawFooter(Canvas canvas) {
        int color = engine.getState() == GameEngine.State.LOST ? RED : ACCENT;
        circle(canvas, 521, 1009, 4, color);
        boolean finished = engine.getState() == GameEngine.State.WON || engine.getState() == GameEngine.State.LOST;
        fitText(canvas, getContext().getString(finished ? R.string.review_hint : status),
                537, 1017, 1000, 24, color, true);
    }
    private void drawPause(Canvas canvas) {
        box(canvas, 512, 54, 1824, 982, 18, 0xea09111b, LINE);
        text(canvas, getContext().getString(R.string.paused_title), 1168, 456, 47, INK, true, true);
        text(canvas, getContext().getString(R.string.paused_sub), 1168, 515, 25, DIM, false, true);
        box(canvas, 978, 560, 1358, 637, 13, 0xff163e3b, ACCENT, 2);
        text(canvas, getContext().getString(R.string.paused_action), 1168, 609, 29, ACCENT, true, true);
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
        if (x >= 316 && x <= 492 && y >= 374 && y <= 559) {
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
