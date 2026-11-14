package net.runelite.client.plugins.microbot.shadowfaxboltenchanter;

import net.runelite.client.plugins.microbot.util.safety.StuckGuard;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.antiban.MouseIdleHabits;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.tabs.Rs2Tab;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import javax.inject.Singleton;
import java.awt.event.KeyEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Casts Enchant Crossbow Bolt over and over. The spell opens the "make" menu; we pick the bolts and let it run,
 * casting again whenever the bolt count stops dropping. Stops the plugin once there are no bolts left to enchant
 * (or no runes for them).
 */
@Slf4j
@Singleton
public class BoltEnchanterScript extends Script {

    // The back arrow inside the spellbook's jewellery-enchant submenu, which hides the bolt spell.
    private static final int SPELLBOOK_BACK = 14286852;
    // Recast once the bolts haven't changed for about this long.
    private static final long IDLE_MIN_MS = 3_000;
    private static final long IDLE_MAX_MS = 4_500;
    // Chance a batch gets the mouse moved off screen, and how long into the batch that happens.
    private static final double DRIFT_CHANCE = 0.35;
    private static final long DRIFT_MIN_DELAY_MS = 1_200;
    private static final long DRIFT_MAX_DELAY_MS = 6_000;

    private BoltEnchanterConfig config;

    // Progress tracking, script thread only.
    private BoltType tracked;
    private int lastCount = -1;
    private long lastActionAt;
    private long idleAfterMs;
    private boolean finishing;
    // When to move the mouse off screen during this batch; 0 for not this batch.
    private long driftAt;

    // Read by the overlay.
    private volatile Instant startedAt = Instant.now();
    private volatile int startXp = -1;
    private volatile int enchanted;
    private volatile BoltType current;
    private volatile int boltsLeft;

