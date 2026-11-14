package net.runelite.client.plugins.microbot.shadowfaxgildedaltar;

import net.runelite.api.Skill;
import net.runelite.client.plugins.microbot.Microbot;

import javax.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Session counters written by the script thread and read by the overlay on the client thread.
 */
@Singleton
public class GildedAltarStats {

    private volatile Instant startedAt = Instant.now();
    private final AtomicInteger bonesOffered = new AtomicInteger();
    private final AtomicInteger trips = new AtomicInteger();
    // -1 until the first logged-in tick, so xp gained isn't measured from zero.
    private volatile int startXp = -1;
    private volatile String host = "";
    private volatile int bonesInBank = -1;
    private volatile int tabsLeft = -1;

    void reset() {
        startedAt = Instant.now();
        bonesOffered.set(0);
        trips.set(0);
        startXp = -1;
        host = "";
        bonesInBank = -1;
        tabsLeft = -1;
    }

    void captureBaselineIfNeeded() {
        if (startXp >= 0) return;
        startXp = Microbot.getClientThread()
                .runOnClientThreadOptional(() -> Microbot.getClient().getSkillExperience(Skill.PRAYER))
                .orElse(-1);
    }

    void addBonesOffered(int count) {
        if (count > 0) bonesOffered.addAndGet(count);
    }

    void addTrip() {
        trips.incrementAndGet();
    }

    void setHost(String host) {
        this.host = host == null ? "" : host;
    }

    void setSupplies(int bonesInBank, int tabsLeft) {
        this.bonesInBank = bonesInBank;
        this.tabsLeft = tabsLeft;
    }

    Duration runtime() {
        return Duration.between(startedAt, Instant.now());
    }

    int bonesOffered() {
        return bonesOffered.get();
    }

    int trips() {
        return trips.get();
    }

    int startXp() {
        return startXp;
    }

    String host() {
        return host;
    }

    int bonesInBank() {
        return bonesInBank;
    }

    int tabsLeft() {
        return tabsLeft;
    }

    /** Scales a session count to a per-hour rate; 0 during the first few seconds to avoid wild numbers. */
    long perHour(long amount) {
        long ms = runtime().toMillis();
        return ms < 5_000 ? 0 : amount * 3_600_000L / ms;
    }
}
