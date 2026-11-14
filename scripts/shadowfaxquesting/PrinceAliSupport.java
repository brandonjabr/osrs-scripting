package net.runelite.client.plugins.microbot.shadowfaxquesting;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.questing.QuestContext;
import net.runelite.client.plugins.microbot.util.questing.StepOverride;

import java.util.Locale;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Prince Ali Rescue helpers. Quest Helper drives the quest (Ned's wig, Aggie's paste, Keli's key print, Osman's key,
 * Leela, Joe's beers, the rope, the door); these cover the two spots the generic executor can trip on:
 * <ul>
 *   <li><b>Dyeing the wig</b>: a "use yellow dye on the wig" step with no NPC or object, done here as one combine.</li>
 *   <li><b>Lady Keli getting loose</b>: she frees herself after a while, and the cell door won't open while she's
 *       free, but Quest Helper's step stays "use the key on the door". If the door step has gone on for a while
 *       without getting into the cell, she's tied up again with the spare rope the kit carries.</li>
 * </ul>
 */
@Slf4j
final class PrinceAliSupport {

    private static final WorldPoint CELL_DOOR = new WorldPoint(3123, 3243, 0);
    // Quest Helper's cell zone.
    private static final int CELL_MIN_X = 3121, CELL_MAX_X = 3125, CELL_MIN_Y = 3240, CELL_MAX_Y = 3243;
    private static final long RETIE_AFTER_MS = 25_000;
    private static final String DOOR_SINCE = "princeAli.doorSince";

    static final StepOverride DYE_WIG = StepOverride.onStepText("dyeing the wig", "Dye the wig",
            PrinceAliSupport::dyeWig);

    static final StepOverride RETIE_KELI = new StepOverride("tying Lady Keli up again",
            PrinceAliSupport::keliLooseAtDoor, PrinceAliSupport::retieKeli);

    private PrinceAliSupport() {
    }

    private static boolean dyeWig(QuestContext ctx) {
        if (Rs2Inventory.hasItem(ItemID.BLONDWIG)) return false;
        if (!Rs2Inventory.hasItem(ItemID.PLAINWIG) || !Rs2Inventory.hasItem(ItemID.YELLOWDYE)) return false;
        Microbot.status = "Dyeing the wig";
        if (Rs2Inventory.combine(ItemID.YELLOWDYE, ItemID.PLAINWIG)) {
            sleepUntil(() -> Rs2Inventory.hasItem(ItemID.BLONDWIG), 3000);
        }
        return true;
    }

    /**
     * True once the door step has run for {@link #RETIE_AFTER_MS} near the jail without getting into the cell, with a
     * rope to spare and Keli in sight. Keeps the step's start time in the context; it resets when the step changes.
     */
    private static boolean keliLooseAtDoor(QuestContext ctx) {
        boolean onDoorStep = ctx.stepText().toLowerCase(Locale.ROOT).contains("use the key on the prison door");
        WorldPoint here = Rs2Player.getWorldLocation();
        if (!onDoorStep || here == null || inCell(here)) {
            ctx.set(DOOR_SINCE, 0L);
            return false;
        }
        if (here.getPlane() != 0 || here.distanceTo(CELL_DOOR) > 10) return false;
        long since = ctx.get(DOOR_SINCE, 0L);
        long now = System.currentTimeMillis();
        if (since == 0L) {
            ctx.set(DOOR_SINCE, now);
            return false;
        }
        return now - since >= RETIE_AFTER_MS && Rs2Inventory.hasItem(ItemID.ROPE)
                && ctx.npc(NpcID.LADY_KELI_VIS) != null;
    }

    private static boolean retieKeli(QuestContext ctx) {
        Rs2NpcModel keli = ctx.npc(NpcID.LADY_KELI_VIS);
        if (keli == null) return false;
        log.info("[Questing] Prince Ali Rescue: the cell door still isn't open; tying Lady Keli up again");
        Microbot.status = "Tying Lady Keli up again";
        int ropes = Rs2Inventory.itemQuantity(ItemID.ROPE);
        if (Rs2Inventory.use(ItemID.ROPE) && sleepUntil(() -> Microbot.getClient().isWidgetSelected(), 1500)) {
            keli.click("");
            sleepUntil(() -> Rs2Inventory.itemQuantity(ItemID.ROPE) < ropes, 5000);
        }
        // Give the door another full wait either way.
        ctx.set(DOOR_SINCE, System.currentTimeMillis());
        return true;
    }

    private static boolean inCell(WorldPoint p) {
        return p.getPlane() == 0 && p.getX() >= CELL_MIN_X && p.getX() <= CELL_MAX_X
                && p.getY() >= CELL_MIN_Y && p.getY() <= CELL_MAX_Y;
    }
}
