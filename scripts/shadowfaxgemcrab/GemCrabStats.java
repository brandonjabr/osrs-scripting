package net.runelite.client.plugins.microbot.shadowfaxgemcrab;

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
 * Session counters shared by the plugin (chat and spawn events), the script and the overlay.
 * Written from the script and client threads, read from the overlay on the client thread.
 */
@Singleton
public class GemCrabStats {

    static final Skill[] TRACKED_SKILLS = {
            Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE, Skill.RANGED, Skill.MAGIC, Skill.HITPOINTS
    };

    /** Running total for one gem. */
    static final class GemLine {
        final String name;
        final AtomicInteger quantity = new AtomicInteger();
        final AtomicLong value = new AtomicLong();

        GemLine(String name) {
            this.name = name;
        }
    }

    private volatile Instant startedAt = Instant.now();
    private final AtomicInteger crabs = new AtomicInteger();
    private final AtomicInteger shellsMined = new AtomicInteger();
    private final AtomicInteger tunnels = new AtomicInteger();
    private final AtomicInteger bankTrips = new AtomicInteger();
    private final AtomicInteger potions = new AtomicInteger();
    private final AtomicInteger styleSwaps = new AtomicInteger();
    private final AtomicInteger recoveries = new AtomicInteger();
    private final AtomicLong gemValue = new AtomicLong();
    private final Map<String, GemLine> gems = new ConcurrentHashMap<>();
    private volatile Map<Skill, Integer> startXp;

    void reset() {
        startedAt = Instant.now();
        crabs.set(0);
        shellsMined.set(0);
        tunnels.set(0);
        bankTrips.set(0);
        potions.set(0);
        styleSwaps.set(0);
        recoveries.set(0);
        gemValue.set(0);
        gems.clear();
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

    void addCrab() {
        crabs.incrementAndGet();
    }

    void addShellMined() {
        shellsMined.incrementAndGet();
    }

    void addTunnel() {
        tunnels.incrementAndGet();
    }

    void addBankTrip() {
        bankTrips.incrementAndGet();
    }

    void addPotion() {
        potions.incrementAndGet();
    }

    void addStyleSwap() {
        styleSwaps.incrementAndGet();
    }

    void addRecovery() {
        recoveries.incrementAndGet();
    }

    void addGem(String name, long value) {
        GemLine line = gems.computeIfAbsent(name, GemLine::new);
        line.quantity.incrementAndGet();
        line.value.addAndGet(value);
        gemValue.addAndGet(value);
    }

    Duration runtime() {
        return Duration.between(startedAt, Instant.now());
    }

    int crabs() {
        return crabs.get();
    }

    int shellsMined() {
        return shellsMined.get();
    }

    int tunnels() {
        return tunnels.get();
    }

    int bankTrips() {
        return bankTrips.get();
    }

    int potions() {
        return potions.get();
    }

    int styleSwaps() {
        return styleSwaps.get();
    }

    int recoveries() {
        return recoveries.get();
    }

    long gemValue() {
        return gemValue.get();
    }

    int gemCount() {
        int total = 0;
        for (GemLine line : gems.values()) total += line.quantity.get();
        return total;
    }

    /** Gems mined, most valuable first. */
    List<GemLine> topGems(int limit) {
        List<GemLine> lines = new ArrayList<>(gems.values());
        lines.sort(Comparator.comparingLong((GemLine l) -> l.value.get()).reversed());
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
