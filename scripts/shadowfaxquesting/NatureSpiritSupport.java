package net.runelite.client.plugins.microbot.shadowfaxquesting;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.questing.QuestContext;
import net.runelite.client.plugins.microbot.util.questing.StepOverride;
import net.runelite.client.plugins.microbot.util.shopping.ItemNeed;

import java.util.List;
import java.util.Locale;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Nature Spirit helpers, for the swamp steps Quest Helper only describes (checked against the OSRS Wiki):
 * <ul>
 *   <li><b>Washing bowl and mirror</b>: the bowl sits on a bench by the grotto with the mirror under it; both are
 *       picked up even where the usual "reachable" loot check says no.</li>
 *   <li><b>The mushroom</b>: stand next to a rotting log in Mort Myre, cast the druidic spell, pick the fungus that
 *       grows on it.</li>
 *   <li><b>The orange stone</b>: stand exactly on it before telling Filliman the puzzle is solved.</li>
 *   <li><b>Three ghasts</b>: bloom the blessed silver sickle next to rotting logs (costs Prayer points; a prayer
 *       potion is brought), pick the fungi, fill the druid pouch (3 at a time), then use the filled pouch on an
 *       invisible ghast to make it attackable and kill it. Ghasts that attack first are revealed by the pouch on
 *       their own. With no ghast around, it paces the swamp north of the grotto to draw one.</li>
 * </ul>
 */
@Slf4j
final class NatureSpiritSupport {

    private static final WorldPoint BENCH = new WorldPoint(3437, 3337, 0);
    private static final WorldPoint ORANGE_STONE = new WorldPoint(3440, 3335, 0);
    private static final WorldPoint GROTTO = new WorldPoint(3440, 3340, 0);
    // Swamp north of the grotto to pace while waiting for a ghast.
    private static final WorldPoint[] PACE = {new WorldPoint(3437, 3348, 0), new WorldPoint(3428, 3356, 0)};
    private static final int LOG_SEARCH = 20;
    private static final int FUNGI_PER_FILL = 3;

    static final StepOverride PICK_UP_BOWL = StepOverride.onStepText("picking up the washing bowl",
            "Pick up the washing bowl", ctx -> pickUp(ctx, ItemID.BOWL_EMPTY_FILLIMAN));

    static final StepOverride PICK_UP_MIRROR = StepOverride.onStepText("picking up the mirror",
            "Pick up the mirror", ctx -> pickUp(ctx, ItemID.MIRROR));

    static final StepOverride GROW_MUSHROOM = StepOverride.onStepText("growing a mushroom with the druidic spell",
            "Cast the druidic spell next to a rotten log", NatureSpiritSupport::growMushroom);

    static final StepOverride STAND_ON_ORANGE = StepOverride.onStepText("standing on the orange stone",
            "Stand on the orange stone", ctx -> !QuestActions.standOn(ORANGE_STONE));

    static final StepOverride GHASTS = new StepOverride("filling the druid pouch and killing ghasts", ctx -> {
        String text = ctx.stepText().toLowerCase(Locale.ROOT);
        return text.contains("bloom' the blessed sickle") || text.contains("filled druid pouch on a ghast")
                || text.contains("kill the ghast");
    }, NatureSpiritSupport::ghasts);

    private NatureSpiritSupport() {
    }

    private static boolean pickUp(QuestContext ctx, int itemId) {
        if (ctx.has(itemId)) return false;
        if (!ctx.walkTo(BENCH, 4)) return true;
        return QuestActions.pickUpNearby(itemId, 6);
    }

    // --- Mushroom ---

    private static boolean growMushroom(QuestContext ctx) {
        if (ctx.has(ItemID.MORTMYREMUSHROOM)) return false;
        if (ctx.continueDialogue()) return true;
        if (pickFungus()) return true;
        if (!ctx.has(ItemID.BLOOM_SPELL)) return false;
        return besideLog(ctx, () -> {
            Microbot.status = "Casting the druidic spell beside a rotting log";
            if (Rs2Inventory.interact(ItemID.BLOOM_SPELL, "Cast")) {
                sleepUntil(() -> fungusNearby() != null, 4_000);
            }
        });
    }

    // --- Ghasts ---

