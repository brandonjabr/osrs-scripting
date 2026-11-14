package net.runelite.client.plugins.microbot.util.antiban;

import net.runelite.client.plugins.microbot.util.math.Rs2Random;

import java.util.concurrent.ThreadLocalRandom;

/**
 * What a player's hand does with the mouse while waiting: now and then it drifts off the game window, or wanders
 * somewhere on it, instead of sitting on the last thing clicked.
 * <p>
 * {@link #enable()} turns on the antiban's off-screen and random moves, which happen during action cooldowns and
 * micro-breaks, with chances picked once per run so each session has its own habit. Call it after applying an
 * antiban template, since templates and {@link Rs2Antiban#resetAntibanSettings()} switch these off.
 */
public final class MouseIdleHabits {

    private MouseIdleHabits() {
    }

    public static void enable() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Rs2AntibanSettings.moveMouseOffScreen = true;
        Rs2AntibanSettings.moveMouseOffScreenChance = 0.15 + random.nextDouble() * 0.10;
        Rs2AntibanSettings.moveMouseRandomly = true;
        Rs2AntibanSettings.moveMouseRandomlyChance = 0.08 + random.nextDouble() * 0.07;
    }

    /**
     * For scripts that wait on long actions without antiban pauses: moves the mouse off screen with this chance
     * (0 to 1). Returns whether it did.
     */
    public static boolean maybeMoveOffScreen(double chance) {
        if (!Rs2Random.diceFractional(chance)) return false;
        Rs2Antiban.moveMouseOffScreen();
        return true;
    }
}
