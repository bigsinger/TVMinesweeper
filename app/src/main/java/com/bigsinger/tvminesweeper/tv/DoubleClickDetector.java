package com.bigsinger.tvminesweeper.tv;

/** Delays a single press until the full double-press window has elapsed. */
public final class DoubleClickDetector {
    public static final long DOUBLE_CLICK_MS = 280L;

    /** Injectable monotonic clock and scheduler keep input timing independently testable. */
    public interface Scheduler {
        long now();
        void postDelayed(Runnable task, long delay);
        void remove(Runnable task);
    }

    /** Receives exactly one action for a recognized click gesture. */
    public interface Listener {
        void onSingleClick();
        void onDoubleClick();
    }

    private final Scheduler scheduler;
    private final Listener listener;
    private boolean pending;
    private long firstAt;
    private long blockedUntil;
    private final Runnable singleTask = new Runnable() {
        @Override public void run() {
            if (pending) {
                pending = false;
                listener.onSingleClick();
            }
        }
    };

    /** Creates a detector using a UI-thread scheduler in production. */
    public DoubleClickDetector(Scheduler scheduler, Listener listener) {
        this.scheduler = scheduler;
        this.listener = listener;
    }

    /** Handles a fresh key down; the caller excludes key repeats. */
    public void onPress() {
        long now = scheduler.now();
        if (now < blockedUntil) {
            return;
        }
        if (pending && now - firstAt <= DOUBLE_CLICK_MS) {
            scheduler.remove(singleTask);
            pending = false;
            blockedUntil = now + DOUBLE_CLICK_MS;
            listener.onDoubleClick();
            return;
        }
        if (pending) {
            scheduler.remove(singleTask);
            pending = false;
            listener.onSingleClick();
        }
        firstAt = now;
        pending = true;
        // One extra millisecond keeps a press at the inclusive 280ms boundary eligible.
        scheduler.postDelayed(singleTask, DOUBLE_CLICK_MS + 1L);
    }

    /** Cancels pending input when moving, opening a dialog or leaving the activity. */
    public void cancel() {
        pending = false;
        blockedUntil = 0L;
        scheduler.remove(singleTask);
    }
}