    private static boolean ghasts(QuestContext ctx) {
        if (ctx.continueDialogue()) return true;

        Rs2NpcModel visible = Microbot.getRs2NpcCache().query().withId(NpcID.GHAST_VIS)
                .nearestReachableOnClientThread(12);
        if (visible != null) {
            if (!Rs2Combat.inCombat()) {
                Microbot.status = "Attacking the ghast";
                ctx.attack(visible);
            }
            return true;
        }
        if (Rs2Combat.inCombat()) return true;

        int fungi = Rs2Inventory.count(ItemID.MORTMYREMUSHROOM);
        boolean pouchFull = ctx.has(ItemID.DRUID_POUCH);
        boolean pouchEmpty = ctx.has(ItemID.DRUID_POUCH_EMPTY);
        if (!pouchFull && !pouchEmpty) return false;

        if (fungi >= FUNGI_PER_FILL) {
            Microbot.status = "Filling the druid pouch";
            int pouch = pouchFull ? ItemID.DRUID_POUCH : ItemID.DRUID_POUCH_EMPTY;
            if (Rs2Inventory.interact(pouch, "Fill")) {
                sleepUntil(() -> Rs2Inventory.count(ItemID.MORTMYREMUSHROOM) < fungi, 3_000);
            }
            return true;
        }

        if (pouchFull) {
            Rs2NpcModel hidden = Microbot.getRs2NpcCache().query().withId(NpcID.GHAST_INVIS)
                    .nearestOnClientThread(8);
            if (hidden != null) {
                Microbot.status = "Using the druid pouch on a ghast";
                if (QuestActions.useItemOnNpc(ItemID.DRUID_POUCH, hidden)) {
                    sleepUntil(() -> Microbot.getRs2NpcCache().query().withId(NpcID.GHAST_VIS)
                            .nearestOnClientThread(12) != null, 4_000);
                }
                return true;
            }
            return pace(ctx);
        }

        // Pouch empty: grow and pick fungi.
        if (pickFungus()) return true;
        if (Rs2Player.getBoostedSkillLevel(Skill.PRAYER) <= 0) return restorePrayer(ctx);
        return besideLog(ctx, () -> {
            Microbot.status = "Blooming the blessed sickle beside a rotting log";
            if (Rs2Inventory.interact(ItemID.SILVER_SICKLE_BLESSED, "Bloom")) {
                sleepUntil(() -> fungusNearby() != null, 4_000);
            }
        });
    }

    private static boolean pace(QuestContext ctx) {
        int leg = ctx.get("natureSpiritPace", 0);
        WorldPoint target = PACE[leg % PACE.length];
        Microbot.status = "Pacing the swamp for a ghast";
        if (ctx.walkTo(target, 2)) ctx.set("natureSpiritPace", leg + 1);
        return true;
    }

    private static boolean restorePrayer(QuestContext ctx) {
        int[] potions = {ItemID._4DOSEPRAYERRESTORE, ItemID._3DOSEPRAYERRESTORE, ItemID._2DOSEPRAYERRESTORE,
                ItemID._1DOSEPRAYERRESTORE};
        for (int potion : potions) {
            if (Rs2Inventory.hasItem(potion)) {
                Microbot.status = "Drinking a prayer potion to bloom";
                Rs2Inventory.interact(potion, "Drink");
                sleepUntil(() -> Rs2Player.getBoostedSkillLevel(Skill.PRAYER) > 0, 2_400);
                return true;
            }
        }
        log.info("[Questing] Nature Spirit: out of Prayer points for Bloom; fetching prayer potions");
        ctx.requestItems(List.of(ItemNeed.of(ItemID._4DOSEPRAYERRESTORE, "Prayer potion(4)", 2)), false);
        return true;
    }

    // --- Logs ---

    private static Rs2TileObjectModel fungusNearby() {
        return Microbot.getRs2TileObjectCache().query().withId(ObjectID.LOG_DRUIDICSPIRIT2)
                .nearestOnClientThread(6);
    }

    /** Picks fungi grown on a log nearby; true if it clicked. */
    private static boolean pickFungus() {
        Rs2TileObjectModel fungus = fungusNearby();
        if (fungus == null || Rs2Inventory.isFull()) return false;
        int before = Rs2Inventory.count(ItemID.MORTMYREMUSHROOM);
        Microbot.status = "Picking mort myre fungi";
        if (fungus.click("Pick")) {
            sleepUntil(() -> Rs2Inventory.count(ItemID.MORTMYREMUSHROOM) > before, 4_000);
        }
        return true;
    }

    /** Walks next to the nearest rotting log, then runs {@code action} there. */
    private static boolean besideLog(QuestContext ctx, Runnable action) {
        Rs2TileObjectModel rotting = Microbot.getRs2TileObjectCache().query().withId(ObjectID.LOG_DRUIDICSPIRIT)
                .nearestOnClientThread(LOG_SEARCH);
        if (rotting == null) {
            ctx.walkTo(GROTTO, 3);
            return true;
        }
        WorldPoint here = Rs2Player.getWorldLocation();
        if (here == null || here.distanceTo(rotting.getWorldLocation()) > 1) {
            ctx.walkTo(rotting.getWorldLocation(), 1);
            return true;
        }
        action.run();
        return true;
    }
}
