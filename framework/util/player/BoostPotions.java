package net.runelite.client.plugins.microbot.util.player;

import net.runelite.api.Skill;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;

import java.util.Comparator;
import java.util.Locale;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Skill-boosting potions matched by exact base name ("Super strength" never matches "Divine super strength
 * potion"), lowest dose first so partly used potions get finished before new ones are opened.
 */
public final class BoostPotions {

    private BoostPotions() {
    }

    /** "Super strength(3)" → "super strength". */
    public static String baseName(String itemName) {
        return itemName == null ? "" : itemName.replaceAll("\\s*\\(\\d+\\)$", "").trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isPotion(Rs2ItemModel item, String potionName) {
        return item != null && !item.isNoted() && baseName(item.getName()).equals(baseName(potionName));
    }

    /** How many unnoted potions of this kind are carried (any dose). */
    public static int carried(String potionName) {
        return (int) Rs2Inventory.items(item -> isPotion(item, potionName)).count();
    }

    /** How far the skill is boosted above its real level (negative when drained). */
    public static int boost(Skill skill) {
        return Rs2Player.getBoostedSkillLevel(skill) - Rs2Player.getRealSkillLevel(skill);
    }

    /**
     * Drinks one dose of {@code potionName} when the boost to {@code skill} is at or below {@code redrinkAt} and
     * waits until the game shows the new level. Does nothing with the bank open.
     *
     * @return true if a dose was drunk this call
     */
    public static boolean drinkIfLow(Skill skill, String potionName, int redrinkAt) {
        if (Rs2Bank.isOpen() || boost(skill) > redrinkAt) return false;
        Rs2ItemModel potion = Rs2Inventory.items(item -> isPotion(item, potionName))
                .min(Comparator.comparingInt(BoostPotions::doses))
                .orElse(null);
        if (potion == null) return false;
        int before = Rs2Player.getBoostedSkillLevel(skill);
        Microbot.status = "Drinking " + potion.getName();
        return Rs2Inventory.interact(potion, "Drink")
                && sleepUntil(() -> Rs2Player.getBoostedSkillLevel(skill) > before, 2400);
    }

    /** Doses left from the "(n)" suffix; 0 when there is none. */
    public static int doses(Rs2ItemModel item) {
        String name = item.getName();
        if (name == null) return 0;
        int open = name.lastIndexOf('(');
        int close = name.lastIndexOf(')');
        if (open < 0 || close <= open + 1) return 0;
        try {
            return Integer.parseInt(name.substring(open + 1, close));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * With the bank open, withdraws one more potion of this kind (the most doses the bank has) while fewer than
     * {@code wanted} are carried.
     *
     * @return true if a withdrawal was made this call
     */
    public static boolean topUp(String potionName, int wanted) {
        if (!Rs2Bank.isOpen() || carried(potionName) >= wanted) return false;
        Rs2ItemModel best = Rs2Bank.bankItems().stream()
                .filter(item -> isPotion(item, potionName))
                .max(Comparator.comparingInt(BoostPotions::doses))
                .orElse(null);
        if (best == null) return false;
        int before = carried(potionName);
        Microbot.status = "Withdrawing " + best.getName();
        return Rs2Bank.withdrawOne(best.getId()) && sleepUntil(() -> carried(potionName) > before, 2400);
    }
}
