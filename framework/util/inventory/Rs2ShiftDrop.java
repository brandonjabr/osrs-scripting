package net.runelite.client.plugins.microbot.util.inventory;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.settings.Rs2Settings;

import java.awt.Rectangle;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepGaussian;

/**
 * Drops items the way a player power-drops: shift held down and a real click on each slot, with the game's
 * "shift click to drop" setting turned on first. Slots go in a snake (left to right, then right to left on the
 * next row), the order a hand naturally sweeps, with human spacing between clicks. Falls back to the menu drop
 * when the setting can't be turned on.
 */
@Slf4j
public final class Rs2ShiftDrop {

    private static final int COLUMNS = 4;

    private Rs2ShiftDrop() {
    }

    /** Turns on the game's shift-click drop setting if it's off. True once it's on. */
    public static boolean ensureEnabled() {
        if (Rs2Settings.isDropShiftSettingEnabled()) return true;
        log.info("[ShiftDrop] Turning on \"Shift click to drop items\" in the game settings");
        return Rs2Settings.enableDropShiftSetting(true);
    }

    /** Drops every inventory item matching {@code filter}. True if anything was dropped. */
    public static boolean dropAll(Predicate<Rs2ItemModel> filter) {
        List<Rs2ItemModel> items = Rs2Inventory.items(filter)
                .sorted(snakeOrder())
                .collect(Collectors.toList());
        if (items.isEmpty()) return false;

        if (!ensureEnabled()) {
            log.info("[ShiftDrop] Shift-click drop is off and couldn't be turned on; dropping from the menu");
            Rs2Inventory.dropAll(filter);
            return true;
        }

        Rs2Inventory.open();
        try {
            Rs2Keyboard.holdShift();
            sleepGaussian(140, 40);
            for (Rs2ItemModel item : items) {
                Rectangle bounds = Rs2Inventory.itemBounds(item);
                if (bounds == null || bounds.width <= 1) continue;
                // A point inside the slot, away from its edges, like a hand aiming at the icon.
                Rectangle inner = new Rectangle(bounds.x + bounds.width / 5, bounds.y + bounds.height / 5,
                        Math.max(1, bounds.width * 3 / 5), Math.max(1, bounds.height * 3 / 5));
                Microbot.getMouse().click(inner);
                // Quick, uneven rhythm with the odd longer gap.
                if (Rs2Random.between(0, 100) < 6) {
                    sleep(260, 520);
                } else {
                    sleepGaussian(95, 30);
                }
            }
        } finally {
            sleepGaussian(90, 30);
            Rs2Keyboard.releaseShift();
        }
        return true;
    }

    private static Comparator<Rs2ItemModel> snakeOrder() {
        return Comparator.comparingInt(item -> {
            int slot = item.getSlot();
            int row = slot / COLUMNS;
            int col = slot % COLUMNS;
            return row * COLUMNS + (row % 2 == 0 ? col : COLUMNS - 1 - col);
        });
    }
}
