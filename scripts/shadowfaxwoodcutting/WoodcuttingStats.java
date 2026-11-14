package net.runelite.client.plugins.microbot.shadowfaxwoodcutting;

import net.runelite.api.Skill;
import net.runelite.client.plugins.microbot.Microbot;

import javax.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/** Session counters and the current plan, written by the script thread and read by the overlay. */
@Singleton
public class WoodcuttingStats {

    private volatile Instant startedAt = Instant.now();
    private final AtomicInteger logs = new AtomicInteger();
    private final AtomicInteger nests = new AtomicInteger();
    private final AtomicInteger trips = new AtomicInteger();
    private final AtomicInteger axesBought = new AtomicInteger();
    // -1 until the first logged-in tick, so xp gained isn't measured from zero.
    private volatile int startXp = -1;

    private volatile String tree = "-";
    private volatile String spot = "-";
    private volatile String axe = "-";
    private volatile String mode = "-";

    void reset() {
        startedAt = Instant.now();
        logs.set(0);
        nests.set(0);
        trips.set(0);
        axesBought.set(0);
        startXp = -1;
        tree = spot = axe = mode = "-";
    }

    void captureBaselineIfNeeded() {
        if (startXp >= 0) return;
        startXp = Microbot.getClientThread()
                .runOnClientThreadOptional(() -> Microbot.getClient().getSkillExperience(Skill.WOODCUTTING))
                .orElse(-1);
    }

    void addLogs(int n) {
        if (n > 0) logs.addAndGet(n);
    }

    void addNest() {
        nests.incrementAndGet();
    }

    void addTrip() {
        trips.incrementAndGet();
    }

    void addAxeBought() {
        axesBought.incrementAndGet();
    }

    void setPlan(String tree, String spot, String axe, String mode) {
        this.tree = tree;
        this.spot = spot;
        this.axe = axe;
        this.mode = mode;
    }

    Duration runtime() {
        return Duration.between(startedAt, Instant.now());
    }

    int logs() {
        return logs.get();
    }

    int nests() {
        return nests.get();
    }

    int trips() {
        return trips.get();
    }

    int axesBought() {
        return axesBought.get();
    }

    int startXp() {
        return startXp;
    }

    String tree() {
        return tree;
    }

    String spot() {
        return spot;
    }

    String axe() {
        return axe;
    }

    String mode() {
        return mode;
    }

    /** Scales a session count to a per-hour rate; 0 during the first few seconds to avoid wild numbers. */
    long perHour(long amount) {
        long ms = runtime().toMillis();
        return ms < 5_000 ? 0 : amount * 3_600_000L / ms;
    }
}
