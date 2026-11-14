package net.runelite.client.plugins.microbot.util.ectofuntus;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

import java.util.Locale;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * One-action-at-a-time steps for worshipping at the Ectofuntus in Port Phasmatys, shared by the Shadowfax
 * Ectofuntus script and the questing script (Animal Magnetism's ecto-tokens).
 * <ol>
 *   <li><b>Grind</b>: bones used on the loader at the top of the tower; the grinder then works through them one
 *       at a time (load, wind, empty the bin into a carried pot). The bin is emptied into a pot first at the start
 *       of a run, and again whenever the game says it's full, since bones can't be loaded on top of old bonemeal.</li>
 *   <li><b>Slime</b>: empty buckets used on the pool of slime in the basement (through the trapdoor); one use fills
 *       them all, a few seconds apart.</li>
 *   <li><b>Worship</b>: each Worship at the Ectofuntus takes one pot of bonemeal and one bucket of slime, gives
 *       Prayer xp and 5 ecto-tokens, held by the ghost disciples until claimed.</li>
 *   <li><b>Claim</b>: talking to a ghost disciple while wearing a ghostspeak amulet hands the tokens over.</li>
 * </ol>
 * Each step method does one thing (walk, click, or wait on the last click) and returns, so it fits a script loop.
 * Bones and bonemeal are matched by name, so any bone type the grinder takes works. Keep one instance per run: it
 * remembers when the grinder and the Ectofuntus last moved, so it waits instead of clicking them again.
 */
@Slf4j
public final class Ectofuntus {

    public static final int TOKENS_PER_WORSHIP = 5;

    public static final WorldPoint ECTOFUNTUS = new WorldPoint(3659, 3519, 0);
    public static final WorldPoint GRINDER = new WorldPoint(3659, 3524, 1);
    public static final WorldPoint SLIME_POOL = new WorldPoint(3682, 9888, 0);
    private static final int[] POOL_IDS = {ObjectID.AHOY_NEW_GREEN_FLOOR, ObjectID.AHOY_GLOOPBUBBLES,
            ObjectID.AHOY_GLOOPBUBBLES_LARGE, ObjectID.AHOY_GLOOPBUBBLES_LARGER};

    // The grinder (one bone at a time: load, wind, empty into a pot), the slime pool and the Ectofuntus all keep
    // going on their own after one click. Clicking again mid-cycle interrupts it, so a step only clicks once the
    // player has done nothing (no animation, no movement, no item change) for this long.
    private static final long GRIND_IDLE_MS = 4_200;
    private static final long SLIME_IDLE_MS = 3_600;
    // Worshipping is one click per offering and can be clicked again as soon as the last one used its bonemeal;
    // this only covers a click that didn't register.
    private static final long WORSHIP_RETRY_MS = 3_000;
    // Clicks in a row that started nothing (no item change since): the step gives up instead of clicking forever.
    private static final int MAX_FRUITLESS_CLICKS = 3;

    private enum Action { NONE, GRIND, SLIME, WORSHIP }

    // "You need to empty the grinder's bin before you put more bones in it": the bin holds bonemeal from an
    // earlier session (or another player's load), and bones can't go in until a pot empties it.
    private static final String BIN_FULL_MESSAGE = "empty the grinder's bin";
    private static volatile long binFullSeenAt;
    private static volatile boolean listening;
    // A new run checks the bin before loading: its state carries over from whoever used the grinder last.
    private boolean binChecked;
    private long binEmptiedAt;

    private String lastSignature = "";
    private long lastActivityAt;
    private Action lastAction = Action.NONE;
    private int mealAtWorshipClick = -1;
    private long worshipClickedAt;
    private String clickedSignature;
    private int fruitlessClicks;

    /** Forgets the waits, e.g. at the start of a run. */
    public void reset() {
        lastSignature = "";
        lastActivityAt = 0;
        clickedSignature = null;
        fruitlessClicks = 0;
        lastAction = Action.NONE;
        binChecked = false;
        binEmptiedAt = 0;
        listenForBinMessage();
    }

    /** Watches game messages for the full-bin one (registered once, for the client's lifetime). */
    private static synchronized void listenForBinMessage() {
        if (listening) return;
        Microbot.getEventBus().register(new ChatWatcher());
        listening = true;
    }

    /** Game message listener: notes when the game says the grinder's bin must be emptied first. */
    public static final class ChatWatcher {
        @Subscribe
        public void onChatMessage(ChatMessage event) {
            if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM) return;
            String message = Text.removeTags(event.getMessage()).toLowerCase(Locale.ROOT);
            if (message.contains(BIN_FULL_MESSAGE)) {
                binFullSeenAt = System.currentTimeMillis();
                log.info("[Ectofuntus] The grinder's bin is full; emptying it before loading more bones");
            }
        }
    }

    /** True when the bin should be emptied before loading: never checked this run, or the game says it's full. */
    private boolean binNeedsEmptying() {
        return !binChecked || binFullSeenAt > binEmptiedAt;
    }

    /**
     * True while the grinder may still be working through loaded bones: the last bone leaves the inventory before
     * its pot is filled, so "no bones left" isn't "done grinding" until the player has been idle a moment.
     */
    public boolean isGrinding() {
        return lastAction == Action.GRIND && idleFor() < GRIND_IDLE_MS;
    }

    /** True while buckets may still be filling at the pool. */
    public boolean isFillingBuckets() {
        return lastAction == Action.SLIME && idleFor() < SLIME_IDLE_MS;
    }

    /** True while the last Worship click hasn't used its bonemeal yet. */
    public boolean isWorshipping() {
        return lastAction == Action.WORSHIP && bonemeal() >= mealAtWorshipClick
                && System.currentTimeMillis() - worshipClickedAt < WORSHIP_RETRY_MS;
    }

    /**
     * Updates the activity clock and says how long the player has been idle. Activity is animating, moving, or
     * any change in bones, pots, bonemeal, buckets, slime or tokens.
     */
    private long idleFor() {
        long now = System.currentTimeMillis();
        String signature = bones() + "/" + pots() + "/" + bonemeal() + "/" + buckets() + "/" + slime() + "/"
                + unclaimed();
        if (!signature.equals(lastSignature)) {
            lastSignature = signature;
            lastActivityAt = now;
            // Items moved: whatever was clicked worked.
            if (clickedSignature != null && !clickedSignature.equals(signature)) fruitlessClicks = 0;
        }
        if (Rs2Player.isAnimating() || Rs2Player.isMoving()) lastActivityAt = now;
        return now - lastActivityAt;
    }

    /**
     * Before clicking: true if the last click (from the same item state) changed nothing, too many times in a row.
     * Counts this click otherwise.
     */
    private boolean tooManyFruitlessClicks(String what) {
        if (lastSignature.equals(clickedSignature)) {
            fruitlessClicks++;
        } else {
            fruitlessClicks = 0;
        }
        clickedSignature = lastSignature;
        if (fruitlessClicks >= MAX_FRUITLESS_CLICKS) {
            log.warn("[Ectofuntus] {} {} times in a row and nothing changed (bones/pots/meal/buckets/slime/tokens {}); "
                    + "giving up on it", what, fruitlessClicks, lastSignature);
            return true;
        }
        return false;
    }

    // --- Inventory ---

    private static boolean isBones(Rs2ItemModel item) {
        String name = item.getName();
        return name != null && name.toLowerCase(Locale.ROOT).endsWith("bones") && !item.isNoted();
    }

    private static boolean isBonemeal(Rs2ItemModel item) {
        String name = item.getName();
        return name != null && name.toLowerCase(Locale.ROOT).contains("bonemeal") && !item.isNoted();
    }

    /** Unnoted bones of any kind. */
    public static int bones() {
        return Rs2Inventory.count(Ectofuntus::isBones);
    }

    /** Pots of bonemeal of any kind. */
    public static int bonemeal() {
        return Rs2Inventory.count(Ectofuntus::isBonemeal);
    }

    public static int pots() {
        return Rs2Inventory.count(ItemID.POT_EMPTY);
    }

    public static int buckets() {
        return Rs2Inventory.count(ItemID.BUCKET_EMPTY);
    }

    public static int slime() {
        return Rs2Inventory.count(ItemID.BUCKET_ECTOPLASM);
    }

    /** Ecto-tokens carried. */
    public static int tokens() {
        return Rs2Inventory.itemQuantity(ItemID.ECTOTOKEN);
    }

    /** Ecto-tokens earned but not yet claimed from a disciple. */
    public static int unclaimed() {
        return Microbot.getVarbitValue(VarbitID.AHOY_ECTOTOKENS_BASE);
    }

    /** Worships possible with what's carried now (bonemeal and slime pairs). */
    public static int offeringsReady() {
        return Math.min(bonemeal(), slime());
    }

    public static boolean canGrind() {
        return bones() > 0 && pots() > 0;
    }

    // The plain amulet and the one the Old Crone enchants in Ghosts Ahoy both let you talk to ghosts.
    private static final int[] GHOSTSPEAK = {ItemID.AMULET_OF_GHOSTSPEAK, ItemID.AMULET_OF_GHOSTSPEAK_ENCHANTED};

    public static boolean hasGhostspeak() {
        return wearingGhostspeak() || carriedGhostspeak() != -1;
    }

    public static boolean wearingGhostspeak() {
        for (int id : GHOSTSPEAK) if (Rs2Equipment.isWearing(id)) return true;
        return false;
    }

    /** A ghostspeak amulet in the inventory, or -1. */
    public static int carriedGhostspeak() {
        for (int id : GHOSTSPEAK) if (Rs2Inventory.hasItem(id)) return id;
        return -1;
    }

    // --- Steps ---

    /**
     * Puts bones in the grinder's loader, or waits while its automatic cycle runs (load, wind, empty the bin into a
     * carried pot, then the next bone). False if the loader can't be found or loading keeps doing nothing.
     */
    public boolean grind() {
        listenForBinMessage();
        if (!walkTo(GRINDER, 3)) return true;
        long idle = idleFor();
        if (idle < GRIND_IDLE_MS) {
            Microbot.status = "Grinding bones (" + bonemeal() + " bonemeal, " + bones() + " bones left)";
            return true;
        }
        if (binNeedsEmptying()) return emptyBin();
        Rs2TileObjectModel loader = object(ObjectID.AHOY_GRINDER_LOADER);
        if (loader == null) {
            log.info("[Ectofuntus] No grinder loader in sight from {}", Rs2Player.getWorldLocation());
            return false;
        }
        Rs2ItemModel bones = Rs2Inventory.get(Ectofuntus::isBones);
        if (bones == null) return true;
        if (tooManyFruitlessClicks("Loaded bones into the grinder")) return false;
        log.info("[Ectofuntus] Loading the grinder ({} bones, {} pots, {} bonemeal; idle {} ms; grinder status {})",
                bones(), pots(), bonemeal(), idle, grinderStatus());
        Microbot.status = "Putting " + bones.getName().toLowerCase(Locale.ROOT) + " in the grinder";
        // Use the bones on the loader (never "Bury"); the grinder then works through them one at a time.
        if (useItemOn(bones, loader)) {
            lastAction = Action.GRIND;
            lastActivityAt = System.currentTimeMillis();
            sleepUntil(() -> Rs2Player.isAnimating() || Rs2Player.isMoving(), 3_000);
        }
        return true;
    }

    /**
     * Empties the grinder's bin into a carried pot. Done once at the start of a run (the bin may hold bonemeal
     * from an earlier session) and whenever the game says it's full. An already-empty bin just gives a message.
     * False if there's no empty pot to empty it into or the bin can't be found.
     */
    private boolean emptyBin() {
        if (pots() == 0) {
            if (binFullSeenAt > binEmptiedAt) {
                log.warn("[Ectofuntus] The grinder's bin is full and there's no empty pot to empty it into");
                return false;
            }
            binChecked = true;
            return true;
        }
        Rs2TileObjectModel bin = object(ObjectID.AHOY_GRINDER_CHUTE);
        if (bin == null) {
            log.info("[Ectofuntus] No grinder bin in sight from {}", Rs2Player.getWorldLocation());
            return false;
        }
        int meal = bonemeal();
        log.info("[Ectofuntus] Emptying the grinder's bin ({} pots, {} bonemeal; grinder status {})",
                pots(), meal, grinderStatus());
        Microbot.status = "Emptying the grinder's bin";
        if (bin.click("Empty") || bin.click()) {
            sleepUntil(() -> bonemeal() > meal, 4_000);
        }
        binChecked = true;
        binEmptiedAt = System.currentTimeMillis();
        lastActivityAt = binEmptiedAt;
        if (bonemeal() > meal) {
            log.info("[Ectofuntus] Emptied bonemeal from the bin");
        }
        return true;
    }

    /** The grinder's state varbit, logged to learn its values (none are documented). */
    private static int grinderStatus() {
        return Microbot.getVarbitValue(VarbitID.AHOY_GRINDER_STATUS);
    }

    /** Fills empty buckets at the pool of slime, or waits while they fill. False if the pool can't be found. */
    public boolean fillBuckets() {
        if (!walkTo(SLIME_POOL, 4)) return true;
        long idle = idleFor();
        if (idle < SLIME_IDLE_MS) {
            Microbot.status = "Filling buckets with slime (" + slime() + " full)";
            return true;
        }
        Rs2TileObjectModel pool = Microbot.getRs2TileObjectCache().query()
                .withIds(POOL_IDS)
                .nearestOnClientThread(12);
        if (pool == null) {
            log.info("[Ectofuntus] No pool of slime in sight from {}", Rs2Player.getWorldLocation());
            return false;
        }
        Rs2ItemModel bucket = Rs2Inventory.get(i -> i.getId() == ItemID.BUCKET_EMPTY);
        if (bucket == null) return true;
        if (tooManyFruitlessClicks("Used a bucket on the pool of slime")) return false;
        log.info("[Ectofuntus] Filling buckets ({} empty, {} slime; idle {} ms)", buckets(), slime(), idle);
        Microbot.status = "Filling buckets with slime (" + slime() + " full)";
        // One use fills every empty bucket carried, a few seconds apart.
        if (useItemOn(bucket, pool)) {
            lastAction = Action.SLIME;
            lastActivityAt = System.currentTimeMillis();
            sleepUntil(() -> Rs2Player.isAnimating() || Rs2Player.isMoving(), 3_000);
        }
        return true;
    }

    /**
     * Worships at the Ectofuntus, one click per offering. The next click goes as soon as the last one has used its
     * bonemeal (no waiting for the animation to end), which is as fast as a player clicking. False if the
     * Ectofuntus can't be found or clicking it keeps doing nothing.
     */
    public boolean worship() {
        if (!walkTo(ECTOFUNTUS, 4)) return true;
        idleFor();
        if (Rs2Player.isMoving() || isWorshipping()) {
            Microbot.status = "Worshipping the Ectofuntus (" + offeringsReady() + " left)";
            return true;
        }
        Rs2TileObjectModel ectofuntus = object(ObjectID.AHOY_ECTOFUNTUS);
        if (ectofuntus == null) {
            log.info("[Ectofuntus] No Ectofuntus in sight from {}", Rs2Player.getWorldLocation());
            return false;
        }
        if (tooManyFruitlessClicks("Worshipped at the Ectofuntus")) return false;
        int meal = bonemeal();
        Microbot.status = "Worshipping the Ectofuntus (" + offeringsReady() + " left)";
        // A short human beat between offerings.
        sleep(120, 420);
        if (ectofuntus.click("Worship")) {
            lastAction = Action.WORSHIP;
            mealAtWorshipClick = meal;
            worshipClickedAt = System.currentTimeMillis();
            lastActivityAt = worshipClickedAt;
            sleepUntil(() -> bonemeal() < meal, (int) WORSHIP_RETRY_MS);
        }
        return true;
    }

    /**
     * Claims the unclaimed tokens from a ghost disciple, wearing the ghostspeak amulet first. False if there's no
     * amulet or no disciple in reach.
     */
    public boolean claim() {
        if (Rs2Dialogue.hasContinue()) {
            Rs2Dialogue.clickContinue();
            return true;
        }
        if (!walkTo(ECTOFUNTUS, 6)) return true;
        // Ghost disciples only talk to players wearing the ghostspeak amulet.
        if (!wearingGhostspeak()) {
            int amulet = carriedGhostspeak();
            if (amulet == -1) {
                log.info("[Ectofuntus] No ghostspeak amulet to claim the tokens with");
                return false;
            }
            Microbot.status = "Putting on the ghostspeak amulet";
            Rs2Inventory.equip(amulet);
            sleepUntil(Ectofuntus::wearingGhostspeak, 2_000);
            return true;
        }
        if (Rs2Dialogue.isInDialogue()) {
            // The disciple's info menu: nothing left to hand over.
            if (Rs2Dialogue.hasDialogueOption("Thanks for your time", false)) {
                Rs2Dialogue.clickOption("Thanks for your time", false);
            }
            return true;
        }
        Rs2NpcModel disciple = Microbot.getRs2NpcCache().query().withId(NpcID.AHOY_DISCIPLE)
                .nearestReachableOnClientThread();
        if (disciple == null) {
            log.info("[Ectofuntus] No ghost disciple in reach from {}", Rs2Player.getWorldLocation());
            return false;
        }
        int tokens = tokens();
        Microbot.status = "Claiming " + unclaimed() + " ecto-tokens";
        if (disciple.click("Talk-to") && sleepUntil(Rs2Dialogue::isInDialogue, 5_000)) {
            sleepUntil(() -> tokens() > tokens, 4_000);
        }
        return true;
    }

    // --- Helpers ---

    /** True once within {@code distance} of {@code point} on its plane; otherwise starts walking and returns false. */
    public static boolean walkTo(WorldPoint point, int distance) {
        WorldPoint here = Rs2Player.getWorldLocation();
        if (here != null && here.getPlane() == point.getPlane() && here.distanceTo(point) <= distance) return true;
        Microbot.status = "Walking";
        Rs2Walker.walkTo(point, distance);
        return false;
    }

    private static Rs2TileObjectModel object(int id) {
        return Microbot.getRs2TileObjectCache().query().withId(id).nearestOnClientThread();
    }

    private static boolean useItemOn(Rs2ItemModel item, Rs2TileObjectModel object) {
        if (!Rs2Inventory.use(item)) return false;
        if (!sleepUntil(() -> Microbot.getClient().isWidgetSelected(), 1_500)) return false;
        return object.click();
    }
}
