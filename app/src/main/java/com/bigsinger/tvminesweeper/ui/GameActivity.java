package com.bigsinger.tvminesweeper.ui;

import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

import com.bigsinger.tvminesweeper.R;
import com.bigsinger.tvminesweeper.data.RankRepository;
import com.bigsinger.tvminesweeper.data.SettingsRepository;
import com.bigsinger.tvminesweeper.game.Cell;
import com.bigsinger.tvminesweeper.game.GameEngine;
import com.bigsinger.tvminesweeper.tv.BoardView;
import com.bigsinger.tvminesweeper.tv.DoubleClickDetector;
import com.bigsinger.tvminesweeper.tv.KeyHandler;
import com.bigsinger.tvminesweeper.util.SoundEffects;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 组织电视界面、遥控器输入、对局计时及后台存储，棋盘规则由独立引擎负责。 */
public final class GameActivity extends Activity implements BoardView.Callback, KeyHandler.Target {
    private static final String TAG = "TVMinesweeper";
    private static final long FRAME_INTERVAL_MS = 250L;
    private static final long SAVE_DELAY_MS = 400L;
    private static final long PERIODIC_SAVE_MS = 3000L;
    private static final long MAX_ELAPSED_MS = 365L * 24L * 60L * 60L * 1000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService storage = Executors.newSingleThreadExecutor();
    private final Object callbackLock = new Object();
    private volatile boolean destroyed;
    private BoardView board;
    private KeyHandler keys;
    private DoubleClickDetector clicks;
    private SoundEffects sounds;
    private SettingsRepository settings;
    private RankRepository rankings;
    private GameEngine engine;
    private DifficultyDialog modal;
    private int modalGeneration;
    private int row;
    private int col;
    private int status = R.string.ready_status;
    private long elapsedMillis;
    private long runningSince = -1L;
    private long lastSavedAt;
    private boolean resumed;
    private boolean paused;
    private boolean soundEnabled = true;
    private boolean resultHandled;

    private final Runnable saveTask = new Runnable() {
        @Override public void run() {
            saveNow();
        }
    };
    private final Runnable tickTask = new Runnable() {
        @Override public void run() {
            if (destroyed || !resumed) {
                return;
            }
            render();
            if (engine != null && engine.getState() == GameEngine.State.PLAYING
                    && runningSince >= 0 && SystemClock.uptimeMillis() - lastSavedAt >= PERIODIC_SAVE_MS) {
                saveNow();
            }
            handler.postDelayed(this, FRAME_INTERVAL_MS);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        keys = new KeyHandler(this);
        clicks = new DoubleClickDetector(new DoubleClickDetector.Scheduler() {
            @Override public long now() { return SystemClock.uptimeMillis(); }
            @Override public void postDelayed(Runnable task, long delay) { handler.postDelayed(task, delay); }
            @Override public void remove(Runnable task) { handler.removeCallbacks(task); }
        }, new DoubleClickDetector.Listener() {
            @Override public void onSingleClick() { openCurrentCell(); }
            @Override public void onDoubleClick() { toggleCurrentFlag(); }
        });
        sounds = new SoundEffects(getApplicationContext());
        board = new BoardView(this, this);
        setContentView(board);
        board.requestFocus();
        hideSystemUi();
        loadSavedState();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        sounds.resume();
        hideSystemUi();
        handler.removeCallbacks(tickTask);
        handler.post(tickTask);
        render();
    }

    @Override protected void onPause() {
        resumed = false;
        clicks.cancel();
        stopClock();
        if (engine != null && engine.getState() == GameEngine.State.PLAYING) {
            paused = true;
        }
        handler.removeCallbacks(tickTask);
        saveNow();
        sounds.pause();
        render();
        super.onPause();
    }

    @Override protected void onDestroy() {
        stopClock();
        saveNow();
        closeModal(false);
        clicks.cancel();
        synchronized (callbackLock) {
            destroyed = true;
            handler.removeCallbacksAndMessages(null);
        }
        sounds.release();
        // 已提交的最终保存仍可完成；不再接受新任务或回调已销毁的界面。
        storage.shutdown();
        super.onDestroy();
    }

    /** 所有遥控器按键从这里交给同一个分发器，弹层也复用该分发器。 */
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        return keys != null && keys.dispatch(event) || super.dispatchKeyEvent(event);
    }

