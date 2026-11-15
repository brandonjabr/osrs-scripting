package net.runelite.client.plugins.microbot.shadowfaxquesting;

import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.questing.StepOverride;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Merlin's Crystal: summoning Thrantax means dropping the bat bones while standing in the star north-east of
 * Camelot. Quest Helper says to right-click drop them, which the generic executor doesn't do; the incantation that
 * follows is Quest Helper's own dialogue answer.
 */
final class MerlinsCrystalSupport {

    static final StepOverride DROP_BAT_BONES = new StepOverride("dropping the bat bones in the star",
            ctx -> ctx.stepText().contains("drop the bat bones") && Rs2Inventory.hasItem(ItemID.BAT_BONES),
            ctx -> {
                Microbot.status = "Dropping the bat bones in the star";
                if (Rs2Inventory.interact(ItemID.BAT_BONES, "Drop")) {
                    sleepUntil(() -> !Rs2Inventory.hasItem(ItemID.BAT_BONES), 3_000);
                }
                return true;
            });

    private MerlinsCrystalSupport() {
    }
}
