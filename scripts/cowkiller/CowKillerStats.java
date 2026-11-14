package net.runelite.client.plugins.microbot.cowkiller;

import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.client.plugins.microbot.Microbot;

import javax.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Session counters shared by the plugin (kill events), the script (banking, upgrades) and the overlay.
 * Written from the script and client threads, read from the overlay on the client thread.
 */
@Singleton
public class CowKillerStats {

    static final Skill[] TRACKED_SKILLS = {Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE, Skill.HITPOINTS, Skill.COOKING};

    private volatile Instant startedAt = Instant.now();
    private final AtomicInteger cowsKilled = new AtomicInteger();
    private final AtomicInteger hidesBanked = new AtomicInteger();
    private final AtomicInteger upgrades = new AtomicInteger();
    private volatile String lastUpgrade;
    private volatile String nextUpgrade = "";
    // Null until the first logged-in tick, so xp gained isn't measured from zero.
    private volatile Map<Skill, Integer> startXp;

    void reset() {
        startedAt = Instant.now();
        cowsKilled.set(0);
        hidesBanked.set(0);
        upgrades.set(0);
        lastUpgrade = null;
        nextUpgrade = "";
        startXp = null;
    }

    /** Call from the script thread once logged in. */
    void captureBaselineIfNeeded() {
        if (startXp != null) return;
        startXp = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Client client = Microbot.getClient();
            Map<Skill, Integer> xp = new EnumMap<>(Skill.class);
            for (Skill skill : TRACKED_SKILLS) {
                xp.put(skill, client.getSkillExperience(skill));
            }
            return Collections.unmodifiableMap(xp);
        }).orElse(null);
    }

    void addCowKill() {
        cowsKilled.incrementAndGet();
    }

    void addHidesBanked(int count) {
        if (count > 0) hidesBanked.addAndGet(count);
    }

    void recordUpgrade(String itemName) {
        upgrades.incrementAndGet();
        lastUpgrade = itemName;
    }

    void setNextUpgrade(String label) {
        nextUpgrade = label;
    }

    Duration runtime() {
        return Duration.between(startedAt, Instant.now());
    }

    int cowsKilled() {
        return cowsKilled.get();
    }

    int hidesBanked() {
        return hidesBanked.get();
    }

    int upgrades() {
        return upgrades.get();
    }

    String lastUpgrade() {
        return lastUpgrade;
    }

    String nextUpgrade() {
        return nextUpgrade;
    }

    /** Starting xp for a tracked skill, or -1 before the baseline is captured. */
    int startXp(Skill skill) {
        Map<Skill, Integer> xp = startXp;
        return xp == null ? -1 : xp.getOrDefault(skill, -1);
    }

    /** Scales a session count to a per-hour rate; 0 during the first few seconds to avoid wild numbers. */
    long perHour(long amount) {
        long ms = runtime().toMillis();
        return ms < 5_000 ? 0 : amount * 3_600_000L / ms;
    }
}
