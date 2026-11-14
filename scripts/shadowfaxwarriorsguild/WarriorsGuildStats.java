package net.runelite.client.plugins.microbot.shadowfaxwarriorsguild;

import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.client.plugins.microbot.Microbot;

import javax.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Session counters shared by the plugin (death events), the script and the overlay. Written from the script and
 * client threads, read from the overlay on the client thread.
 */
@Singleton
public class WarriorsGuildStats {

    static final Skill[] TRACKED_SKILLS = {
            Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE, Skill.RANGED, Skill.MAGIC, Skill.HITPOINTS
    };

    private volatile Instant startedAt = Instant.now();
    private final AtomicInteger armourKills = new AtomicInteger();
    private final AtomicInteger cyclopsKills = new AtomicInteger();
    private final AtomicInteger killsSinceDefender = new AtomicInteger();
    private final AtomicInteger tokensEarned = new AtomicInteger();
    private final AtomicInteger tokensSpent = new AtomicInteger();
    private final AtomicInteger bankTrips = new AtomicInteger();
    private final AtomicInteger recoveries = new AtomicInteger();
    private final List<Defender> defenders = new CopyOnWriteArrayList<>();
    private volatile Map<Skill, Integer> startXp;

    void reset() {
        startedAt = Instant.now();
        armourKills.set(0);
        cyclopsKills.set(0);
        killsSinceDefender.set(0);
        tokensEarned.set(0);
        tokensSpent.set(0);
        bankTrips.set(0);
        recoveries.set(0);
        defenders.clear();
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

    void addArmourKill() {
        armourKills.incrementAndGet();
    }

    void addCyclopsKill() {
        cyclopsKills.incrementAndGet();
        killsSinceDefender.incrementAndGet();
    }

    void addDefender(Defender defender) {
        defenders.add(defender);
        killsSinceDefender.set(0);
    }

    void addTokensEarned(int amount) {
        tokensEarned.addAndGet(amount);
    }

    void addTokensSpent(int amount) {
        tokensSpent.addAndGet(amount);
    }

    void addBankTrip() {
        bankTrips.incrementAndGet();
    }

    void addRecovery() {
        recoveries.incrementAndGet();
    }

    Duration runtime() {
        return Duration.between(startedAt, Instant.now());
    }

    int armourKills() {
        return armourKills.get();
    }

    int cyclopsKills() {
        return cyclopsKills.get();
    }

    int killsSinceDefender() {
        return killsSinceDefender.get();
    }

    int tokensEarned() {
        return tokensEarned.get();
    }

    int tokensSpent() {
        return tokensSpent.get();
    }

    int bankTrips() {
        return bankTrips.get();
    }

    int recoveries() {
        return recoveries.get();
    }

    /** Defenders picked up this session, in order. */
    List<Defender> defenders() {
        return defenders;
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
