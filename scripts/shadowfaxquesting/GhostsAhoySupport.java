package net.runelite.client.plugins.microbot.shadowfaxquesting;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.questhelper.steps.DetailedQuestStep;
import net.runelite.client.plugins.microbot.util.ectofuntus.Ectofuntus;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.questing.QuestContext;
import net.runelite.client.plugins.microbot.util.questing.StepOverride;
import net.runelite.client.plugins.microbot.util.shopping.ItemNeed;
import net.runelite.client.plugins.microbot.util.shopping.ItemProcurer;
import net.runelite.client.plugins.microbot.util.walker.Rs2CharterShips;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Ghosts Ahoy helpers, for the parts Quest Helper leaves to the player (checked against the OSRS Wiki):
 * <ul>
 *   <li><b>Ecto-tokens</b>: the barrier into Port Phasmatys costs 2 and the boat to Dragontooth 25 (31 in all
 *       without a Ring of Charos). Whenever a step lists more tokens than are carried, they're earned at the
 *       Ectofuntus (just outside the barrier) with the bones, pots and buckets in the kit, plus 2 spare for
 *       getting back in.</li>
 *   <li><b>Rune-Draw</b> with Robin: four wins (not in a row) get the signed bow. It always draws and never holds:
 *       whoever draws the death rune loses, and holding late can leave Robin stuck (a known bug).</li>
 *   <li><b>The bedsheet</b>: one bucket of slime from the pool under the Ectofuntus (it can't be bought), then
 *       the dyed sheet is worn for Gravingas and the villagers' signatures.</li>
 *   <li><b>The flag</b>: once the mast shows the three colours, each part is dyed with its colour; green, purple
 *       and orange are mixed from the red, blue and yellow brought (blue + yellow, red + blue, red + yellow), or
 *       bought if the primaries ran out.</li>
 *   <li><b>The rocks</b> to the third chest: jumped one at a time along Quest Helper's path (25 Agility).</li>
 *   <li>The enchanted ghostspeak amulet is worn for Necrovarus.</li>
 *   <li><b>Getting there</b>: from anywhere west of the Salve, a Catherby teleport tablet and a chartered ship to
 *       Port Phasmatys, which beats the walker's overland route and lands inside the barrier.</li>
 * </ul>
 */
@Slf4j
final class GhostsAhoySupport {

    // Tokens the quest is planned for: 31 (barrier + Dragontooth boat) plus spare for the odd villager.
    static final int TOKENS_WANTED = 40;
    private static final int SPARE_TOKENS = 2;
    // The walker's listed fare from Catherby to Port Phasmatys (cheaper with a Ring of Charos (a)).
    static final int CHARTER_FARE = 3_500;
    // One for the way in, one for coming back after a mid-quest bank trip.
    static final int TRIPS = 2;
    static final int CATHERBY_TABLET = ItemID.LUNAR_TABLET_CATHERBY_TELEPORT;
    private static final WorldPoint CATHERBY_DOCKS = new WorldPoint(2792, 3414, 0);
    private static final int MAX_TRAVEL_TRIES = 3;
    // The charter ship moors at Port Phasmatys with the player on deck (plane 1); the quay below is inside the town.
    private static final WorldPoint PHAS_QUAY = new WorldPoint(3702, 3503, 0);
    // Quest Helper's path across the rocks from the gangplank to the third chest.
    private static final WorldPoint[] ROCK_PATH = {
            new WorldPoint(3604, 3550, 0), new WorldPoint(3601, 3550, 0), new WorldPoint(3601, 3552, 0),
            new WorldPoint(3595, 3552, 0), new WorldPoint(3595, 3557, 0), new WorldPoint(3597, 3557, 0),
            new WorldPoint(3597, 3564, 0), new WorldPoint(3605, 3564, 0)
    };
    private static final Pattern DYE_STEP = Pattern.compile("Dye the (top|bottom|skull) of the model ship's flag (\\w+)\\.");

    private enum Dye {
        RED(ItemID.REDDYE, "Red dye", 0, 0),
        BLUE(ItemID.BLUEDYE, "Blue dye", 0, 0),
        YELLOW(ItemID.YELLOWDYE, "Yellow dye", 0, 0),
        GREEN(ItemID.GREENDYE, "Green dye", ItemID.BLUEDYE, ItemID.YELLOWDYE),
        PURPLE(ItemID.PURPLEDYE, "Purple dye", ItemID.REDDYE, ItemID.BLUEDYE),
        ORANGE(ItemID.ORANGEDYE, "Orange dye", ItemID.REDDYE, ItemID.YELLOWDYE);

        final int itemId;
        final String name;
        final int mixA;
        final int mixB;

        Dye(int itemId, String name, int mixA, int mixB) {
            this.itemId = itemId;
            this.name = name;
            this.mixA = mixA;
            this.mixB = mixB;
        }

        static Dye named(String colour) {
            for (Dye d : values()) {
                if (d.name().equalsIgnoreCase(colour)) return d;
            }
            return null;
        }
    }

    /** Away from Morytania with a step there: Catherby tablet, then the charter ship to Port Phasmatys. */
    static final StepOverride TRAVEL = new StepOverride("sailing to Port Phasmatys from Catherby",
            GhostsAhoySupport::needsVoyage, GhostsAhoySupport::voyage);

    /**
     * Quest Helper's "Enter Port Phasmatys" while already inside the walls, e.g. still on the charter ship's deck,
     * which its town zones (ground floor only) don't cover. Steps down to the quay so the guide moves on to the
     * actual step (Velorina) instead of sending the player out through the barrier for tokens.
     */
    static final StepOverride ARRIVAL = new StepOverride("stepping off the ship into Port Phasmatys",
            ctx -> {
                WorldPoint here = Rs2Player.getWorldLocation();
                return here != null && ctx.stepText().startsWith("Enter Port Phasmatys") && insideTown(here);
            },
            ctx -> {
                Microbot.status = "Stepping off the ship";
                Rs2Walker.walkTo(PHAS_QUAY, 1);
                sleepUntil(() -> {
                    WorldPoint p = Rs2Player.getWorldLocation();
                    return p != null && p.getPlane() == 0;
                }, 5_000);
                return true;
            });

    static final StepOverride TOKENS = new StepOverride("earning ecto-tokens at the Ectofuntus",
            ctx -> {
                int need = EctoTokenSupport.stepNeed(ctx);
                if (need <= 0 || Ectofuntus.tokens() >= need) return false;
                // The barrier toll isn't owed from inside the town (ARRIVAL steps off the ship instead).
                WorldPoint here = Rs2Player.getWorldLocation();
                return !(here != null && ctx.stepText().startsWith("Enter Port Phasmatys") && insideTown(here));
            },
            ctx -> EctoTokenSupport.earn(ctx, EctoTokenSupport.stepNeed(ctx) + SPARE_TOKENS, "Ghosts Ahoy"));

    static final StepOverride RUNEDRAW = new StepOverride("playing Rune-Draw with Robin",
            ctx -> Rs2Widget.isWidgetVisible(InterfaceID.AhoyRunedraw.RUNEDRAW_BTN_DRAW),
            GhostsAhoySupport::runeDraw);

    static final StepOverride SLIME_FOR_SHEET = new StepOverride("filling a bucket of slime for the bedsheet",
            ctx -> ctx.stepText().contains("bucket of slime on the bedsheet") && !ctx.has(ItemID.BUCKET_ECTOPLASM),
            EctoTokenSupport::fillOneBucket);

    static final StepOverride WEAR = new StepOverride("wearing the bedsheet or enchanted amulet",
            ctx -> {
                if (Rs2Inventory.hasItem(ItemID.AMULET_OF_GHOSTSPEAK_ENCHANTED)) return true;
                String text = ctx.stepText().toLowerCase(Locale.ROOT);
                return Rs2Inventory.hasItem(ItemID.AHOY_BEDSHEETGREEN)
                        && (text.contains("gravingas") || text.contains("ghost villagers"));
            },
            ctx -> QuestActions.wearIfCarried(ItemID.AMULET_OF_GHOSTSPEAK_ENCHANTED)
                    || QuestActions.wearIfCarried(ItemID.AHOY_BEDSHEETGREEN));

    static final StepOverride DYE_FLAG = new StepOverride("getting the right dye for the flag",
            ctx -> DYE_STEP.matcher(ctx.stepText()).find(),
            GhostsAhoySupport::dyeFlag);

    static final StepOverride ROCKS = StepOverride.onStepText("jumping across the rocks",
            "Jump across the rocks to the chest", GhostsAhoySupport::jumpRocks);

    private GhostsAhoySupport() {
    }

    // --- Kit ---

    /**
     * The token part of the kit: the ecto-tokens already owned (bank included), and bones, pots and buckets only for
     * the offerings needed to make up the rest. Plus one bucket for the bedsheet's slime.
     */
    static List<ItemNeed> planTokens(long budgetGp) {
        int owned = ItemProcurer.ownedCount(ItemID.ECTOTOKEN, true);
        List<ItemNeed> kit = new ArrayList<>();
        if (owned > 0) kit.add(ItemNeed.of(ItemID.ECTOTOKEN, "Ecto-token", Math.min(owned, TOKENS_WANTED)));
        int worships = EctoTokenSupport.worshipsFor(Math.max(0, TOKENS_WANTED - owned));
        if (worships > 0) {
            kit.add(ItemNeed.of(ItemID.BONES, "Bones", worships));
            kit.add(ItemNeed.of(ItemID.POT_EMPTY, "Pot", worships));
        }
        kit.add(ItemNeed.of(ItemID.BUCKET_EMPTY, "Bucket", worships + 1));
        log.info("[Questing] Ghosts Ahoy: {} ecto-tokens owned; bringing supplies for {} offerings", owned, worships);
        return kit;
    }

    // --- Getting to Port Phasmatys ---

    private static boolean needsVoyage(QuestContext ctx) {
        WorldPoint here = Rs2Player.getWorldLocation();
        if (here == null || inMorytania(here)) return false;
        if (ctx.get("voyageGaveUp", false)) return false;
        // Steps elsewhere (none in this quest so far) are left to the walker.
        WorldPoint target = stepPoint(ctx);
        return target == null || inMorytania(target);
    }

    /**
     * One leg per tick: break a Catherby tablet (or walk there without one), walk to the docks, charter. Gives the
     * route up for this quest, leaving it to the walker, after repeated failures or without the fare.
     */
    private static boolean voyage(QuestContext ctx) {
        WorldPoint here = Rs2Player.getWorldLocation();
        boolean nearCatherby = here.getPlane() == 0 && here.distanceTo(CATHERBY_DOCKS) <= 60;

        if (Rs2Inventory.itemQuantity(ItemID.COINS) < CHARTER_FARE) {
            return giveUp(ctx, "fewer than " + CHARTER_FARE + " coins for the charter");
        }
        if (!nearCatherby) {
            if (Rs2Inventory.hasItem(CATHERBY_TABLET)) {
                if (failed(ctx, "voyageTeleports")) return giveUp(ctx, "the Catherby tablet didn't teleport");
                Microbot.status = "Teleporting to Catherby";
                if (Rs2Inventory.interact(CATHERBY_TABLET, "Break")) {
                    sleepUntil(() -> {
                        WorldPoint p = Rs2Player.getWorldLocation();
                        return p != null && p.distanceTo(CATHERBY_DOCKS) <= 60;
                    }, 6_000);
                }
                return true;
            }
            Microbot.status = "Walking to Catherby (no teleport tablet)";
            Rs2Walker.walkTo(CATHERBY_DOCKS, 3);
            return true;
        }
        if (here.distanceTo(CATHERBY_DOCKS) > 6) {
            Microbot.status = "Walking to the Catherby docks";
            Rs2Walker.walkTo(CATHERBY_DOCKS, 3);
            return true;
        }
        if (failed(ctx, "voyageCharters")) return giveUp(ctx, "chartering from Catherby kept failing");
        int coinsBefore = Rs2Inventory.itemQuantity(ItemID.COINS);
        if (Rs2CharterShips.charter("Port Phasmatys")) {
            sleepUntil(() -> {
                WorldPoint p = Rs2Player.getWorldLocation();
                return p != null && inMorytania(p);
            }, 20_000);
            WorldPoint landed = Rs2Player.getWorldLocation();
            if (landed != null && inMorytania(landed)) {
                log.info("[Questing] Ghosts Ahoy: chartered to Port Phasmatys for {} coins",
                        coinsBefore - Rs2Inventory.itemQuantity(ItemID.COINS));
                ctx.set("voyageTeleports", 0);
                ctx.set("voyageCharters", 0);
                // Off the deck straight away: Quest Helper only counts the ground floor as inside the town.
                Rs2Walker.walkTo(PHAS_QUAY, 1);
            }
        }
        return true;
    }

    /** Counts an attempt under {@code key}; true once there have been too many. */
    private static boolean failed(QuestContext ctx, String key) {
        int tries = ctx.get(key, 0) + 1;
        ctx.set(key, tries);
        return tries > MAX_TRAVEL_TRIES;
    }

    private static boolean giveUp(QuestContext ctx, String why) {
        log.warn("[Questing] Ghosts Ahoy: not sailing from Catherby ({}); leaving the route to the walker", why);
        ctx.set("voyageGaveUp", true);
        return false;
    }

    private static WorldPoint stepPoint(QuestContext ctx) {
        if (!(ctx.activeStep() instanceof DetailedQuestStep)) return null;
        DetailedQuestStep step = (DetailedQuestStep) ctx.activeStep();
        return step.getDefinedPoint() == null ? null : step.getDefinedPoint().getWorldPoint();
    }

    /**
     * Inside Port Phasmatys' walls: Quest Helper's town zones (ground floor, from x 3653 east of the barrier) plus
     * the charter ship's deck at the quay.
     */
    static boolean insideTown(WorldPoint p) {
        int x = p.getX(), y = p.getY();
        if (p.getPlane() == 1) return x >= 3700 && x <= 3712 && y >= 3495 && y <= 3510;
        if (p.getPlane() != 0) return false;
        return (x >= 3653 && x <= 3710 && y >= 3457 && y <= 3507)
                || (x >= 3669 && x <= 3710 && y >= 3508 && y <= 3510)
                || (x >= 3672 && x <= 3710 && y >= 3511 && y <= 3513)
                || (x >= 3675 && x <= 3710 && y >= 3514 && y <= 3516)
                || (x >= 3684 && x <= 3693 && y >= 3517 && y <= 3532)
                || (x >= 3661 && x <= 3709 && y >= 3454 && y <= 3456);
    }

    /** East of the Salve: Port Phasmatys, the Ectofuntus and its basement, Dragontooth Island. */
    private static boolean inMorytania(WorldPoint p) {
        return p.getX() >= 3400 && ((p.getY() >= 3200 && p.getY() < 4000) || (p.getY() >= 9800 && p.getY() < 10000));
    }

    // --- Rune-Draw ---

    private static boolean runeDraw(QuestContext ctx) {
        Microbot.status = "Rune-Draw: drawing a rune";
        Rs2Widget.clickWidget(InterfaceID.AhoyRunedraw.RUNEDRAW_BTN_DRAW);
        // Robin takes his turn before the button works again.
        sleep(1_200, 2_200);
        return true;
    }

    // --- Flag ---

    /** Makes sure the dye the step wants is carried; then lets Quest Helper use it on the model ship. */
    private static boolean dyeFlag(QuestContext ctx) {
        Matcher m = DYE_STEP.matcher(ctx.stepText());
        if (!m.find()) return false;
        Dye dye = Dye.named(m.group(2));
        if (dye == null || ctx.has(dye.itemId)) return false;
        if (dye.mixA != 0 && ctx.has(dye.mixA) && ctx.has(dye.mixB)) {
            Microbot.status = "Mixing " + dye.name.toLowerCase(Locale.ROOT);
            if (Rs2Inventory.combine(dye.mixA, dye.mixB)) sleepUntil(() -> ctx.has(dye.itemId), 3_000);
            return true;
        }
        log.info("[Questing] Ghosts Ahoy: the {} needs {}; fetching it", m.group(1), dye.name);
        ctx.requestItems(List.of(ItemNeed.of(dye.itemId, dye.name, 1)), false);
        return true;
    }

    // --- Rocks ---

    /**
     * One jump per tick. The path's points are where the guide draws its line, not the rocks, so the rock to jump
     * is picked by progress: of the jumpable rocks close by, the nearest one that's further along the path to the
     * chest than the player, never one already jumped or stood on (which is what sent it back and forth before).
     */
    private static boolean jumpRocks(QuestContext ctx) {
        WorldPoint here = Rs2Player.getWorldLocation();
        if (here == null) return true;
        WorldPoint chest = ROCK_PATH[ROCK_PATH.length - 1];
        // At the chest: the guide's own step searches it.
        if (here.distanceTo(chest) <= 1) return false;
        if (Rs2Player.isAnimating() || Rs2Player.isMoving()) return true;

        Set<WorldPoint> used = ctx.get("rocksUsed", null);
        if (used == null) {
            used = new HashSet<>();
            ctx.set("rocksUsed", used);
        }
        used.add(here);
        Set<WorldPoint> skip = used;

        double mine = remaining(here);
        // Not on the rocks yet: get to the start of the path first.
        if (here.distanceTo(ROCK_PATH[0]) > 2 && mine >= remaining(ROCK_PATH[0]) - 0.5) {
            Microbot.status = "Walking to the first rock";
            // Starting over (e.g. after a fall into the water): every rock is fair game again.
            used.clear();
            Rs2Walker.walkTo(ROCK_PATH[0], 0);
            return true;
        }

        Rs2TileObjectModel rock = Microbot.getRs2TileObjectCache().query()
                .withActionContains("Jump")
                .within(6)
                .toListOnClientThread()
                .stream()
                .filter(o -> o.getWorldLocation() != null && !skip.contains(o.getWorldLocation()))
                .filter(o -> remaining(o.getWorldLocation()) < mine - 0.5)
                .min(Comparator.<Rs2TileObjectModel>comparingInt(o -> o.getWorldLocation().distanceTo(here))
                        .thenComparingDouble(o -> remaining(o.getWorldLocation())))
                .orElse(null);
        if (rock == null) {
            // No rock ahead within reach: walk on along the path (e.g. the last steps to the chest).
            WorldPoint ahead = nextPathPoint(here);
            Microbot.status = "Walking on towards the chest";
            log.info("[Questing] Ghosts Ahoy: no rock ahead of {}; walking to {}", here, ahead);
            Rs2Walker.walkFastCanvas(ahead);
            sleepUntil(Rs2Player::isMoving, 1_500);
            sleepUntil(() -> !Rs2Player.isMoving(), 4_000);
            return true;
        }

        WorldPoint target = rock.getWorldLocation();
        used.add(target);
        Microbot.status = "Jumping to the rock at " + target;
        log.info("[Questing] Ghosts Ahoy: jumping from {} to the rock at {} ({} tiles of path left)",
                here, target, Math.round(remaining(target)));
        if (!rock.click("Jump-to")) rock.click();
        sleepUntil(() -> {
            WorldPoint p = Rs2Player.getWorldLocation();
            return p != null && !p.equals(here);
        }, 5_000);
        sleepUntil(() -> !Rs2Player.isAnimating() && !Rs2Player.isMoving(), 3_000);
        WorldPoint landed = Rs2Player.getWorldLocation();
        if (landed != null) used.add(landed);
        return true;
    }

    /** Path length left from {@code p} to the chest, measured from its closest point on the path. */
    private static double remaining(WorldPoint p) {
        double best = Double.MAX_VALUE;
        double result = 0;
        for (int i = 0; i < ROCK_PATH.length - 1; i++) {
            WorldPoint a = ROCK_PATH[i], b = ROCK_PATH[i + 1];
            double dx = b.getX() - a.getX(), dy = b.getY() - a.getY();
            double len2 = dx * dx + dy * dy;
            double t = len2 == 0 ? 0 : ((p.getX() - a.getX()) * dx + (p.getY() - a.getY()) * dy) / len2;
            t = Math.max(0, Math.min(1, t));
            double px = a.getX() + t * dx, py = a.getY() + t * dy;
            double off = Math.hypot(p.getX() - px, p.getY() - py);
            if (off < best) {
                best = off;
                double left = Math.sqrt(len2) * (1 - t);
                for (int j = i + 1; j < ROCK_PATH.length - 1; j++) {
                    left += ROCK_PATH[j].distanceTo2D(ROCK_PATH[j + 1]);
                }
                result = left;
            }
        }
        return result;
    }

    /** The first path point further along than {@code p}. */
    private static WorldPoint nextPathPoint(WorldPoint p) {
        double mine = remaining(p);
        for (WorldPoint point : ROCK_PATH) {
            if (remaining(point) < mine - 0.5) return point;
        }
        return ROCK_PATH[ROCK_PATH.length - 1];
    }
}
