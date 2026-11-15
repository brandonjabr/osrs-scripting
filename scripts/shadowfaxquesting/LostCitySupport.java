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
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.magic.Rs2CombatSpells;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.magic.Runes;
import net.runelite.client.plugins.microbot.util.magic.Spell;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.questing.DialogueAnswer;
import net.runelite.client.plugins.microbot.util.questing.QuestContext;
import net.runelite.client.plugins.microbot.util.questing.StepOverride;
import net.runelite.client.plugins.microbot.util.reachable.Rs2Reachable;
import net.runelite.client.plugins.microbot.util.shopping.ItemNeed;
import net.runelite.client.plugins.microbot.util.shopping.ItemProcurer;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;
import net.runelite.client.plugins.skillcalculator.skills.MagicAction;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * What Shadowfax Questing adds to Quest Helper's Lost City, per the OSRS Wiki and Quest Helper's own notes:
 * <ul>
 *   <li><b>Entrana</b>: the monks only take players with no weapons or armour, so before the boat everything worn
 *       is banked and the inventory is cut down to runes, food, a knife and a Lumbridge teleport tablet.</li>
 *   <li><b>Magic only</b>: no staff is allowed either, so spells are cast by hand (autocast needs a staff): the best
 *       strike, bolt or blast for the player's Magic level, with runes for {@value #CASTS} casts. Auto retaliate is
 *       turned off in the dungeon so a hit doesn't send us punching, and back on after.</li>
 *   <li><b>Bronze axe</b>: no axe is allowed on Entrana; one comes from killing the dungeon's zombies (Quest
 *       Helper's loot handling picks it up).</li>
 *   <li><b>Tree Spirit</b> (level 101, melee): appears when the Dramen tree is first chopped. It's fought from behind
 *       one of the fungi near the tree, which Quest Helper says safespot it: we stand on the far side of a fungus
 *       from the spirit so it walks into the fungus. A spot where it still reaches us is dropped for another.</li>
 *   <li><b>Escape</b>: the dungeon's only way out is a teleport, so with no food left at low health, and after the
 *       branch is cut, the tablet is broken (Lumbridge Home Teleport if it's missing).</li>
 *   <li><b>The staff</b>: knife on the branch, then wielded for the shed south of Lumbridge.</li>
 * </ul>
 */
@Slf4j
final class LostCitySupport {

    static final int CASTS = 160;
    static final int FOOD = 15;
    // Runes left for fewer casts than this: restock before the boat rather than run dry in the dungeon.
    private static final int MIN_CASTS = 60;
    private static final int TELEPORT_AT_PERCENT = 40;
    // A manual cast every 5 ticks; recasting sooner just cancels the last one.
    private static final long CAST_GAP_MS = 2_700;
    private static final int SAFESPOT_SEARCH = 9;
    // Hits taken on one safespot before it's given up.
    private static final int MAX_HITS_ON_SPOT = 2;

    private static final Rs2CombatSpells[] SPELLS = {
            Rs2CombatSpells.FIRE_BLAST, Rs2CombatSpells.EARTH_BLAST, Rs2CombatSpells.WATER_BLAST,
            Rs2CombatSpells.WIND_BLAST, Rs2CombatSpells.FIRE_BOLT, Rs2CombatSpells.EARTH_BOLT,
            Rs2CombatSpells.WATER_BOLT, Rs2CombatSpells.WIND_BOLT, Rs2CombatSpells.FIRE_STRIKE,
            Rs2CombatSpells.EARTH_STRIKE, Rs2CombatSpells.WATER_STRIKE, Rs2CombatSpells.WIND_STRIKE};

    private static final int TABLET = ItemID.POH_TABLET_LUMBRIDGETELEPORT;
    private static final WorldPoint DRAMEN_TREE = new WorldPoint(2861, 9735, 0);
    private static final WorldPoint ENTRANA_LADDER = new WorldPoint(2820, 3374, 0);
    private static final String LADDER_WARNING = "risk I will have to take";

    // Quest Helper's zones.
    private static final WorldPoint DUNGEON_SW = new WorldPoint(2817, 9722, 0);
    private static final WorldPoint DUNGEON_NE = new WorldPoint(2879, 9784, 0);

    private static final Set<Integer> RUNE_IDS = Arrays.stream(Runes.values()).map(Runes::getItemId)
            .collect(Collectors.toSet());

    // --- Overrides, in the order the runner asks them (the first that applies gets the tick) ---

    /** Everything in the dungeon. Also keeps the runner from going to a bank for the axe while down there. */
    static final StepOverride DUNGEON = new StepOverride("in the Entrana dungeon",
            ctx -> inDungeon(), LostCitySupport::dungeon);

    static final StepOverride RETALIATE_BACK_ON = new StepOverride("turning auto retaliate back on",
            ctx -> !inDungeon() && ctx.get("retaliateOff", false),
            ctx -> {
                Rs2Combat.setAutoRetaliate(true);
                ctx.set("retaliateOff", false);
                return true;
            });

    static final StepOverride PREPARE_FOR_ENTRANA = new StepOverride("getting ready for Entrana",
            ctx -> ctx.stepText().contains("boat to Entrana") && !onEntrana()
                    && (!readyForEntrana(ctx) || Rs2Bank.isOpen()),
            LostCitySupport::prepare);

    // Boarding: see EntranaTrip for why it isn't left to Quest Helper's step.
    static final StepOverride BOARD_BOAT = new StepOverride("taking the boat to Entrana",
            ctx -> ctx.stepText().contains("boat to Entrana") && !onEntrana() && !inDungeon()
                    && !Rs2Bank.isOpen() && readyForEntrana(ctx),
            LostCitySupport::board);

    /** On the island: down the ladder, whatever the guide says (it can lag a step behind the boat). */
    static final StepOverride ON_ENTRANA = new StepOverride("heading for the Entrana dungeon",
            ctx -> onEntrana() && !ctx.has(ItemID.DRAMEN_BRANCH) && !ctx.has(ItemID.DRAMEN_STAFF),
            LostCitySupport::entrana);

    // Only with both carried: otherwise the runner's own item fetch brings the knife.
    static final StepOverride MAKE_STAFF = new StepOverride("making the dramen staff",
            ctx -> ctx.stepText().contains("knife on the dramen branch") && Rs2Inventory.hasItem(ItemID.KNIFE)
                    && Rs2Inventory.hasItem(ItemID.DRAMEN_BRANCH),
            LostCitySupport::makeStaff);

    static final StepOverride WIELD_STAFF = new StepOverride("wielding the dramen staff",
            ctx -> ctx.stepText().contains("Dramen Staff equipped") && !Rs2Equipment.isWearing(ItemID.DRAMEN_STAFF)
                    && Rs2Inventory.hasItem(ItemID.DRAMEN_STAFF),
            ctx -> ctx.equip(ItemID.DRAMEN_STAFF));

    /** The Port Sarim monks: yes, ready to go (only once nothing they'd refuse is carried). */
    static final DialogueAnswer MONK = (ctx, options) -> {
        // On Entrana the monks offer the trip back: never take it.
        if (!ctx.stepText().contains("boat to Entrana") || onEntrana()) return null;
        for (String option : options) {
            String lower = option.toLowerCase();
            if (lower.contains("ready to go") || lower.startsWith("yes")) return option;
        }
        return null;
    };

    private LostCitySupport() {
    }

    // --- Before the boat ---

    private static boolean prepare(QuestContext ctx) {
        // Nothing worn and nothing the monks refuse: just the bank to close.
        if (readyForEntrana(ctx)) {
            Rs2Bank.closeBank();
            return true;
        }
        List<ItemNeed> missing = missingSupplies(ctx);
        if (!missing.isEmpty()) {
            log.info("[Questing] Lost City: supplies for Entrana: {} ({})", missing, spell().name());
            // Banks everything else in the inventory first (the axe, any weapons), then takes these.
            ctx.requestItems(entranaKit(ctx), true);
            return true;
        }
        return EntranaTrip.bankForEntrana(i -> allowedOnEntrana(ctx, i));
    }

    private static boolean board(QuestContext ctx) {
        return EntranaTrip.board(ctx, "Lost City");
    }

    // --- On Entrana ---

    private static boolean entrana(QuestContext ctx) {
        if (Rs2Dialogue.hasDialogueOption(LADDER_WARNING, false)) {
            Rs2Dialogue.keyPressForDialogueOption(LADDER_WARNING, false);
            sleepUntil(LostCitySupport::inDungeon, 5_000);
            return true;
        }
        if (ctx.continueDialogue()) return true;
        if (Rs2Dialogue.isInDialogue()) {
            // Anything else here is a monk offering the trip back.
            Rs2Dialogue.clickOption(false, "No", "not yet", "Goodbye");
            return true;
        }

        Rs2TileObjectModel ladder = Microbot.getRs2TileObjectCache().query().withId(ObjectID.ENTRANALADDERTOP)
                .nearestOnClientThread(12);
        WorldPoint here = Rs2Player.getWorldLocation();
        if (ladder == null || here == null || here.getPlane() != 0) {
            Microbot.status = "Walking to the ladder on the north of Entrana";
            ctx.walkTo(ENTRANA_LADDER, 3);
            return true;
        }
        Microbot.status = "Climbing down into the Entrana dungeon";
        if (ladder.click("Climb-down") || ladder.click()) {
            sleepUntil(() -> inDungeon() || Rs2Dialogue.hasDialogueOption(LADDER_WARNING, false), 8_000);
        }
        return true;
    }

    private static boolean readyForEntrana(QuestContext ctx) {
        return missingSupplies(ctx).isEmpty() && Rs2Equipment.items().isEmpty()
                && Rs2Inventory.items().allMatch(i -> allowedOnEntrana(ctx, i));
    }

    /** Runes, food, a knife, the tablet, coins and stamina potions: nothing a monk calls a weapon or armour. */
    private static boolean allowedOnEntrana(QuestContext ctx, Rs2ItemModel item) {
        int id = item.getId();
        if (RUNE_IDS.contains(id) || id == ItemID.KNIFE || id == TABLET || id == ItemID.COINS) return true;
        if (ctx.food() != null && id == ctx.food().getItemId()) return true;
        String name = item.getName();
        return name != null && name.startsWith("Stamina potion");
    }

    /** What to take to Entrana: runes for {@value #CASTS} casts, food, knife and tablet. */
    private static List<ItemNeed> entranaKit(QuestContext ctx) {
        List<ItemNeed> kit = new ArrayList<>();
        for (Map.Entry<Integer, Integer> rune : runesFor(CASTS).entrySet()) {
            kit.add(ItemNeed.of(rune.getKey(), ItemProcurer.itemName(rune.getKey()), rune.getValue()));
        }
        kit.add(ItemNeed.of(ItemID.KNIFE, "Knife", 1));
        kit.add(ItemNeed.of(TABLET, "Lumbridge teleport", 1));
        if (ctx.food() != null) kit.add(ctx.food().withQuantity(Math.max(FOOD, ctx.food().getQuantity())));
        return kit;
    }

    private static List<ItemNeed> missingSupplies(QuestContext ctx) {
        List<ItemNeed> missing = new ArrayList<>();
        for (Map.Entry<Integer, Integer> rune : runesFor(MIN_CASTS).entrySet()) {
            if (Rs2Inventory.itemQuantity(rune.getKey()) < rune.getValue()) {
                missing.add(ItemNeed.of(rune.getKey(), ItemProcurer.itemName(rune.getKey()), rune.getValue()));
            }
        }
        if (!Rs2Inventory.hasItem(ItemID.KNIFE)) missing.add(ItemNeed.of(ItemID.KNIFE, "Knife", 1));
        if (!Rs2Inventory.hasItem(TABLET)) missing.add(ItemNeed.of(TABLET, "Lumbridge teleport", 1));
        if (ctx.food() != null && Rs2Inventory.itemQuantity(ctx.food().getItemId()) < FOOD / 2) {
            missing.add(ctx.food());
        }
        return missing;
    }

    /** The strongest standard strike, bolt or blast the player's Magic level allows. */
    static Rs2CombatSpells spell() {
        for (Rs2CombatSpells s : SPELLS) {
            if (s.hasRequiredLevel()) return s;
        }
        return Rs2CombatSpells.WIND_STRIKE;
    }

    private static Map<Integer, Integer> runesFor(int casts) {
        return Spell.convertRequiredRunes(spell().getRequiredRunes(casts));
    }

    // --- The dungeon ---

    private static boolean dungeon(QuestContext ctx) {
        if (ctx.continueDialogue()) return true;
        if (!ctx.get("retaliateOff", false)) {
            Rs2Combat.setAutoRetaliate(false);
            ctx.set("retaliateOff", true);
            return true;
        }

        boolean haveBranch = ctx.has(ItemID.DRAMEN_BRANCH) || ctx.has(ItemID.DRAMEN_STAFF);
        if (haveBranch) return teleportOut(ctx, "with the dramen branch");

        int health = (int) Math.round(Rs2Player.getHealthPercentage());
        if (health <= TELEPORT_AT_PERCENT && Rs2Inventory.getInventoryFood().isEmpty()) {
            log.warn("[Questing] Lost City: out of food at {}% health in the dungeon; teleporting out", health);
            return teleportOut(ctx, "out of food");
        }
        if (!hasRunesFor(1)) {
            log.warn("[Questing] Lost City: out of runes for {} in the dungeon; teleporting out", spell().name());
            return teleportOut(ctx, "out of runes");
        }

        Rs2NpcModel spirit = Microbot.getRs2NpcCache().query().withId(NpcID.TREE_SPIRIT).nearestOnClientThread();
        if (spirit != null) return fightSpirit(ctx, spirit);

        String text = ctx.stepText();
        if (text.contains("Kill zombies") && !ctx.has(ItemID.BRONZE_AXE)) return fightZombie(ctx);
        // Chopping the tree, cutting the branch, picking the axe up: Quest Helper's steps.
        return false;
    }

    private static boolean fightZombie(QuestContext ctx) {
        Rs2NpcModel zombie = Microbot.getRs2NpcCache().query()
                .withNameContains("Zombie")
                .where(z -> z.getInteracting() == Microbot.getClient().getLocalPlayer())
                .nearestOnClientThread();
        if (zombie == null) {
            zombie = Microbot.getRs2NpcCache().query().withNameContains("Zombie")
                    .where(z -> !z.isDead()).nearestReachableOnClientThread(15);
        }
        if (zombie == null) {
            Microbot.status = "Looking for zombies";
            ctx.walkTo(new WorldPoint(2843, 9760, 0), 4);
            return true;
        }
        Microbot.status = "Killing zombies for a bronze axe (" + spell().name().toLowerCase().replace('_', ' ') + ")";
        return cast(ctx, zombie);
    }

    /**
     * Fights the spirit from behind a fungus. The spirit only walks straight at us, so standing one tile past a
     * fungus on the line from the spirit leaves it stuck on the fungus while we cast.
     */
    private static boolean fightSpirit(QuestContext ctx, Rs2NpcModel spirit) {
        WorldPoint here = Rs2Player.getWorldLocation();
        WorldPoint at = spirit.getWorldLocation();
        if (here == null || at == null) return true;

        Set<WorldPoint> bad = ctx.get("badSafespots", new HashSet<WorldPoint>());
        ctx.set("badSafespots", bad);
        WorldPoint spot = ctx.get("safespot", (WorldPoint) null);

        // Hit while standing on the spot: it isn't safe from here.
        int hp = Rs2Player.getBoostedSkillLevel(Skill.HITPOINTS);
        int lastHp = ctx.get("spotHp", hp);
        ctx.set("spotHp", hp);
        if (spot != null && here.equals(spot) && hp < lastHp && at.distanceTo(here) <= 1) {
            int hits = ctx.get("spotHits", 0) + 1;
            ctx.set("spotHits", hits);
            if (hits >= MAX_HITS_ON_SPOT) {
                log.info("[Questing] Lost City: the Tree Spirit reaches {}; trying another spot", spot);
                bad.add(spot);
                ctx.set("safespot", null);
                ctx.set("spotHits", 0);
                spot = null;
            }
        }

        if (spot == null) {
            spot = findSafespot(at, bad);
            ctx.set("safespot", spot);
            ctx.set("spotHits", 0);
            if (spot != null) log.info("[Questing] Lost City: Tree Spirit at {}, safespot {}", at, spot);
        }
        if (spot != null && !here.equals(spot)) {
            Microbot.status = "Moving behind a fungus";
            WorldPoint target = spot;
            Rs2Walker.walkFastCanvas(target);
            sleepUntil(() -> target.equals(Rs2Player.getWorldLocation()), 2_400);
            return true;
        }
        Microbot.status = "Fighting the Tree Spirit" + (spot == null ? " (no safespot found)" : " from behind a fungus");
        return cast(ctx, spirit);
    }

    /** A free tile just past a fungus, with the fungus between it and the spirit, within casting range. */
    private static WorldPoint findSafespot(WorldPoint spirit, Set<WorldPoint> bad) {
        List<Rs2TileObjectModel> fungi = Microbot.getRs2TileObjectCache().query()
                .withNameContains("ungus").within(DRAMEN_TREE, SAFESPOT_SEARCH).toListOnClientThread();
        WorldPoint best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Rs2TileObjectModel fungus : fungi) {
            WorldPoint f = fungus.getWorldLocation();
            int dx = Integer.signum(f.getX() - spirit.getX());
            int dy = Integer.signum(f.getY() - spirit.getY());
            // Step away from the spirit along its larger offset; the smaller offset is how far off the line it is.
            boolean alongX = Math.abs(f.getX() - spirit.getX()) >= Math.abs(f.getY() - spirit.getY());
            WorldPoint tile = alongX ? f.dx(dx == 0 ? 1 : dx) : f.dy(dy == 0 ? 1 : dy);
            if (bad.contains(tile) || tile.distanceTo(spirit) > 9 || tile.distanceTo(spirit) < 2) continue;
            if (!Rs2Reachable.isReachableFromPlayer(tile)) continue;
            int offLine = alongX ? Math.abs(f.getY() - spirit.getY()) : Math.abs(f.getX() - spirit.getX());
            int score = offLine * 10 + tile.distanceTo(Rs2Player.getWorldLocation());
            if (score < bestScore) {
                bestScore = score;
                best = tile;
            }
        }
        return best;
    }

    /** One manual cast on the target, then waits out the cast before the next. */
    private static boolean cast(QuestContext ctx, Rs2NpcModel target) {
        long now = System.currentTimeMillis();
        if (now - ctx.get("lastCastAt", 0L) < CAST_GAP_MS) return true;
        if (!Rs2Magic.cast(spell().getMagicAction())) return true;
        if (!sleepUntil(() -> Microbot.getClient().isWidgetSelected(), 1_500)) return true;
        if (target.click()) {
            ctx.set("lastCastAt", now);
            sleepUntil(Rs2Player::isAnimating, 1_800);
        }
        return true;
    }

    private static boolean hasRunesFor(int casts) {
        for (Map.Entry<Integer, Integer> rune : runesFor(casts).entrySet()) {
            if (Rs2Inventory.itemQuantity(rune.getKey()) < rune.getValue()) return false;
        }
        return true;
    }

    private static boolean teleportOut(QuestContext ctx, String why) {
        WorldPoint before = Rs2Player.getWorldLocation();
        Microbot.status = "Teleporting out of the dungeon (" + why + ")";
        boolean started = Rs2Inventory.hasItem(TABLET)
                ? Rs2Inventory.interact(TABLET, "Break")
                : Rs2Magic.cast(MagicAction.LUMBRIDGE_HOME_TELEPORT);
        if (started) sleepUntil(() -> !inDungeon(), Rs2Inventory.hasItem(TABLET) ? 6_000 : 15_000);
        if (inDungeon()) log.warn("[Questing] Lost City: teleport from {} didn't leave the dungeon yet", before);
        return true;
    }

    // --- The staff ---

    private static boolean makeStaff(QuestContext ctx) {
        if (ctx.has(ItemID.DRAMEN_STAFF)) return false;
        if (!Rs2Inventory.hasItem(ItemID.KNIFE) || !Rs2Inventory.hasItem(ItemID.DRAMEN_BRANCH)) return false;
        Microbot.status = "Carving the dramen staff";
        if (Rs2Inventory.combine(ItemID.KNIFE, ItemID.DRAMEN_BRANCH)) {
            if (sleepUntil(Rs2Widget::isProductionWidgetOpen, 1_800)) Rs2Keyboard.keyPress(KeyEvent.VK_SPACE);
            sleepUntil(() -> Rs2Inventory.hasItem(ItemID.DRAMEN_STAFF), 4_000);
        }
        return true;
    }

    // --- Where we are ---

    private static boolean inDungeon() {
        return inside(DUNGEON_SW, DUNGEON_NE);
    }

    private static boolean onEntrana() {
        return EntranaTrip.onEntrana();
    }

    private static boolean inside(WorldPoint sw, WorldPoint ne) {
        WorldPoint p = Rs2Player.getWorldLocation();
        return p != null && p.getX() >= sw.getX() && p.getX() <= ne.getX() && p.getY() >= sw.getY()
                && p.getY() <= ne.getY() && p.getPlane() >= sw.getPlane() && p.getPlane() <= ne.getPlane();
    }
}