    /** 外部窗口抢走焦点时暂停；应用自己的菜单由弹层计时策略负责。 */
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        } else if (modal == null) {
            clicks.cancel();
            if (engine != null && engine.getState() == GameEngine.State.PLAYING) {
                stopClock();
                paused = true;
                saveNow();
                render();
            }
        }
    }

    /** 移动菜单选项或棋盘光标；边缘保持原位，不跳出棋盘。 */
    @Override public void move(int rowDelta, int columnDelta) {
        clicks.cancel();
        if (modal != null) {
            if (rowDelta != 0) {
                modal.move(rowDelta);
                sounds.play(SoundEffects.MOVE);
            }
            return;
        }
        if (engine == null || paused || !resumed) {
            return;
        }
        int nextRow = Math.max(0, Math.min(engine.getRows() - 1, row + rowDelta));
        int nextCol = Math.max(0, Math.min(engine.getCols() - 1, col + columnDelta));
        if (nextRow != row || nextCol != col) {
            row = nextRow;
            col = nextCol;
            sounds.play(SoundEffects.MOVE);
            scheduleSave();
            render();
        }
    }

    /** 弹层立即确认；暂停时恢复；正常棋盘等待完整双击窗口后再翻开。 */
    @Override public void confirm() {
        if (modal != null) {
            clicks.cancel();
            modal.confirm();
        } else if (engine != null && resumed) {
            if (paused) {
                onResumeTap();
            } else {
                clicks.onPress();
            }
        }
    }

    /** 音量键或 OK 双击切换当前旗帜，暂停和弹层期间不修改棋盘。 */
    @Override public void flag() {
        clicks.cancel();
        toggleCurrentFlag();
    }

    private void toggleCurrentFlag() {
        if (modal != null || engine == null || paused || !resumed || isFinished()) {
            return;
        }
        if (engine.toggleFlag(row, col)) {
            boolean flagged = engine.getCell(row, col).isFlagged();
            status = flagged ? R.string.flagged_status : R.string.unflagged_status;
            sounds.play(flagged ? SoundEffects.FLAG : SoundEffects.UNFLAG);
            scheduleSave();
            render();
        }
    }

    /** MENU 打开或关闭难度与设置弹层，游戏计时在弹层期间暂停。 */
    @Override public void menu() {
        clicks.cancel();
        if (modal != null) {
            closeModal(true);
        } else if (engine != null && resumed) {
            showMenu();
        }
    }

    /** 返回先关闭当前弹层，否则显示默认选中“继续”的保存退出确认。 */
    @Override public void back() {
        clicks.cancel();
        if (modal != null) {
            closeModal(true);
        } else if (engine == null) {
            finish();
        } else {
            showExit();
        }
    }

    /** 鼠标和触屏点击也经过相同的延迟单击与双击规则。 */
    @Override public void onCellTap(int targetRow, int targetCol) {
        if (engine == null || modal != null || !resumed || engine.getCell(targetRow, targetCol) == null) {
            return;
        }
        if (paused) {
            onResumeTap();
            return;
        }
        if (row != targetRow || col != targetCol) {
            clicks.cancel();
            row = targetRow;
            col = targetCol;
            scheduleSave();
            render();
        }
        confirm();
    }

    /** 点击右上角难度卡片打开同一菜单。 */
    @Override public void onMenuTap() {
        menu();
    }

    /** 用户明确恢复后才继续计时，后台返回不会直接触发翻格。 */
    @Override public void onResumeTap() {
        clicks.cancel();
        if (engine != null && paused && resumed && modal == null) {
            paused = false;
            startClock();
            sounds.play(SoundEffects.MENU);
            render();
        }
    }

    private void loadSavedState() {
        final Context application = getApplicationContext();
        storage.execute(new Runnable() {
            @Override public void run() {
                final LoadedState loaded = new LoadedState();
                try {
                    loaded.settings = new SettingsRepository(application);
                    loaded.rankings = new RankRepository(application);
                    loaded.difficulty = loaded.settings.getDifficulty();
                    loaded.sound = loaded.settings.isSoundEnabled();
                    loaded.saved = loaded.settings.loadGame();
                    loaded.entries = loaded.rankings.getEntries();
                    if (loaded.saved != null) {
                        loaded.engine = GameEngine.restore(loaded.saved.engineState);
                        if (loaded.engine == null || loaded.engine.getState() == GameEngine.State.WON
                                || loaded.engine.getState() == GameEngine.State.LOST) {
                            loaded.engine = null;
                            loaded.settings.clearGame();
                        }
                    }
                } catch (RuntimeException exception) {
                    Log.e(TAG, "Unable to load local state; opening a new game", exception);
                }
                postToUi(new Runnable() {
                    @Override public void run() {
                        settings = loaded.settings;
                        rankings = loaded.rankings;
                        soundEnabled = loaded.sound;
                        sounds.setEnabled(soundEnabled);
                        board.setRanks(loaded.entries, null);
                        if (loaded.engine != null) {
                            engine = loaded.engine;
                            row = Math.max(0, Math.min(engine.getRows() - 1, loaded.saved.row));
                            col = Math.max(0, Math.min(engine.getCols() - 1, loaded.saved.col));
                            boolean playing = engine.getState() == GameEngine.State.PLAYING;
                            elapsedMillis = playing ? Math.min(MAX_ELAPSED_MS, loaded.saved.elapsedMillis) : 0L;
                            paused = playing;
                            status = playing ? R.string.playing_status : R.string.ready_status;
                            render();
                        } else {
                            newGame(GameEngine.Difficulty.values()[loaded.difficulty]);
                        }
                    }
                });
            }
        });
    }

    private void newGame(GameEngine.Difficulty difficulty) {
        clicks.cancel();
        stopClock();
        closeModal(false);
        engine = new GameEngine(difficulty);
        row = 0;
        col = 0;
        elapsedMillis = 0L;
        runningSince = -1L;
        paused = false;
        resultHandled = false;
        status = R.string.ready_status;
        persistPreferences();
        scheduleSave();
        render();
    }

    private void openCurrentCell() {
        if (engine == null || modal != null || paused || !resumed || destroyed) {
            return;
        }
        if (isFinished()) {
            newGame(engine.getDifficulty());
            return;
        }
        Cell cell = engine.getCell(row, col);
        if (cell.isFlagged()) {
            status = R.string.flag_first_status;
            render();
            return;
        }
        int opened;
        if (cell.isOpened()) {
            status = R.string.chord_status;
            opened = engine.chord(row, col);
        } else {
            opened = engine.openCell(row, col);
        }
        if (isFinished()) {
            finishGame();
        } else {
            startClock();
            if (opened > 0) {
                status = R.string.playing_status;
                sounds.play(SoundEffects.OPEN);
            }
            scheduleSave();
            render();
        }
    }

    private void finishGame() {
        if (resultHandled) {
            return;
        }
        resultHandled = true;
        clicks.cancel();
        stopClock();
        boolean won = engine.getState() == GameEngine.State.WON;
        status = won ? R.string.won_status : R.string.lost_status;
        sounds.play(won ? SoundEffects.WIN : SoundEffects.LOSE);
        saveNow();
        if (won && rankings != null) {
            final RankRepository repository = rankings;
            final int difficulty = engine.getDifficulty().ordinal();
            final long seconds = elapsedMillis / 1000L;
            final int safeCells = engine.getOpenedCount();
            storage.execute(new Runnable() {
                @Override public void run() {
                    final String record = repository.addWin(difficulty, seconds, safeCells);
                    final List<RankRepository.Entry> entries = repository.getEntries();
                    postToUi(new Runnable() {
                        @Override public void run() { board.setRanks(entries, record); }
                    });
                }
            });
        }
        render();
        showResult(won);
    }

    private void showMenu() {
        sounds.play(SoundEffects.MENU);
        String[] options = new String[6];
        for (int index = 0; index < 3; index++) {
            GameEngine.Difficulty difficulty = GameEngine.Difficulty.values()[index];
            options[index] = getString(BoardView.difficultyLabel(index)) + "    "
                    + getString(R.string.difficulty_meta, difficulty.cols, difficulty.rows, difficulty.mines);
        }
        options[3] = getString(soundEnabled ? R.string.sound_on : R.string.sound_off);
        options[4] = getString(R.string.help_title);
        options[5] = getString(R.string.continue_game);
        showModal(getString(R.string.menu_title), getString(R.string.menu_sub), options,
                getString(R.string.menu_hint), engine.getDifficulty().ordinal(), new Selection() {
                    @Override public void select(int position) {
                        if (position < 3) {
                            newGame(GameEngine.Difficulty.values()[position]);
                        } else if (position == 3) {
                            soundEnabled = !soundEnabled;
                            sounds.setEnabled(soundEnabled);
                            sounds.play(SoundEffects.MENU);
                            modal.setOption(3, getString(soundEnabled ? R.string.sound_on : R.string.sound_off));
                            persistPreferences();
                            render();
                        } else if (position == 4) {
                            showHelp();
                        } else {
                            closeModal(true);
                        }
                    }
                });
    }

    private void showHelp() {
        int[] helpLines = {R.string.help_1, R.string.help_2, R.string.help_3, R.string.help_4,
                R.string.help_5, R.string.help_6, R.string.help_7};
        StringBuilder content = new StringBuilder();
        for (int line : helpLines) {
            if (content.length() > 0) {
                content.append('\n');
            }
            content.append(getString(line));
        }
        showModal(getString(R.string.help_title), content.toString(),
                new String[]{getString(R.string.help_close)}, getString(R.string.menu_hint), 0,
                new Selection() {
                    @Override public void select(int position) { closeModal(true); }
                });
    }

    private void showExit() {
        showModal(getString(R.string.exit_title), getString(R.string.exit_sub),
                new String[]{getString(R.string.continue_game), getString(R.string.exit_action)},
                getString(R.string.menu_hint), 0, new Selection() {
                    @Override public void select(int position) {
                        if (position == 0) {
                            closeModal(true);
                        } else {
                            closeModal(false);
                            saveNow();
                            finish();
                        }
                    }
                });
    }

    private void showResult(boolean won) {
        String duration = BoardView.formatTime(elapsedMillis);
        int score = RankRepository.calculateScore(engine.getDifficulty().ordinal(), elapsedMillis / 1000L,
                engine.getOpenedCount());
        String subtitle = won ? getString(R.string.result_win, score, duration)
                : getString(R.string.result_lose, engine.getOpenedCount(), duration);
        showModal(getString(won ? R.string.win_title : R.string.lose_title), subtitle,
                new String[]{getString(R.string.play_again), getString(R.string.inspect_board)},
                getString(R.string.menu_hint), 0, new Selection() {
                    @Override public void select(int position) {
                        if (position == 0) {
                            newGame(engine.getDifficulty());
                        } else {
                            closeModal(true);
                        }
                    }
                });
    }

    private void showModal(String title, String subtitle, String[] options, String hint,
            int initial, final Selection selection) {
        clicks.cancel();
        stopClock();
        closeModal(false);
        if (destroyed || isFinishing()) {
            return;
        }
        final int generation = ++modalGeneration;
        modal = new DifficultyDialog(this, title, subtitle, options, hint, initial, keys,
                new DifficultyDialog.Listener() {
                    @Override public void onSelected(int position) {
                        if (!destroyed && modalGeneration == generation) {
                            selection.select(position);
                        }
                    }
                    @Override public void onClosed() {
                        if (!destroyed && modalGeneration == generation) {
                            modal = null;
                            startClock();
                            render();
                        }
                    }
                });
        modal.show();
        scheduleSave();
        render();
    }

    private void closeModal(boolean resumeClock) {
        if (modal != null) {
            DifficultyDialog previous = modal;
            modal = null;
            modalGeneration++;
            // dismiss 可能异步通知旧监听器，先解绑避免它清除随后显示的新弹层。
            previous.setOnDismissListener(null);
            previous.dismiss();
        }
        if (resumeClock) {
            startClock();
            board.requestFocus();
            hideSystemUi();
            render();
        }
    }

    private void startClock() {
        if (engine != null && engine.getState() == GameEngine.State.PLAYING && resumed
                && !paused && modal == null && runningSince < 0L) {
            runningSince = SystemClock.uptimeMillis();
        }
    }

    private void stopClock() {
        if (runningSince >= 0L) {
            elapsedMillis = currentElapsed();
            runningSince = -1L;
        }
    }

    private long currentElapsed() {
        long additional = runningSince >= 0L ? Math.max(0L, SystemClock.uptimeMillis() - runningSince) : 0L;
        return Math.min(MAX_ELAPSED_MS, elapsedMillis + additional);
    }

    private boolean isFinished() {
        return engine.getState() == GameEngine.State.WON || engine.getState() == GameEngine.State.LOST;
    }

    private void render() {
        if (board != null && !destroyed) {
            board.update(engine, row, col, currentElapsed(), status, paused, soundEnabled);
        }
    }

    private void scheduleSave() {
        handler.removeCallbacks(saveTask);
        if (!destroyed) {
            handler.postDelayed(saveTask, SAVE_DELAY_MS);
        }
    }

    private void saveNow() {
        handler.removeCallbacks(saveTask);
        if (destroyed || engine == null || settings == null) {
            return;
        }
        lastSavedAt = SystemClock.uptimeMillis();
        final SettingsRepository repository = settings;
        final boolean finished = isFinished();
        final String snapshot = finished ? null : engine.serialize();
        final long elapsed = currentElapsed();
        final int savedRow = row;
        final int savedCol = col;
        storage.execute(new Runnable() {
            @Override public void run() {
                if (finished) {
                    repository.clearGame();
                } else {
                    repository.saveGame(snapshot, elapsed, savedRow, savedCol);
                }
            }
        });
    }

    private void persistPreferences() {
        if (settings == null || destroyed) {
            return;
        }
        final SettingsRepository repository = settings;
        final int difficulty = engine.getDifficulty().ordinal();
        final boolean enabled = soundEnabled;
        storage.execute(new Runnable() {
            @Override public void run() {
                repository.setDifficulty(difficulty);
                repository.setSoundEnabled(enabled);
            }
        });
    }

    private void postToUi(final Runnable callback) {
        synchronized (callbackLock) {
            if (!destroyed) {
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (!destroyed && !isFinishing()) {
                            callback.run();
                        }
                    }
                });
            }
        }
    }

    private void hideSystemUi() {
        int flags = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            flags |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private interface Selection {
        void select(int position);
    }

    private static final class LoadedState {
        SettingsRepository settings;
        RankRepository rankings;
        SettingsRepository.SavedGame saved;
        GameEngine engine;
        List<RankRepository.Entry> entries = Collections.emptyList();
        int difficulty;
        boolean sound = true;
    }
}
