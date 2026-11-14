package net.runelite.client.plugins.microbot.shadowfaxmagictrainer;

import net.runelite.api.Skill;
import net.runelite.client.plugins.microbot.Microbot;

import javax.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Session counters written by the script thread and read by the overlay on the client thread.
 */
@Singleton
public class MagicTrainerStats {

    private volatile Instant startedAt = Instant.now();
    private final AtomicInteger casts = new AtomicInteger();
    private final AtomicLong gpSpent = new AtomicLong();
    // -1 until the first logged-in tick, so xp gained isn't measured from zero.
    private volatile int startXp = -1;

    // Snapshot of what the script is doing, refreshed every tick.
    private volatile SplashSpell spell;
    private volatile Map<String, Integer> runes = Collections.emptyMap();
    private volatile int castsLeft;
    private volatile Integer magicBonus;

    void reset() {
        startedAt = Instant.now();
        casts.set(0);
        gpSpent.set(0);
        startXp = -1;
        spell = null;
        runes = Collections.emptyMap();
        castsLeft = 0;
        magicBonus = null;
    }

    /** Call from the script thread once logged in. */
    void captureBaselineIfNeeded() {
        if (startXp >= 0) return;
        startXp = Microbot.getClientThread()
                .runOnClientThreadOptional(() -> Microbot.getClient().getSkillExperience(Skill.MAGIC))
                .orElse(-1);
    }

    void addCast() {
        casts.incrementAndGet();
    }

    void addGpSpent(long gp) {
        if (gp > 0) gpSpent.addAndGet(gp);
    }

    void update(SplashSpell spell, Map<String, Integer> runes, int castsLeft, int magicBonus) {
        this.spell = spell;
        this.runes = runes;
        this.castsLeft = castsLeft;
        this.magicBonus = magicBonus;
    }

    Duration runtime() {
        return Duration.between(startedAt, Instant.now());
    }

    int casts() {
        return casts.get();
    }

    long gpSpent() {
        return gpSpent.get();
    }

    int startXp() {
        return startXp;
    }

    SplashSpell spell() {
        return spell;
    }

    /** Rune name to inventory count; Integer.MAX_VALUE when a staff provides it. */
    Map<String, Integer> runes() {
        return runes;
    }

    int castsLeft() {
        return castsLeft;
    }

    Integer magicBonus() {
        return magicBonus;
    }

    /** Scales a session count to a per-hour rate; 0 during the first few seconds to avoid wild numbers. */
    long perHour(long amount) {
        long ms = runtime().toMillis();
        return ms < 5_000 ? 0 : amount * 3_600_000L / ms;
    }
}
