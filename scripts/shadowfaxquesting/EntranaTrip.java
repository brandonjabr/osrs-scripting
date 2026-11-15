package net.runelite.client.plugins.microbot.shadowfaxquesting;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.magic.Runes;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.questing.QuestContext;

import java.util.Arrays;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Getting to Entrana, shared by the quests that go there (Lost City, Holy Grail). The monks at Port Sarim only take
 * players with no weapons or armour, worn or carried.
 * <p>
 * Boarding is done with the monk's Take-boat option, then waiting to land, rather than by Quest Helper's
 * talk-to-the-monk step: that step walks to the monk, and the walk keeps going after the boat sails, crosses the
 * Entrana gangplank and takes the boat straight back.
 */
@Slf4j
final class EntranaTrip {

    private static final WorldPoint PORT_SARIM_DOCK = new WorldPoint(3047, 3236, 0);
    // The monks on the Port Sarim side; the SHIPMONK2 ones on Entrana sail back.
    private static final int[] PORT_SARIM_MONKS = {NpcID.SHIPMONK, NpcID.SHIPMONK1_B, NpcID.SHIPMONK1_C};

    // Quest Helper's Entrana zone, ship deck included.
    private static final WorldPoint ENTRANA_SW = new WorldPoint(2798, 3327, 0);
    private static final WorldPoint ENTRANA_NE = new WorldPoint(2878, 3394, 1);

    private static final Set<Integer> RUNE_IDS = Arrays.stream(Runes.values()).map(Runes::getItemId)
            .collect(Collectors.toSet());

    private EntranaTrip() {
    }

    static boolean onEntrana() {
        WorldPoint p = Rs2Player.getWorldLocation();
        return p != null && p.getX() >= ENTRANA_SW.getX() && p.getX() <= ENTRANA_NE.getX()
                && p.getY() >= ENTRANA_SW.getY() && p.getY() <= ENTRANA_NE.getY()
                && p.getPlane() >= ENTRANA_SW.getPlane() && p.getPlane() <= ENTRANA_NE.getPlane();
    }

    /**
     * What any quest may take to Entrana: runes, coins, food, teleport tablets, a knife and stamina potions. Nothing
     * the monks call a weapon or armour.
     */
    static boolean allowed(QuestContext ctx, Rs2ItemModel item) {
        int id = item.getId();
        if (RUNE_IDS.contains(id) || id == ItemID.KNIFE || id == ItemID.COINS) return true;
        if (ctx.food() != null && id == ctx.food().getItemId()) return true;
        String name = item.getName();
        if (name == null) return false;
        String lower = name.toLowerCase();
        return lower.startsWith("stamina potion") || (lower.contains("teleport") && !lower.contains("ring")
                && !lower.contains("amulet") && !lower.contains("necklace"));
    }

    /** Nothing worn and nothing carried that {@code allowed} rejects. */
    static boolean clear(Predicate<Rs2ItemModel> allowed) {
        return Rs2Equipment.items().isEmpty() && Rs2Inventory.items().allMatch(allowed);
    }

    /** One bank action toward {@link #clear}: open a bank, bank what's worn, then what's carried and not allowed. */
    static boolean bankForEntrana(Predicate<Rs2ItemModel> allowed) {
        if (clear(allowed)) {
            if (Rs2Bank.isOpen()) Rs2Bank.closeBank();
            return true;
        }
        if (!Rs2Bank.isOpen()) {
            Microbot.status = "Going to a bank to leave weapons and armour behind";
            Rs2Bank.walkToBankAndUseBank();
            return true;
        }
        if (!Rs2Equipment.items().isEmpty()) {
            Microbot.status = "Banking worn equipment (no weapons or armour on Entrana)";
            Rs2Bank.depositEquipment();
            sleepUntil(() -> Rs2Equipment.items().isEmpty(), 3_000);
            return true;
        }
        Microbot.status = "Banking what the monks won't allow";
        Rs2Bank.depositAllExcept(allowed);
        sleepUntil(() -> Rs2Inventory.items().allMatch(allowed), 3_000);
        return true;
    }

    /** Next to the Port Sarim monks: Take-boat, then wait out the search and the trip without walking anywhere. */
    static boolean board(QuestContext ctx, String quest) {
        Rs2NpcModel monk = Microbot.getRs2NpcCache().query().withIds(PORT_SARIM_MONKS).nearestOnClientThread(10);
        if (monk == null) {
            Microbot.status = "Walking to the Entrana monks at Port Sarim";
            ctx.walkTo(PORT_SARIM_DOCK, 4);
            return true;
        }
        Microbot.status = "Taking the boat to Entrana";
        if (!monk.click("Take-boat")) return true;
        if (sleepUntil(EntranaTrip::onEntrana, 20_000)) {
            log.info("[Questing] {}: arrived on Entrana", quest);
        } else if (!Rs2Dialogue.isInDialogue()) {
            log.warn("[Questing] {}: the monks didn't take us to Entrana yet", quest);
        }
        return true;
    }
}
