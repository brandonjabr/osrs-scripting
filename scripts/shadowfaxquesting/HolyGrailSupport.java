package net.runelite.client.plugins.microbot.shadowfaxquesting;

import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.questing.StepOverride;

/**
 * Holy Grail helpers, for what Quest Helper leaves open (checked against the OSRS Wiki):
 * <ul>
 *   <li><b>Entrana</b>: the High Priest is on Entrana, and the monks only take players with no weapons or armour.
 *       Everything worn is banked and the inventory cut down to what {@link EntranaTrip#allowed} keeps, then the
 *       boat is boarded with Take-boat (see {@link EntranaTrip} for why).</li>
 *   <li><b>Black Knight Titan</b> (level 120): only Excalibur can deal the killing blow, so it's wielded for the
 *       whole fight.</li>
 * </ul>
 */
final class HolyGrailSupport {

    private static final String ENTRANA_STEP = "monk of Entrana for passage";

    static final StepOverride PREPARE_FOR_ENTRANA = new StepOverride("banking combat gear for Entrana",
            ctx -> ctx.stepText().contains(ENTRANA_STEP) && !EntranaTrip.onEntrana()
                    && (!EntranaTrip.clear(i -> EntranaTrip.allowed(ctx, i)) || Rs2Bank.isOpen()),
            ctx -> EntranaTrip.bankForEntrana(i -> EntranaTrip.allowed(ctx, i)));

    static final StepOverride BOARD_BOAT = new StepOverride("taking the boat to Entrana",
            ctx -> ctx.stepText().contains(ENTRANA_STEP) && !EntranaTrip.onEntrana() && !Rs2Bank.isOpen()
                    && EntranaTrip.clear(i -> EntranaTrip.allowed(ctx, i)),
            ctx -> EntranaTrip.board(ctx, "Holy Grail"));

    static final StepOverride WIELD_EXCALIBUR = new StepOverride("wielding Excalibur for the Titan",
            ctx -> ctx.stepText().contains("Black Knight Titan with Excalibur")
                    && !Rs2Equipment.isWearing(ItemID.EXCALIBUR) && Rs2Inventory.hasItem(ItemID.EXCALIBUR),
            ctx -> ctx.equip(ItemID.EXCALIBUR));

    private HolyGrailSupport() {
    }
}
