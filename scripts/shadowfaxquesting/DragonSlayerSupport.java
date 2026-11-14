package net.runelite.client.plugins.microbot.shadowfaxquesting;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.questhelper.collections.ItemCollections;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;
import net.runelite.client.plugins.microbot.util.questing.DialogueAnswer;
import net.runelite.client.plugins.microbot.util.questing.MeleeGear;
import net.runelite.client.plugins.microbot.util.questing.QuestContext;
import net.runelite.client.plugins.microbot.util.questing.StepOverride;
import net.runelite.client.plugins.microbot.util.shopping.ItemNeed;
import net.runelite.client.plugins.microbot.util.shopping.ItemProcurer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * What Shadowfax Questing adds to Quest Helper's Dragon Slayer I. Quest Helper supplies the steps (map pieces,
 * Melzar's Maze, the ship, Crandor, Elvarg); this class makes the fights safe for a bot, per the OSRS Wiki:
 * <ul>
 *   <li><b>Gear</b>: melee weapon and armour planned by Attack/Defence level ({@link MeleeGear}); owned pieces first,
 *       the rest bought within the shopping budget. Worn before Melzar's Maze and before sailing to Crandor.</li>
 *   <li><b>Anti-dragon shield, always</b>: fetched from Duke Horacio as soon as the Guildmaster has told us about it
 *       (Quest Helper only sends us after all three map pieces), then worn for the rest of the quest. With it
 *       Elvarg's dragonfire hits at most 10 instead of 70.</li>
 *   <li><b>Food</b>: at least {@value #MAZE_FOOD} for the maze (lesser demon, level 82); for Elvarg a bank trip that
 *       banks everything else and takes {@value #ELVARG_FOOD} food, leaving room for her head, which only drops into
 *       a free slot.</li>
 *   <li><b>Prayer</b>: with 37 Prayer, Protect from Magic during the Elvarg fight (dragonfire max 10 → 7), and on a
 *       members world {@value #PRAYER_POTIONS} prayer potions (members-only) to keep it up, since each dragonfire
 *       drains 10% of prayer points.</li>
 *   <li><b>No antipoison</b>: nothing in the quest poisons (Elvarg, the maze monsters, Crandor's monsters and the
 *       deadly red spiders are all non-poisonous).</li>
 *   <li><b>Retreat</b>: out of food and below {@value #RETREAT_AT_PERCENT}% health in Elvarg's lair, climb back out
 *       and restock instead of fighting on.</li>
 * </ul>
 */
@Slf4j
final class DragonSlayerSupport {

    static final int MIN_COMBAT = 45;
    static final int MAZE_FOOD = 10;
    static final int ELVARG_FOOD = 20;
    static final int PRAYER_POTIONS = 3;
    // Elvarg hits up to 8 (slash) or 10 (shielded dragonfire) every 2.4s: eat early, leave before it's close.
    static final int EAT_AT_PERCENT = 60;
    static final int RETREAT_AT_PERCENT = 45;
    static final int DRINK_PRAYER_AT = 15;
    // Elvarg's head drops only into a free slot; one spare for safety.
    static final int FREE_SLOTS_FOR_HEAD = 2;
    // Kept back from the shopping budget for the quest's own items (silk, bowl, planks, nails, food...).
    static final long QUEST_ITEMS_RESERVE_GP = 25_000;

    private static final WorldPoint DUKE = new WorldPoint(3210, 3220, 1);
    private static final List<Integer> SHIELDS = ItemCollections.ANTIFIRE_SHIELDS.getItems();
    private static final List<String> DUKE_OPTIONS = List.of(
            "I seek a shield that will protect me from dragonbreath.",
            "Elvarg, the dragon of Crandor island!",
            "So, are you going to give me the shield or not?",
            "Yes");

    // --- Overrides, in the order the runner asks them (the first that applies gets the tick) ---

    static final StepOverride ELVARG_FIGHT = new StepOverride("fighting Elvarg",
            ctx -> inElvargLair(), DragonSlayerSupport::fightElvarg);

    static final StepOverride PRAYER_OFF = new StepOverride("turning Protect from Magic off",
            ctx -> !inElvargLair() && Rs2Prayer.isPrayerActive(Rs2PrayerEnum.PROTECT_MAGIC),
            ctx -> Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_MAGIC, false));

    static final StepOverride WEAR_SHIELD = new StepOverride("wearing the anti-dragon shield",
            ctx -> !wearingShield() && carriedShield() != -1,
            ctx -> ctx.equip(carriedShield()));

    static final StepOverride WEAR_GEAR = new StepOverride("wearing combat gear",
            ctx -> gearToWear(ctx) != null,
            ctx -> ctx.equip(gearToWear(ctx).getItemId()));

    static final StepOverride COMBINE_MAP = StepOverride.onStepText("combining the map",
            "Use the three map parts together", DragonSlayerSupport::combineMap);

    static final StepOverride GET_SHIELD_EARLY = new StepOverride("getting the anti-dragon shield early",
            DragonSlayerSupport::shieldIsDue, DragonSlayerSupport::fetchShield);

    static final StepOverride PREPARE_FOR_MAZE = new StepOverride("preparing for Melzar's Maze",
            ctx -> textHas(ctx, "Enter Melzar's Maze") && !mazeNeeds(ctx).isEmpty(),
            ctx -> {
                List<ItemNeed> needs = mazeNeeds(ctx);
                log.info("[Questing] Dragon Slayer: gearing up for Melzar's Maze: {}", needs);
                ctx.requestItems(needs, false);
                return true;
            });

    static final StepOverride PREPARE_FOR_ELVARG = new StepOverride("preparing for Elvarg",
            ctx -> textHas(ctx, "Prepare to fight Elvarg") && !onCrandor() && !elvargNeeds(ctx).isEmpty(),
            ctx -> {
                List<ItemNeed> needs = elvargNeeds(ctx);
                log.info("[Questing] Dragon Slayer: restocking for Elvarg: {}", needs);
                // Bank everything else: the trip leaves only food, potions, coins and gear, so the head has room.
                ctx.requestItems(needs, true);
                return true;
            });

    /**
     * Wormbrain (10,000) and Klarense (2,000 plus the hull repairs) are paid from the inventory, but coins only come
     * out of the bank on a bank trip, so fetch them (with the boat materials) before talking.
     */
    static final StepOverride BRING_COINS = new StepOverride("fetching coins",
            ctx -> !paymentNeeds(ctx).isEmpty(),
            ctx -> {
                List<ItemNeed> needs = paymentNeeds(ctx);
                log.info("[Questing] Dragon Slayer: \"{}\" needs {}", ctx.stepText(), needs);
                ctx.requestItems(needs, false);
                return true;
            });

    /** Duke Horacio's questions, answered only while we're beside him (one of them is a bare "Yes"). */
    static final DialogueAnswer DUKE_ANSWERS = (ctx, options) -> {
        WorldPoint here = Rs2Player.getWorldLocation();
        if (here == null || here.getPlane() != DUKE.getPlane() || here.distanceTo(DUKE) > 12) return null;
        for (String wanted : DUKE_OPTIONS) {
            for (String option : options) {
                if (option.trim().equalsIgnoreCase(wanted)) return option;
            }
        }
        return null;
    };

    /** The kit's gear: melee weapon and armour for the player's levels, within what's left of the shopping budget. */
    static List<ItemNeed> planGear(long budgetGp) {
        return MeleeGear.plan(Math.max(0, budgetGp - QUEST_ITEMS_RESERVE_GP));
    }

    private DragonSlayerSupport() {
    }

    // --- Elvarg ---

    private static boolean fightElvarg(QuestContext ctx) {
        if (!wearingShield()) {
            int shield = carriedShield();
            if (shield != -1) {
                Microbot.status = "Putting the anti-dragon shield on";
                return ctx.equip(shield);
            }
            log.warn("[Questing] Dragon Slayer: in Elvarg's lair without an anti-dragon shield; leaving");
            return retreat(ctx, "no anti-dragon shield");
        }

        if (Rs2Player.getRealSkillLevel(Skill.PRAYER) >= Rs2PrayerEnum.PROTECT_MAGIC.getLevel()) {
            int points = Rs2Prayer.getPrayerPoints();
            if (points <= DRINK_PRAYER_AT && Rs2Player.drinkPrayerPotion()) {
                return true;
            }
            if (points > 0 && !Rs2Prayer.isPrayerActive(Rs2PrayerEnum.PROTECT_MAGIC)) {
                Microbot.status = "Protect from Magic on";
                Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_MAGIC, true);
                return true;
            }
        }

        int health = (int) Math.round(Rs2Player.getHealthPercentage());
        if (health <= EAT_AT_PERCENT && !Rs2Inventory.getInventoryFood().isEmpty()) {
            Microbot.status = "Eating (Elvarg)";
            return Rs2Player.eatAt(EAT_AT_PERCENT);
        }
        if (health <= RETREAT_AT_PERCENT && Rs2Inventory.getInventoryFood().isEmpty()) {
            return retreat(ctx, "out of food at " + health + "% health");
        }
        // Otherwise Quest Helper's "Kill Elvarg" step attacks her.
        return false;
    }

    /** Climbs back over the rocks out of the lair and asks for a full restock. */
    private static boolean retreat(QuestContext ctx, String why) {
        Microbot.status = "Leaving Elvarg's lair: " + why;
        log.warn("[Questing] Dragon Slayer: leaving Elvarg's lair ({})", why);
        Rs2TileObjectModel rocks = ctx.object(ObjectID.DRAGON_SLAYER_QIP_STALAGTITE_JUMP);
        if (rocks != null && rocks.click()) {
            sleepUntil(() -> !inElvargLair(), 8000);
        }
        if (!inElvargLair()) {
            List<ItemNeed> needs = elvargNeeds(ctx);
            if (needs.isEmpty() && ctx.food() == null) {
                // No food configured to restock: stay out of the lair; the stall watchdog stops the quest.
                log.error("[Questing] Dragon Slayer: no food is configured, so there's nothing to restock for Elvarg");
            }
            ctx.requestItems(needs, true);
        }
        return true;
    }

    /** What's still needed before sailing to Crandor (or entering the volcano); empty when ready. */
    private static List<ItemNeed> elvargNeeds(QuestContext ctx) {
        List<ItemNeed> needs = new ArrayList<>(MeleeGear.missing(ctx.kit()));
        if (!wearingShield() && carriedShield() == -1) {
            needs.add(new ItemNeed(ownedShieldOr(ItemID.ANTIDRAGONBREATHSHIELD), "Anti-dragon shield", 1, true));
        }
        ItemNeed food = ctx.food();
        int carriedFood = Rs2Inventory.getInventoryFood().size();
        int potions = prayerPotionsWanted();
        int prayerCarried = prayerPotionsCarried();
        boolean lowFood = food != null && carriedFood < ELVARG_FOOD - 2;
        boolean lowPotions = potions > 0 && prayerCarried == 0;
        boolean cramped = Rs2Inventory.emptySlotCount() < FREE_SLOTS_FOR_HEAD;
        if (lowFood || lowPotions || cramped) {
            if (food != null) needs.add(food.withQuantity(ELVARG_FOOD));
            if (potions > 0) needs.add(ItemNeed.of(ItemID._4DOSEPRAYERRESTORE, "Prayer potion(4)", potions));
        }
        return needs;
    }

    // --- Payments ---

    private static List<ItemNeed> paymentNeeds(QuestContext ctx) {
        List<ItemNeed> needs = new ArrayList<>();
        int coins = 0;
        if (textHas(ctx, "Wormbrain") && textHas(ctx, "10,000")) coins = 10_000;
        if (textHas(ctx, "Klarense") && textHas(ctx, "buy his boat")) {
            coins = 2_000;
            addIfShort(needs, ItemID.WOODPLANK, "Plank", 3);
            addIfShort(needs, ItemID.NAILS, "Steel nails", 90);
            if (ItemCollections.HAMMER.getItems().stream().noneMatch(Rs2Inventory::hasItem)) {
                needs.add(ItemNeed.of(ItemID.HAMMER, "Hammer", 1));
            }
        }
        if (coins > 0 && Rs2Inventory.itemQuantity(ItemID.COINS) < coins) {
            needs.add(ItemNeed.of(ItemID.COINS, "Coins", coins));
        }
        // Only worth a trip when the coins are short; the rest rides along.
        return needs.stream().anyMatch(n -> n.getItemId() == ItemID.COINS) ? needs : List.of();
    }

    private static void addIfShort(List<ItemNeed> needs, int itemId, String name, int quantity) {
        if (Rs2Inventory.itemQuantity(itemId) < quantity) needs.add(ItemNeed.of(itemId, name, quantity));
    }

    // --- Melzar's Maze ---

    private static List<ItemNeed> mazeNeeds(QuestContext ctx) {
        List<ItemNeed> needs = new ArrayList<>();
        for (ItemNeed piece : MeleeGear.missing(ctx.kit())) {
            // Carried gear gets worn by WEAR_GEAR; only fetch what isn't on us.
            if (!Rs2Inventory.hasItem(piece.getItemId())) needs.add(piece);
        }
        ItemNeed food = ctx.food();
        if (food != null && Rs2Inventory.getInventoryFood().size() < MAZE_FOOD / 2) {
            needs.add(food.withQuantity(Math.max(MAZE_FOOD, food.getQuantity())));
        }
        return needs;
    }

    // --- Anti-dragon shield ---

    /**
     * After the Guildmaster's questions (all five asked) and before sailing, with no shield anywhere: get it now
     * rather than after the map pieces, so it's worn in the maze too. Quest Helper's own shield step is left to it.
     */
    private static boolean shieldIsDue(QuestContext ctx) {
        int stage = Microbot.getVarbitPlayerValue(VarPlayerID.DRAGONQUEST);
        if (stage < 2 || stage > 6) return false;
        if (!askedAllQuestions()) return false;
        if (textHas(ctx, "Duke Horacio") || inMelzarsMaze()) return false;
        // Not from underground (the Dwarven Mine door, the maze basement): finish what's started there first.
        WorldPoint here = Rs2Player.getWorldLocation();
        if (here == null || here.getY() > 9000) return false;
        return ownedShield() == -1;
    }

    private static boolean fetchShield(QuestContext ctx) {
        if (!ctx.walkTo(DUKE, 4)) return true;
        Rs2NpcModel duke = ctx.npc(NpcID.DUKE_OF_LUMBRIDGE);
        if (duke == null) return false;
        Microbot.status = "Asking Duke Horacio for an anti-dragon shield";
        ctx.talkTo(duke);
        return true;
    }

    /** The Guildmaster's five answers are flags in varp 177 that clear once asked (as Quest Helper reads them). */
    private static boolean askedAllQuestions() {
        int flags = Microbot.getVarbitPlayerValue(VarPlayerID.DRAGONQUESTVAR);
        for (int bit = 11; bit <= 15; bit++) {
            if ((flags & (1 << bit)) != 0) return false;
        }
        return true;
    }

    private static boolean wearingShield() {
        return SHIELDS.stream().anyMatch(Rs2Equipment::isWearing);
    }

    private static int carriedShield() {
        return SHIELDS.stream().filter(Rs2Inventory::hasItem).findFirst().orElse(-1);
    }

    /** A shield we own anywhere (worn, carried, banked), or -1. */
    private static int ownedShield() {
        return SHIELDS.stream().filter(id -> ItemProcurer.ownedCount(id, true) > 0).findFirst().orElse(-1);
    }

    private static int ownedShieldOr(int fallback) {
        int owned = ownedShield();
        return owned != -1 ? owned : fallback;
    }

    // --- Map ---

    /** Uses the map pieces on each other until they make the Crandor map. */
    private static boolean combineMap(QuestContext ctx) {
        if (Rs2Inventory.hasItem(ItemID.DRAGONMAP)) return false;
        int[] parts = {ItemID.MAPPART1, ItemID.MAPPART2, ItemID.MAPPART3};
        List<Integer> held = new ArrayList<>();
        for (int part : parts) {
            if (Rs2Inventory.hasItem(part)) held.add(part);
        }
        if (held.size() < 2) return false;
        Microbot.status = "Putting the map together";
        if (Rs2Inventory.combine(held.get(0), held.get(1))) {
            sleepUntil(() -> Rs2Inventory.hasItem(ItemID.DRAGONMAP) || !Rs2Inventory.hasItem(held.get(0)), 3000);
        }
        return true;
    }

    // --- Gear helpers ---

    /** A planned gear piece that's in the inventory but not worn, or null. */
    private static ItemNeed gearToWear(QuestContext ctx) {
        for (ItemNeed piece : MeleeGear.missing(ctx.kit())) {
            if (Rs2Inventory.hasItem(piece.getItemId())) return piece;
        }
        return null;
    }

    private static int prayerPotionsWanted() {
        boolean canPray = Rs2Player.getRealSkillLevel(Skill.PRAYER) >= Rs2PrayerEnum.PROTECT_MAGIC.getLevel();
        return canPray && ItemProcurer.onMembersWorld() ? PRAYER_POTIONS : 0;
    }

    private static int prayerPotionsCarried() {
        int count = 0;
        for (int id : ItemCollections.PRAYER_POTIONS.getItems()) {
            count += Rs2Inventory.count(id);
        }
        return count;
    }

    // --- Where we are ---

    private static boolean textHas(QuestContext ctx, String fragment) {
        return ctx.stepText().toLowerCase(Locale.ROOT).contains(fragment.toLowerCase(Locale.ROOT));
    }

    /** Elvarg's cave, east of the rocks you climb over (Quest Helper's zone). */
    private static boolean inElvargLair() {
        return within(2846, 9625, 2867, 9651, 0);
    }

    /** Crandor's surface or the dungeon under it: no bank within reach. */
    private static boolean onCrandor() {
        return within(2810, 3228, 2867, 3312, 0) || within(2821, 9600, 2872, 9663, 0);
    }

    private static boolean inMelzarsMaze() {
        WorldPoint here = Rs2Player.getWorldLocation();
        if (here == null) return false;
        boolean castle = here.getX() >= 2922 && here.getX() <= 2942 && here.getY() >= 3237 && here.getY() <= 3258;
        return castle || within(2920, 9639, 2942, 9658, 0);
    }

    private static boolean within(int x1, int y1, int x2, int y2, int plane) {
        WorldPoint here = Rs2Player.getWorldLocation();
        return here != null && here.getPlane() == plane
                && here.getX() >= x1 && here.getX() <= x2 && here.getY() >= y1 && here.getY() <= y2;
    }
}
