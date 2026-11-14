package net.runelite.client.plugins.microbot.shadowfaxslayer;

import net.runelite.api.Skill;
import net.runelite.client.plugins.microbot.Microbot;

import javax.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Session counters and the current plan, written by the script thread and read by the overlay. */
@Singleton
public class SlayerStats {

    private volatile Instant startedAt = Instant.now();
    private final AtomicInteger kills = new AtomicInteger();
    private final AtomicInteger tasksDone = new AtomicInteger();
    private final AtomicInteger skips = new AtomicInteger();
    private final AtomicInteger trips = new AtomicInteger();
    private final AtomicLong lootValue = new AtomicLong();
    private final AtomicLong spent = new AtomicLong();
    private volatile int startXp = -1;

    private volatile String task = "-";
    private volatile int remaining;
    private volatile int assigned;
    private volatile String master = "-";
    private volatile String spot = "-";
    private volatile String style = "-";

    void reset() {
        startedAt = Instant.now();
        kills.set(0);
        tasksDone.set(0);
        skips.set(0);
        trips.set(0);
        lootValue.set(0);
        spent.set(0);
        startXp = -1;
        task = master = spot = style = "-";
        remaining = assigned = 0;
    }

    void captureBaselineIfNeeded() {
        if (startXp >= 0) return;
        startXp = Microbot.getClientThread()
                .runOnClientThreadOptional(() -> Microbot.getClient().getSkillExperience(Skill.SLAYER))
                .orElse(-1);
    }

    void addKills(int n) {
        if (n > 0) kills.addAndGet(n);
    }

    void addTaskDone() {
        tasksDone.incrementAndGet();
    }

    void addSkip() {
        skips.incrementAndGet();
    }

    void addTrip() {
        trips.incrementAndGet();
    }

    void addLoot(long value) {
        if (value > 0) lootValue.addAndGet(value);
    }

    void addSpent(long gp) {
        if (gp > 0) spent.addAndGet(gp);
    }

    void setTask(String task, int remaining, int assigned) {
        this.task = task;
        this.remaining = remaining;
        this.assigned = Math.max(assigned, remaining);
    }

    void setPlan(String master, String spot, String style) {
        this.master = master;
        this.spot = spot;
        this.style = style;
    }

    Duration runtime() {
        return Duration.between(startedAt, Instant.now());
    }

    int kills() {
        return kills.get();
    }

    int tasksDone() {
        return tasksDone.get();
    }

    int skips() {
        return skips.get();
    }

    int trips() {
        return trips.get();
    }

    long lootValue() {
        return lootValue.get();
    }

    long spent() {
        return spent.get();
    }

    int startXp() {
        return startXp;
    }

    String task() {
        return task;
    }

    int remaining() {
        return remaining;
    }

    int assigned() {
        return assigned;
    }

    String master() {
        return master;
    }

    String spot() {
        return spot;
    }

    String style() {
        return style;
    }

    long perHour(long amount) {
        long ms = runtime().toMillis();
        return ms < 5_000 ? 0 : amount * 3_600_000L / ms;
    }
}
