package net.runelite.client.plugins.microbot.shadowfaxectofuntus;

import net.runelite.api.Skill;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.ectofuntus.Ectofuntus;

import javax.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Session counters and a supplies snapshot, written by the script thread and read by the overlay on the client
 * thread.
 */
@Singleton
public class EctofuntusStats {

    private volatile Instant startedAt = Instant.now();
    private final AtomicInteger worships = new AtomicInteger();
    private final AtomicInteger tokensClaimed = new AtomicInteger();
    // -1 until the first logged-in tick, so xp gained isn't measured from zero.
    private volatile int startXp = -1;

    // Supplies, refreshed by the script each loop.
    private volatile int bones = -1;
    private volatile int pots = -1;
    private volatile int bonemeal = -1;
    private volatile int buckets = -1;
    private volatile int slime = -1;
    private volatile int unclaimed = -1;

    void reset() {
        startedAt = Instant.now();
        worships.set(0);
        tokensClaimed.set(0);
        startXp = -1;
        bones = pots = bonemeal = buckets = slime = unclaimed = -1;
    }

    void captureBaselineIfNeeded() {
        if (startXp >= 0) return;
        startXp = Microbot.getClientThread()
                .runOnClientThreadOptional(() -> Microbot.getClient().getSkillExperience(Skill.PRAYER))
                .orElse(-1);
    }

    /** Call from the script thread. */
    void refreshSupplies() {
        bones = Ectofuntus.bones();
        pots = Ectofuntus.pots();
        bonemeal = Ectofuntus.bonemeal();
        buckets = Ectofuntus.buckets();
        slime = Ectofuntus.slime();
        unclaimed = Ectofuntus.unclaimed();
    }

    void addWorships(int count) {
        if (count > 0) worships.addAndGet(count);
    }

    void addTokensClaimed(int count) {
        if (count > 0) tokensClaimed.addAndGet(count);
    }

    Duration runtime() {
        return Duration.between(startedAt, Instant.now());
    }

    int worships() {
        return worships.get();
    }

    int tokensClaimed() {
        return tokensClaimed.get();
    }

    int startXp() {
        return startXp;
    }

    int bones() {
        return bones;
    }

    int pots() {
        return pots;
    }

    int bonemeal() {
        return bonemeal;
    }

    int buckets() {
        return buckets;
    }

    int slime() {
        return slime;
    }

    int unclaimed() {
        return unclaimed;
    }

    /** Scales a session count to a per-hour rate; 0 during the first few seconds to avoid wild numbers. */
    long perHour(long amount) {
        long ms = runtime().toMillis();
        return ms < 5_000 ? 0 : amount * 3_600_000L / ms;
    }
}
