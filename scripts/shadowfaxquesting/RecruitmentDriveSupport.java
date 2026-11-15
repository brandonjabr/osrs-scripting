package net.runelite.client.plugins.microbot.shadowfaxquesting;

import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.questing.StepOverride;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Recruitment Drive: Sir Tiffy only teleports players to the tests with no items or equipment at all (Quest Helper's
 * "No items or equipment carried" requirement), so before talking to him everything goes in the bank. The tests
 * themselves are Quest Helper's, puzzle solutions included; Sir Leye is fought bare-handed, which is allowed.
 */
final class RecruitmentDriveSupport {

    static final StepOverride EMPTY_HANDED = new StepOverride("banking everything for Sir Tiffy",
            ctx -> ctx.stepText().contains("Talk to Sir Tiffy Cashien") && carryingAnything(),
            ctx -> bankEverything());

    private RecruitmentDriveSupport() {
    }

    private static boolean carryingAnything() {
        return !Rs2Equipment.items().isEmpty() || Rs2Inventory.items().findAny().isPresent();
    }

    private static boolean bankEverything() {
        if (!Rs2Bank.isOpen()) {
            Microbot.status = "Going to a bank: Sir Tiffy takes no items or equipment";
            Rs2Bank.walkToBankAndUseBank();
            return true;
        }
        if (!Rs2Equipment.items().isEmpty()) {
            Microbot.status = "Banking worn equipment";
            Rs2Bank.depositEquipment();
            sleepUntil(() -> Rs2Equipment.items().isEmpty(), 3_000);
            return true;
        }
        Microbot.status = "Banking the inventory";
        Rs2Bank.depositAll();
        if (sleepUntil(() -> !Rs2Inventory.items().findAny().isPresent(), 3_000)) Rs2Bank.closeBank();
        return true;
    }
}
