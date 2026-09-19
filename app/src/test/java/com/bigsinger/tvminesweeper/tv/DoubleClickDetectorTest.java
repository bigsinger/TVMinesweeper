package com.bigsinger.tvminesweeper.tv;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.Assert.assertEquals;

/** 通过虚拟单调时钟验证遥控器点击边界，无需真实等待或 Android 环境。 */
public class DoubleClickDetectorTest {
    @Test
    public void singlePressWaitsForCompleteDoubleClickWindow() {
        Fixture fixture = new Fixture();
        fixture.detector.onPress();
        fixture.scheduler.advanceTo(240);
        assertEquals(0, fixture.singles);
        fixture.scheduler.advanceTo(360);
        assertEquals(0, fixture.singles);
        fixture.scheduler.advanceTo(361);
        assertEquals(1, fixture.singles);
        fixture.scheduler.advanceTo(1000);
        assertEquals(1, fixture.singles);
        assertEquals(0, fixture.doubles);
    }

    @Test
    public void secondPressAt340MillisecondsProducesOnlyDoubleClick() {
        assertDoubleClickAt(340);
    }

    @Test
    public void secondPressAtInclusive360MillisecondBoundaryIsDoubleClick() {
        assertDoubleClickAt(360);
    }

    @Test
    public void secondPressAt361MillisecondsProducesSeparateSingleClicks() {
        Fixture fixture = new Fixture();
        fixture.detector.onPress();
        fixture.scheduler.advanceTo(361);
        fixture.detector.onPress();
        assertEquals(1, fixture.singles);
        fixture.scheduler.advanceTo(722);
        assertEquals(2, fixture.singles);
        assertEquals(0, fixture.doubles);
    }

    @Test
    public void delayedSchedulerStillHonorsExpiredDoubleClickWindow() {
        Fixture fixture = new Fixture();
        fixture.detector.onPress();
        fixture.scheduler.time = 400;
        fixture.detector.onPress();
        assertEquals(1, fixture.singles);
        assertEquals(0, fixture.doubles);
        fixture.scheduler.advanceTo(761);
        assertEquals(2, fixture.singles);
    }

    @Test
    public void cancelDiscardsPendingClickAndAllowsFreshGesture() {
        Fixture fixture = new Fixture();
        fixture.detector.onPress();
        fixture.scheduler.advanceTo(200);
        fixture.detector.cancel();
        fixture.scheduler.advanceTo(1000);
        assertEquals(0, fixture.singles);
        assertEquals(0, fixture.doubles);
        fixture.detector.onPress();
        fixture.scheduler.advanceTo(1361);
        assertEquals(1, fixture.singles);
    }

    @Test
    public void rapidTriplePressDoesNotAlsoToggleFlag() {
        Fixture fixture = new Fixture();
        fixture.detector.onPress();
        fixture.scheduler.advanceTo(100);
        fixture.detector.onPress();
        fixture.scheduler.advanceTo(200);
        fixture.detector.onPress();
        fixture.scheduler.advanceTo(1000);
        assertEquals(1, fixture.doubles);
        assertEquals(0, fixture.singles);
    }

    @Test
    public void newGestureAtCooldownBoundaryIsAccepted() {
        Fixture fixture = new Fixture();
        fixture.detector.onPress();
        fixture.scheduler.advanceTo(100);
        fixture.detector.onPress();
        fixture.scheduler.advanceTo(460);
        fixture.detector.onPress();
        fixture.scheduler.advanceTo(821);
        assertEquals(1, fixture.doubles);
        assertEquals(1, fixture.singles);
    }

    @Test
    public void cancelAlsoClearsCooldownWhenContextChanges() {
        Fixture fixture = new Fixture();
        fixture.detector.onPress();
        fixture.scheduler.advanceTo(100);
        fixture.detector.onPress();
        fixture.detector.cancel();
        fixture.scheduler.advanceTo(150);
        fixture.detector.onPress();
        fixture.scheduler.advanceTo(511);
        assertEquals(1, fixture.doubles);
        assertEquals(1, fixture.singles);
    }

    private static void assertDoubleClickAt(long secondAt) {
        Fixture fixture = new Fixture();
        fixture.detector.onPress();
        fixture.scheduler.advanceTo(secondAt);
        fixture.detector.onPress();
        assertEquals(1, fixture.doubles);
        fixture.scheduler.advanceTo(1000);
        assertEquals(0, fixture.singles);
        assertEquals(1, fixture.doubles);
    }

    private static final class Fixture implements DoubleClickDetector.Listener {
        final FakeScheduler scheduler = new FakeScheduler();
        final DoubleClickDetector detector = new DoubleClickDetector(scheduler, this);
        int singles;
        int doubles;

        @Override
        public void onSingleClick() {
            singles++;
        }

        @Override
        public void onDoubleClick() {
            doubles++;
        }
    }

    private static final class FakeScheduler implements DoubleClickDetector.Scheduler {
        long time;
        final List<ScheduledTask> tasks = new ArrayList<ScheduledTask>();

        @Override
        public long now() {
            return time;
        }

        @Override
        public void postDelayed(Runnable task, long delay) {
            tasks.add(new ScheduledTask(task, time + delay));
        }

        @Override
        public void remove(Runnable task) {
            Iterator<ScheduledTask> iterator = tasks.iterator();
            while (iterator.hasNext()) {
                if (iterator.next().runnable == task) {
                    iterator.remove();
                }
            }
        }

        void advanceTo(long target) {
            while (true) {
                ScheduledTask next = null;
                for (ScheduledTask task : tasks) {
                    if (task.due <= target && (next == null || task.due < next.due)) {
                        next = task;
                    }
                }
                if (next == null) {
                    time = target;
                    return;
                }
                tasks.remove(next);
                time = next.due;
                next.runnable.run();
            }
        }
    }

    private static final class ScheduledTask {
        final Runnable runnable;
        final long due;

        ScheduledTask(Runnable runnable, long due) {
            this.runnable = runnable;
            this.due = due;
        }
    }
}