    public boolean run(BoltEnchanterConfig config) {
        this.config = config;
        tracked = null;
        lastCount = -1;
        lastActionAt = 0;
        finishing = false;
        driftAt = 0;
        if (config.antiban()) {
            Rs2Antiban.resetAntibanSettings();
            Rs2Antiban.antibanSetupTemplates.applyGeneralBasicSetup();
            MouseIdleHabits.enable();
        }
        startedAt = Instant.now();
        startXp = -1;
        enchanted = 0;
        current = null;
        boltsLeft = 0;

        StuckGuard.attach("Shadowfax Bolt Enchanter", BoltEnchanterPlugin.class);

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (StuckGuard.isTripped()) return;
                if (!Microbot.isLoggedIn() || finishing) return;
                if (startXp < 0) startXp = Microbot.getClient().getSkillExperience(Skill.MAGIC);
                step();
            } catch (Throwable ex) {
                log.error("[BoltEnchanter] Unexpected error in scheduled loop", ex);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);
        return true;
    }

    private void step() {
        BoltType type = pickBolts();
        current = type;
        if (type == null) {
            finish();
            return;
        }
        trackProgress(type);

        if (Rs2Widget.isProductionWidgetOpen()) {
            choose(type);
            return;
        }
        if (System.currentTimeMillis() - lastActionAt < idleAfterMs) {
            Microbot.status = "Enchanting " + type + " bolts";
            if (driftAt > 0 && System.currentTimeMillis() >= driftAt) {
                driftAt = 0;
                MouseIdleHabits.maybeMoveOffScreen(1.0);
            }
            return;
        }
        cast(type);
    }

    /** The configured bolts, or for "best in inventory" the highest-level kind we can enchant; null when done. */
    private BoltType pickBolts() {
        BoltType fixed = config.bolts().type();
        if (fixed != null) {
            return count(fixed) > 0 && Rs2Magic.canCast(fixed.spell) ? fixed : null;
        }
        BoltType[] types = BoltType.values();
        for (int i = types.length - 1; i >= 0; i--) {
            if (count(types[i]) > 0 && Rs2Magic.canCast(types[i].spell)) return types[i];
        }
        return null;
    }

    /** Counts bolts that disappeared since the last tick as enchanted. */
    private void trackProgress(BoltType type) {
        int count = count(type);
        if (type != tracked) {
            tracked = type;
            lastCount = count;
        } else if (count < lastCount) {
            enchanted += lastCount - count;
            lastCount = count;
            markAction();
        } else {
            lastCount = count;
        }
        boltsLeft = count;
    }

    private void cast(BoltType type) {
        Microbot.status = "Casting Enchant Crossbow Bolt";
        Rs2Tab.switchToMagicTab();
        sleepUntil(() -> !Rs2Widget.isHidden(InterfaceID.MagicSpellbook.XBOWS_ENCHANT), 1200);
        if (Rs2Widget.isHidden(InterfaceID.MagicSpellbook.XBOWS_ENCHANT) && !Rs2Widget.isHidden(SPELLBOOK_BACK)) {
            Rs2Widget.clickWidget(SPELLBOOK_BACK);
            sleep(300, 600);
        }
        if (!Rs2Widget.clickWidget(InterfaceID.MagicSpellbook.XBOWS_ENCHANT)) {
            log.warn("[BoltEnchanter] Couldn't click the Enchant Crossbow Bolt spell");
            markAction();
            return;
        }
        if (sleepUntil(Rs2Widget::isProductionWidgetOpen, 2400)) {
            choose(type);
        } else {
            markAction();
        }
    }

    /** Picks our bolts in the make menu, set to All. */
    private void choose(BoltType type) {
        Microbot.status = "Choosing " + type + " bolts";
        Rs2Widget.enableQuantityOption("All");
        boolean chosen = false;
        // The kind we actually hold first, so dragon bolts don't pick the regular option.
        for (String name : type.itemNames()) {
            if (Rs2Inventory.itemQuantity(name, true) > 0 && Rs2Widget.handleProcessingInterface(name)) {
                chosen = true;
                break;
            }
        }
        if (!chosen && !Rs2Widget.handleProcessingInterface(type.gem)) {
            // Space picks the highlighted option, which is the last bolts enchanted.
            Rs2Keyboard.keyPress(KeyEvent.VK_SPACE);
            sleepUntil(() -> !Rs2Widget.isProductionWidgetOpen(), 1200);
        }
        markAction();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        driftAt = config.antiban() && random.nextDouble() < DRIFT_CHANCE
                ? System.currentTimeMillis() + random.nextLong(DRIFT_MIN_DELAY_MS, DRIFT_MAX_DELAY_MS)
                : 0;
    }

    private void markAction() {
        lastActionAt = System.currentTimeMillis();
        idleAfterMs = ThreadLocalRandom.current().nextLong(IDLE_MIN_MS, IDLE_MAX_MS);
    }

    private void finish() {
        finishing = true;
        BoltType fixed = config.bolts().type();
        boolean anyBolts = fixed != null ? count(fixed) > 0
                : Arrays.stream(BoltType.values()).anyMatch(t -> count(t) > 0);
        String why = anyBolts ? "Out of runes (or Magic level) for the bolts left" : "No bolts left to enchant";
        Microbot.status = why;
        log.info("[BoltEnchanter] {}; enchanted {} bolts. Stopping", why, enchanted);
        Microbot.log("Shadowfax Bolt Enchanter: " + why + ", stopping.");
        Microbot.stopPlugin(BoltEnchanterPlugin.class);
    }

    @Override
    public void shutdown() {
        StuckGuard.detach(BoltEnchanterPlugin.class);
        super.shutdown();
        Rs2Antiban.resetAntibanSettings();
    }

    private static int count(BoltType type) {
        int total = 0;
        for (String name : type.itemNames()) {
            total += Rs2Inventory.itemQuantity(name, true);
        }
        return total;
    }

    // --- For the overlay ---

    Duration runtime() {
        return Duration.between(startedAt, Instant.now());
    }

    int startXp() {
        return startXp;
    }

    int enchanted() {
        return enchanted;
    }

    BoltType current() {
        return current;
    }

    int boltsLeft() {
        return boltsLeft;
    }
}
