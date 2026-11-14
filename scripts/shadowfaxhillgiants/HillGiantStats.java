package net.runelite.client.plugins.microbot.shadowfaxhillgiants;

import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.client.plugins.microbot.Microbot;

import javax.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Session counters shared by the plugin (kill events), the script (loot, bank trips) and the overlay.
 * Written from the script and client threads, read from the overlay on the client thread.
 */
@Singleton
public class HillGiantStats {

    static final Skill[] TRACKED_SKILLS = {
            Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE, Skill.RANGED, Skill.MAGIC, Skill.HITPOINTS, Skill.PRAYER
    };

    /** Running total for one item name. */
    static final class LootLine {
        final String name;
        final AtomicInteger quantity = new AtomicInteger();
        final AtomicLong value = new AtomicLong();

        LootLine(String name) {
            this.name = name;
        }
    }

    private volatile Instant startedAt = Instant.now();
    private final AtomicInteger kills = new AtomicInteger();
    private final AtomicInteger bankTrips = new AtomicInteger();
    private final AtomicInteger recoveries = new AtomicInteger();
    private final AtomicInteger bonesBuried = new AtomicInteger();
    private final AtomicLong lootValue = new AtomicLong();
    private final Map<String, LootLine> loot = new ConcurrentHashMap<>();
    // Null until the first logged-in tick, so xp gained isn't measured from zero.
    private volatile Map<Skill, Integer> startXp;

    void reset() {
        startedAt = Instant.now();
        kills.set(0);
        bankTrips.set(0);
        recoveries.set(0);
        bonesBuried.set(0);
        lootValue.set(0);
        loot.clear();
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

    void addKill() {
        kills.incrementAndGet();
    }

    void addRecovery() {
        recoveries.incrementAndGet();
    }

    int recoveries() {
        return recoveries.get();
    }

    void addBankTrip() {
        bankTrips.incrementAndGet();
    }

    void addBoneBuried() {
        bonesBuried.incrementAndGet();
    }

    void addLoot(String name, int quantity, long value) {
        LootLine line = loot.computeIfAbsent(name, LootLine::new);
        line.quantity.addAndGet(quantity);
        line.value.addAndGet(value);
        lootValue.addAndGet(value);
    }

    Duration runtime() {
        return Duration.between(startedAt, Instant.now());
    }

    int kills() {
        return kills.get();
    }

    int bankTrips() {
        return bankTrips.get();
    }

    int bonesBuried() {
        return bonesBuried.get();
    }

    long lootValue() {
        return lootValue.get();
    }

    int itemsLooted() {
        int total = 0;
        for (LootLine line : loot.values()) total += line.quantity.get();
        return total;
    }

    /** Looted items, most valuable first. */
    List<LootLine> topLoot(int limit) {
        List<LootLine> lines = new ArrayList<>(loot.values());
        lines.sort(Comparator.comparingLong((LootLine l) -> l.value.get()).reversed());
        return lines.size() > limit ? lines.subList(0, limit) : lines;
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
