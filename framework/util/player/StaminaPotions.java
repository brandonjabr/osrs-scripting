package net.runelite.client.plugins.microbot.util.player;

import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.shopping.ItemProcurer;

import java.util.Arrays;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Stamina potion handling for scripts that walk a lot: take potions out of the bank, and drink one when run energy
 * is low and no stamina effect is running. Stamina potions are members-only, so on free-to-play worlds every method
 * here does nothing.
 */
public final class StaminaPotions {

    /** Highest dose first, for withdrawing full potions. */
    private static final int[] DOSES_HIGH_FIRST = {
            ItemID._4DOSESTAMINA, ItemID._3DOSESTAMINA, ItemID._2DOSESTAMINA, ItemID._1DOSESTAMINA
    };
    /** Lowest dose first, so part-used potions are drunk before full ones. */
    private static final int[] DOSES_LOW_FIRST = {
            ItemID._1DOSESTAMINA, ItemID._2DOSESTAMINA, ItemID._3DOSESTAMINA, ItemID._4DOSESTAMINA
    };

    private StaminaPotions() {
    }

    /** Whether stamina potions can be used here at all (members world). */
    public static boolean usable() {
        return ItemProcurer.onMembersWorld();
    }

    /** Stamina potions carried, any dose. */
    public static int carried() {
        return Arrays.stream(DOSES_HIGH_FIRST).map(Rs2Inventory::itemQuantity).sum();
    }

    /** Any dose of stamina potion; for keeping them out of "deposit everything else" bank calls. */
    public static boolean isStamina(int itemId) {
        return Arrays.stream(DOSES_HIGH_FIRST).anyMatch(id -> id == itemId);
    }

    public static boolean inBank() {
        return Arrays.stream(DOSES_HIGH_FIRST).anyMatch(id -> Rs2Bank.count(id) > 0);
    }

    /**
     * With the bank open, withdraws one stamina potion (fullest first) if the bank has any.
     *
     * @return true if one was withdrawn
     */
    public static boolean withdrawOne() {
        if (!usable() || !Rs2Bank.isOpen()) return false;
        for (int id : DOSES_HIGH_FIRST) {
            if (Rs2Bank.count(id) <= 0) continue;
            int before = carried();
            Rs2Bank.withdrawX(id, 1);
            return sleepUntil(() -> carried() > before, 3000);
        }
        return false;
    }

    /**
     * With the bank open, takes out one more stamina potion while fewer than {@code wanted} are carried. Call it once
     * per tick from a banking step and return when it says it withdrew; it returns false once enough are carried,
     * the bank has none, or the withdraw didn't work (full inventory), so the bank trip carries on either way.
     *
     * @return true if a potion was withdrawn this call
     */
    public static boolean topUp(int wanted) {
        if (!usable() || !Rs2Bank.isOpen() || carried() >= wanted || !inBank()) return false;
        Microbot.status = "Withdrawing a stamina potion";
        return withdrawOne();
    }

    /**
     * Drinks a dose when run energy is at or below {@code energyPercent} and no stamina effect is active, then makes
     * sure running is on. Does nothing with the bank or another interface open.
     *
     * @return true if a dose was drunk this call
     */
    public static boolean drinkIfNeeded(int energyPercent) {
        if (!usable() || Rs2Bank.isOpen() || carried() == 0) return false;
        if (Rs2Player.hasStaminaActive() || Rs2Player.getRunEnergy() > energyPercent) return false;
        for (int id : DOSES_LOW_FIRST) {
            if (!Rs2Inventory.hasItem(id)) continue;
            Microbot.status = "Drinking a stamina potion";
            if (Rs2Inventory.interact(id, "Drink") && sleepUntil(Rs2Player::hasStaminaActive, 2400)) {
                Rs2Player.toggleRunEnergy(true);
                return true;
            }
            return false;
        }
        return false;
    }
}
