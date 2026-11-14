package net.runelite.client.plugins.microbot.shadowfaxslayer;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.api.tileitem.models.Rs2TileItemModel;
import net.runelite.client.plugins.microbot.statemachine.StateMachineScript;
import net.runelite.client.plugins.microbot.statemachine.Transition;
import net.runelite.client.plugins.microbot.util.antiban.MouseIdleHabits;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.equipment.GearSetup;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.grandexchange.Rs2GrandExchange;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.magic.Rs2CombatSpells;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.npc.Rs2NpcManager;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;
import net.runelite.client.plugins.microbot.util.reachable.Rs2Reachable;
import net.runelite.client.plugins.microbot.util.safety.StuckGuard;
import net.runelite.client.plugins.microbot.util.shop.Rs2Shop;
import net.runelite.client.plugins.microbot.util.shopping.ItemNeed;
import net.runelite.client.plugins.microbot.util.shopping.ItemPrices;
import net.runelite.client.plugins.microbot.util.shopping.ItemProcurer;
import net.runelite.client.plugins.microbot.util.skills.slayer.Rs2Slayer;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.slayer.Task;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Does slayer tasks from Turael, Vannaka or Nieve start to finish.
 * <p>
 * SETUP → PLAN → GET_TASK / SKIP_TASK / SHOP / BANK (→ RESTOCK) → TRAVEL → FIGHT → PLAN …
 * <ul>
 *   <li><b>SETUP</b>: the gear window, where the melee, ranged and magic setups are saved from what's worn.</li>
 *   <li><b>PLAN</b>: reads the task (varps, like the enchanted gem shows), picks the guide, spot and setup, and works
 *       out which slayer shop items are missing (checked against the inventory, equipment and the bank, so nothing
 *       owned is bought again).</li>
 *   <li><b>GET_TASK</b>: walks to the master and asks for an assignment. <b>SKIP_TASK</b>: Turael skip for a task
 *       the script can't do.</li>
 *   <li><b>SHOP</b>: buys the missing slayer equipment (the enchanted gem first of all) from the nearest master,
 *       taking coins from the bank if needed.</li>
 *   <li><b>BANK</b>: swaps into the task's setup plus its protective gear, takes the gem, finisher items, light,
 *       supplies, potions, runes and food. <b>RESTOCK</b>: buys on the GE what the bank lacks: fish, or a candle + tinderbox (lit
 *       at the bank) and a rope for the Lumbridge Swamp Caves.</li>
 *   <li><b>TRAVEL</b>: to the spot, using teleports from the bank when faster. <b>FIGHT</b>: kills task monsters,
 *       eating, praying, finishing gargoyles and lizards, looting, and checking the gem now and then.</li>
 *   <li><b>FINISH</b>: stop condition or a problem: logs out and stops.</li>
 * </ul>
 */
@Slf4j
@Singleton
public class SlayerScript extends StateMachineScript<SlayerScript.State> {

    enum State { SETUP, PLAN, GET_TASK, SKIP_TASK, SHOP, BANK, RESTOCK, TRAVEL, FIGHT, FINISH }

    /** Fish bought on the GE when the bank has no food: the cheapest one suited to the Hitpoints level. */
    private enum Fish {
        TUNA("Tuna", ItemID.TUNA, 30),
        LOBSTER("Lobster", ItemID.LOBSTER, 60),
        SWORDFISH("Swordfish", ItemID.SWORDFISH, 80),
        MONKFISH("Monkfish", ItemID.MONKFISH, 99);

        final String itemName;
        final int itemId;
        final int maxHitpoints;

        Fish(String itemName, int itemId, int maxHitpoints) {
            this.itemName = itemName;
            this.itemId = itemId;
            this.maxHitpoints = maxHitpoints;
        }

        static Fish forHitpoints(int level) {
            for (Fish fish : values()) {
                if (level <= fish.maxHitpoints) return fish;
            }
            return MONKFISH;
        }
    }

    // Lit light sources by id: a lantern's lit and unlit versions have the same name.
    private static final Set<Integer> LIT_LIGHTS = Set.of(
            ItemID.LIT_CANDLE, ItemID.LIT_BLACK_CANDLE, ItemID.TORCH_LIT, ItemID.CANDLE_LANTERN_LIT,
            ItemID.CANDLE_LANTERN_BLACK_LIT, ItemID.OIL_LANTERN_LIT, ItemID.BULLSEYE_LANTERN_LIT,
            ItemID.BULLSEYE_LANTERN_LIT_LUNAR_QUEST, ItemID.TOG_SAPPHIRE_LANTERN_LIT);
    // Worn items that light the way and have no unlit version.
    private static final String[] LIGHT_GEAR = {
            "Firemaking cape", "Firemaking cape(t)", "Max cape", "Abyssal lantern",
            "Kandarin headgear 1", "Kandarin headgear 2", "Kandarin headgear 3", "Kandarin headgear 4"
    };
    // Lumbridge Swamp Caves entrance: a rope is tied to it once per account (varbit 279 set after).
    private static final WorldPoint SWAMP_HOLE = new WorldPoint(3169, 3172, 0);
    private static final int SWAMP_HOLE_OBJECT = 5947;

    // Watchdog: nothing changing for this long (xp, position, inventory, task count) means stuck.
    private static final long STALL_MS = 180_000;
    // No task monster at all around a spot this long: try the task's next spot.
    private static final long EMPTY_SPOT_MS = 120_000;
    // Waiting for respawns this long: move around the spot a little (wakes crabs, finds stragglers).
    private static final long WANDER_AFTER_MS = 25_000;
    private static final int MAX_EQUIP_TRIES = 3;
    private static final int MAX_DIALOGUE_TRIES = 6;
    private static final int MAX_SHOP_FAILURES = 4;
    private static final int GE_MARKUP_PERCENT = 10;
    private static final int MAX_ICE_COOLERS = 20;
    // How long the antiban cooldown may idle the script out of combat, picked at random per pause.
    private static final long MIN_COOLDOWN_MS = 1_500;
    private static final long MAX_COOLDOWN_MS = 6_000;
    // Loot that failed to pick up this many times is passed over for a while.
    private static final int MAX_PICKUP_ATTEMPTS = 2;
    private static final long SKIP_LOOT_MS = 120_000;

    @Inject
    private SlayerStats stats;

    @Inject
    private GearStore gearStore;

    @Inject
    private Client client;

    private SlayerConfig config;

    // Setup window.
    private volatile boolean setupOpen;
    private volatile boolean setupDone;
    private GearSetupDialog dialog;

    // Plan.
    private boolean planned;
    private Master master;
    private TaskGuide guide;
    private TaskGuide preparedFor;
    private TaskGuide.Spot spot;
    private CombatStyle style;
    private GearSetup gear = GearSetup.empty();
    private String taskName;
    private String unsupported;
    private final Map<ShopItem, Integer> shopList = new EnumMap<>(ShopItem.class);
    private final Set<TaskGuide.Spot> badSpots = new HashSet<>();
    // Set once a bank visit has shown what's in the bank, so owned items are never bought.
    private boolean bankSeen;
    // Whether the current plan was made knowing the bank (only then does a bank visit gear up).
    private boolean planKnewBank;

    // Per-state progress.
    private boolean taskGotten;
    private boolean skipDone;
    private String skipFrom;
    private boolean shopDone;
    private int shopFailures;
    private boolean bankDone;
    private boolean depositedThisVisit;
    private final Map<Integer, Integer> equipTries = new HashMap<>();
    private final Set<String> withdrawTried = new HashSet<>();
    private final Set<String> warned = new HashSet<>();
    private boolean restockNeeded;
    private boolean foodRestockFailed;
    // Whether the running restock is for trip items (candle, tinderbox, rope) rather than food.
    private boolean restockingTripItems;
    private boolean tripBuyFailed;
    private ItemProcurer procurer;
    private int dialogueTries;

    // Fight.
    private long noTargetsSince;
    private long waitingSince;
    private long lastFinisherAt;
    private int killsUntilGemCheck;
    private boolean gemCheckDue = true;
    private boolean autocastChecked;
    private final Map<String, Integer> pickupFailures = new ConcurrentHashMap<>();
    private final Map<String, Long> skippedLoot = new ConcurrentHashMap<>();

    // Tracking.
    private int lastCount = -1;
    private String failure;
    private String doneReason;
    private boolean finishing;
    private long cooldownDeadline;

    private State watchedState;
    private String progressSignature = "";
    private long lastProgressAt;

    @Override
    protected State initialState() {
        return State.SETUP;
    }

    @Override
    protected List<Transition<State>> defineTransitions() {
        return List.of(
                Transition.<State>from(State.SETUP)
                        .when(() -> setupDone, "setupDone")
                        .because("Gear setups ready")
                        .goTo(State.PLAN),

                Transition.<State>from(State.PLAN)
                        .when(this::shouldFinish, "failure or stop condition")
                        .because("Stopping")
                        .goTo(State.FINISH),
                Transition.<State>from(State.PLAN)
                        .when(() -> planned && taskName == null, "no task")
                        .because("Need a new task")
                        .goTo(State.GET_TASK),
                Transition.<State>from(State.PLAN)
                        .when(() -> planned && unsupported != null, "task not supported")
                        .because("Can't do this task: skipping it")
                        .goTo(State.SKIP_TASK),
                Transition.<State>from(State.PLAN)
                        .when(() -> planned && !bankSeen, "bank not seen yet")
                        .because("Checking the bank before buying anything")
                        .goTo(State.BANK),
                Transition.<State>from(State.PLAN)
                        .when(() -> planned && !shopList.isEmpty(), "slayer items missing")
                        .because("Buying slayer equipment")
                        .goTo(State.SHOP),
                Transition.<State>from(State.PLAN)
                        .when(() -> planned && preparedFor != guide, "not geared for this task")
                        .because("Gearing up for the task")
                        .goTo(State.BANK),
                Transition.<State>from(State.PLAN)
                        .when(() -> planned && needsBankNow(), "out of supplies")
                        .because("Restocking")
                        .goTo(State.BANK),
                Transition.<State>from(State.PLAN)
                        .when(() -> planned, "planned")
                        .because("Heading to the task")
                        .goTo(State.TRAVEL),

                Transition.<State>from(State.GET_TASK)
                        .when(this::shouldFinish, "failure")
                        .because("Couldn't get a task")
                        .goTo(State.FINISH),
                Transition.<State>from(State.GET_TASK)
                        .when(() -> taskGotten, "taskGotten")
                        .because("Got a new task")
                        .goTo(State.PLAN),

                Transition.<State>from(State.SKIP_TASK)
                        .when(this::shouldFinish, "failure")
                        .because("Couldn't skip the task")
                        .goTo(State.FINISH),
                Transition.<State>from(State.SKIP_TASK)
                        .when(() -> skipDone, "skipDone")
                        .because("Task skipped")
                        .goTo(State.PLAN),

                Transition.<State>from(State.SHOP)
                        .when(this::shouldFinish, "failure")
                        .because("Couldn't buy slayer equipment")
                        .goTo(State.FINISH),
                Transition.<State>from(State.SHOP)
                        .when(() -> shopDone, "shopDone")
                        .because("Slayer equipment bought")
                        .goTo(State.PLAN),

                Transition.<State>from(State.BANK)
                        .when(this::shouldFinish, "failure")
                        .because("Couldn't gear up")
                        .goTo(State.FINISH),
                Transition.<State>from(State.BANK)
                        .when(() -> restockNeeded, "missing from the bank")
                        .because("Buying what the bank is missing")
                        .goTo(State.RESTOCK),
                Transition.<State>from(State.BANK)
                        .when(() -> bankDone, "bankDone")
                        .because("Geared up")
                        .goTo(State.PLAN),

                Transition.<State>from(State.RESTOCK)
                        .when(() -> procurer == null, "restock finished")
                        .because("Back to the bank with what was bought")
                        .goTo(State.BANK),

                Transition.<State>from(State.TRAVEL)
                        .when(this::shouldFinish, "failure")
                        .because("Stopping")
                        .goTo(State.FINISH),
                Transition.<State>from(State.TRAVEL)
                        .when(this::taskOver, "task over")
                        .because("Task finished")
                        .goTo(State.PLAN),
                Transition.<State>from(State.TRAVEL)
                        .when(this::needsBankNow, "out of supplies")
                        .because("Out of supplies on the way")
                        .goTo(State.BANK),
                Transition.<State>from(State.TRAVEL)
                        .when(this::atSpot, "atSpot()")
                        .because("At the task spot")
                        .goTo(State.FIGHT),

                Transition.<State>from(State.FIGHT)
                        .when(this::shouldFinish, "failure")
                        .because("Stopping")
                        .goTo(State.FINISH),
                Transition.<State>from(State.FIGHT)
                        .when(() -> taskOver() && !Rs2Combat.inCombat(), "task over")
                        .because("Task finished")
                        .goTo(State.PLAN),
                Transition.<State>from(State.FIGHT)
                        .when(this::needsBankNow, "out of supplies")
                        .because("Out of supplies")
                        .goTo(State.BANK),
                Transition.<State>from(State.FIGHT)
                        .when(() -> !planned, "spot given up")
                        .because("No task monsters at this spot")
                        .goTo(State.PLAN),
                Transition.<State>from(State.FIGHT)
                        .when(() -> !nearSpot(), "left the spot")
                        .because("Wandered off the spot")
                        .goTo(State.TRAVEL)
        );
    }

    @Override
    protected void onState(State state) {
        switch (state) {
            case SETUP:
                setup();
                break;
            case PLAN:
                plan();
                break;
            case GET_TASK:
                getTask();
                break;
            case SKIP_TASK:
                skipTask();
                break;
            case SHOP:
                shop();
                break;
            case BANK:
                bank();
                break;
            case RESTOCK:
                restock();
                break;
            case TRAVEL:
                travel();
                break;
            case FIGHT:
                fight();
                break;
            case FINISH:
                finish();
                break;
        }
    }

    @Override
    protected void onTransition(State from, State to, String reason) {
        super.onTransition(from, to, reason);
        if (from == State.FIGHT) prayersOff();
        switch (to) {
            case PLAN:
                planned = false;
                break;
            case GET_TASK:
                taskGotten = false;
                dialogueTries = 0;
                break;
            case SKIP_TASK:
                skipDone = false;
                dialogueTries = 0;
                skipFrom = taskName;
                break;
            case SHOP:
                shopDone = false;
                shopFailures = 0;
                break;
            case BANK:
                bankDone = false;
                depositedThisVisit = false;
                restockNeeded = false;
                equipTries.clear();
                withdrawTried.clear();
                break;
            case FIGHT:
                noTargetsSince = 0;
                waitingSince = 0;
                autocastChecked = false;
                break;
            default:
                break;
        }
    }

    // --- Setup ---

    private void setup() {
        if (setupOpen) {
            Microbot.status = "Waiting for the gear setup window";
            return;
        }
        if (!config.openGearSetup() && gearStore.hasAny()) {
            setupDone = true;
            return;
        }
        openGearSetup();
    }

    /** Opens the gear setup window (from the start-up state or the hotkey); the script pauses while it's open. */
    void openGearSetup() {
        if (setupOpen) {
            SwingUtilities.invokeLater(() -> {
                if (dialog != null && dialog.isDisplayable()) dialog.toFront();
            });
            return;
        }
        setupOpen = true;
        SwingUtilities.invokeLater(() -> {
            Window owner = client.getCanvas() != null ? SwingUtilities.getWindowAncestor(client.getCanvas()) : null;
            dialog = new GearSetupDialog(owner, gearStore, this::gearSetupClosed);
            dialog.setVisible(true);
        });
    }

    private void gearSetupClosed() {
        dialog = null;
        gearStore.invalidate();
        // Gear may have changed: re-gear at the bank before the next fight.
        preparedFor = null;
        planned = false;
        if (!gearStore.hasAny()) {
            log.warn("[Slayer] No gear setup saved: fighting in whatever is worn");
        }
        setupDone = true;
        setupOpen = false;
    }

    // --- Plan ---

    private void plan() {
        if (Rs2Shop.isOpen()) {
            Rs2Shop.closeShop();
            return;
        }
        master = chooseMaster();
        unsupported = null;
        shopList.clear();
        planKnewBank = bankSeen;

        int count = Rs2Slayer.getSlayerTaskSize();
        String name = count > 0 ? Rs2Slayer.getSlayerTask() : null;
        if (count <= 0 || name == null) {
            taskName = null;
            guide = null;
            stats.setTask("None", 0, 0);
            stats.setPlan(master.label(), "-", "-");
            planned = true;
            return;
        }
        taskName = name;
        stats.setTask(pretty(name), count, Microbot.getVarbitPlayerValue(VarPlayerID.SLAYER_COUNT_ORIGINAL));

        TaskGuide next = TaskGuide.forTaskName(name);
        if (next != guide) {
            badSpots.clear();
            preparedFor = null;
            gemCheckDue = true;
        }
        guide = next;
        if (guide == null) {
            unsupported = name + " isn't supported yet";
            planned = true;
            return;
        }
        if (inSkipList(name)) {
            unsupported = name + " is on your skip list";
            planned = true;
            return;
        }

        spot = guide.spots().stream().filter(s -> !badSpots.contains(s)).findFirst().orElse(null);
        if (spot == null) {
            unsupported = guide.spots().isEmpty()
                    ? name + " has no spot for a " + (ItemProcurer.onMembersWorld() ? "members" : "free-to-play") + " world"
                    : "no " + name.toLowerCase(Locale.ROOT) + " found at any known spot";
            planned = true;
            return;
        }

        style = chooseStyle();
        gear = gearStore.get(style);

        if (guide.light() && bankSeen && !hasLightSource() && countLightInBank() == 0 && !canMakeCandle()
                && !canBuyTripItems()) {
            unsupported = name + " needs a lit light source (lit candle, torch or lantern) and none was found"
                    + (config.buySlayerItems() ? "; buying a candle and tinderbox failed" : "");
            planned = true;
            return;
        }
        if (needsSwampRope() && bankSeen && !Rs2Inventory.hasItem(ItemID.ROPE) && Rs2Bank.count(ItemID.ROPE) <= 0
                && !canBuyTripItems()) {
            unsupported = name + " needs a rope for the Lumbridge Swamp Caves entrance and none was found";
            planned = true;
            return;
        }

        if (bankSeen) {
            String problem = computeShopList(count);
            if (problem != null) {
                unsupported = problem;
                shopList.clear();
                planned = true;
                return;
            }
        }

        stats.setPlan(master.label(), spot.label(), style.label() + (gear.isEmpty() ? " (worn)" : ""));
        planned = true;
    }

    private Master chooseMaster() {
        Master chosen = config.master().master();
        if (chosen == null || !chosen.canUse()) chosen = Master.bestForCombat();
        return chosen;
    }

    /** The configured style, or melee by default; falls back to any saved setup. */
    private CombatStyle chooseStyle() {
        CombatStyle want = config.style().style();
        if (want == null || (guide != null && guide.leafBladed())) want = CombatStyle.MELEE;
        if (gearStore.has(want)) return want;
        for (CombatStyle s : CombatStyle.values()) {
            if (gearStore.has(s)) return s;
        }
        return want;
    }

    private boolean inSkipList(String name) {
        for (String s : config.skipTasks().split(",")) {
            if (!s.isBlank() && s.trim().equalsIgnoreCase(name.trim())) return true;
        }
        return false;
    }

    /**
     * Fills {@link #shopList} with slayer shop items the task needs that aren't owned (carried, worn or banked).
     * Returns a reason when the task can't be done because something needed can't be bought.
     */
    private String computeShopList(int remaining) {
        Map<ShopItem, Integer> wanted = new LinkedHashMap<>();
        wanted.put(ShopItem.ENCHANTED_GEM, 1);
        for (ShopItem item : guide.wear()) {
            if (!item.coveredBySlayerHelm()) wanted.put(item, 1);
        }
        if (guide.finisher() == ShopItem.ICE_COOLER) {
            wanted.put(ShopItem.ICE_COOLER, Math.min(remaining + 3, MAX_ICE_COOLERS));
        } else if (guide.finisher() != null) {
            wanted.put(guide.finisher(), 1);
        }
        if (guide.leafBladed() && !setupHasLeafWeapon()) wanted.put(ShopItem.LEAF_BLADED_SPEAR, 1);

        for (Map.Entry<ShopItem, Integer> e : wanted.entrySet()) {
            ShopItem item = e.getKey();
            int have = item.carriedCount() + (item.wornNow() ? 1 : 0) + item.bankedCount();
            int missing = e.getValue() - have;
            if (missing <= 0) continue;
            if (item == ShopItem.ENCHANTED_GEM && !config.buySlayerItems()) {
                // The gem is only for checking progress; the task can still be done without it.
                warnOnce("gem", "No enchanted gem owned and buying is off; doing tasks without one");
                continue;
            }
            if (!config.buySlayerItems()) {
                return guide.displayName() + " needs a " + item.itemName() + " (buying slayer equipment is off)";
            }
            if (item.price() > config.slayerItemBudget()) {
                return guide.displayName() + " needs a " + item.itemName() + " (" + item.price()
                        + " gp, over the slayer item budget)";
            }
            shopList.put(item, missing);
        }
        return null;
    }

    private boolean setupHasLeafWeapon() {
        GearSetup.Item weapon = gear.inSlot(EquipmentInventorySlot.WEAPON);
        return weapon != null && weapon.name().toLowerCase(Locale.ROOT).contains("leaf-bladed");
    }

    // --- Get / skip a task ---

    private void getTask() {
        if (Rs2Bank.isOpen()) {
            Rs2Bank.closeBank();
            return;
        }
        if (Rs2Slayer.hasSlayerTask() && !Rs2Dialogue.isInDialogue()) {
            String name = Rs2Slayer.getSlayerTask();
            log.info("[Slayer] New task from {}: {} x {}", master.label(), Rs2Slayer.getSlayerTaskSize(), name);
            taskGotten = true;
            return;
        }
        if (handleDialogue("easier", "yes", "assignment")) return;
        if (!walkToMaster(master)) return;
        if (++dialogueTries > MAX_DIALOGUE_TRIES) {
            failure = "couldn't get a task from " + master.label();
            return;
        }
        Rs2NpcModel npc = masterNpc(master);
        if (npc == null) {
            Microbot.status = "Looking for " + master.label();
            return;
        }
        Microbot.status = "Asking " + master.label() + " for a task";
        if (npc.click("Assignment")) {
            sleepUntil(() -> Rs2Dialogue.isInDialogue() || Rs2Slayer.hasSlayerTask(), 6_000);
        }
    }

    /** Turael skip: a Turael task in place of a task from another master (the streak resets). */
    private void skipTask() {
        if (config.unsupportedTask() == SlayerConfig.UnsupportedTask.STOP) {
            failure = unsupported;
            return;
        }
        String current = Rs2Slayer.hasSlayerTask() ? Rs2Slayer.getSlayerTask() : null;
        if (current != null && skipFrom != null && !current.equalsIgnoreCase(skipFrom) && !Rs2Dialogue.isInDialogue()) {
            log.info("[Slayer] Turael skip: {} → {} x {}", skipFrom, Rs2Slayer.getSlayerTaskSize(), current);
            stats.addSkip();
            skipDone = true;
            return;
        }
        int from = Microbot.getVarbitValue(VarbitID.SLAYER_MASTER);
        if (from == Master.TURAEL.varbitValue() && !Rs2Dialogue.isInDialogue()) {
            failure = unsupported + "; it's Turael's own task, so it can't be Turael-skipped";
            return;
        }
        if (Rs2Bank.isOpen()) {
            Rs2Bank.closeBank();
            return;
        }
        if (handleDialogue("easier", "yes")) return;
        if (!walkToMaster(Master.TURAEL)) return;
        if (++dialogueTries > MAX_DIALOGUE_TRIES) {
            failure = unsupported + ", and Turael wouldn't swap it";
            return;
        }
        Rs2NpcModel turael = masterNpc(Master.TURAEL);
        if (turael == null) return;
        Microbot.status = "Asking Turael for an easier task (" + unsupported + ")";
        if (turael.click("Assignment")) {
            sleepUntil(Rs2Dialogue::isInDialogue, 6_000);
        }
    }

    /**
     * Steps through a dialogue: continues, and picks the first option containing one of {@code prefer} (case
     * insensitive). True if a dialogue was handled this tick.
     */
    private boolean handleDialogue(String... prefer) {
        if (!Rs2Dialogue.isInDialogue()) return false;
        if (Rs2Dialogue.hasSelectAnOption()) {
            for (String p : prefer) {
                if (Rs2Dialogue.hasDialogueOption(p, false)) {
                    Rs2Dialogue.clickOption(p, false);
                    sleep(600, 1_100);
                    return true;
                }
            }
            Rs2Dialogue.keyPressForDialogueOption(1);
            sleep(600, 1_100);
            return true;
        }
        if (Rs2Dialogue.hasContinue()) {
            Rs2Dialogue.clickContinue();
            sleepGaussian(650, 200);
            return true;
        }
        return false;
    }

    /** Walks to the master; true once next to them. */
    private boolean walkToMaster(Master m) {
        WorldPoint here = Rs2Player.getWorldLocation();
        if (here != null && here.getPlane() == m.location().getPlane() && here.distanceTo(m.location()) <= 6
                && masterNpc(m) != null) {
            return true;
        }
        Microbot.status = "Travelling to " + m.label();
        Rs2Walker.walkWithBankedTransports(m.location(), 3, false);
        return false;
    }

    private static Rs2NpcModel masterNpc(Master m) {
        return Microbot.getRs2NpcCache().query()
                .withNames(m.npcNames())
                .nearestOnClientThread(15);
    }

    // --- Shop ---

    private void shop() {
        long cost = shopCost();
        int coins = Rs2Inventory.itemQuantity(ItemID.COINS);
        if (coins < cost) {
            if (!Rs2Bank.isOpen()) {
                Microbot.status = "Getting coins for the slayer shop";
                Rs2Bank.walkToBankAndUseBank();
                return;
            }
            int banked = Rs2Bank.count(ItemID.COINS);
            if (banked + coins < cost) {
                failure = "not enough coins for " + describeShopList() + " (" + cost + " gp)";
                return;
            }
            int before = coins;
            Rs2Bank.withdrawX(ItemID.COINS, (int) Math.min(Integer.MAX_VALUE, cost - coins + 50));
            sleepUntil(() -> Rs2Inventory.itemQuantity(ItemID.COINS) > before, 3_000);
            return;
        }
        if (Rs2Bank.isOpen()) {
            Rs2Bank.closeBank();
            return;
        }

        Master seller = nearestMaster();
        if (!Rs2Shop.isOpen()) {
            if (!walkToMaster(seller)) return;
            Microbot.status = "Opening " + seller.label() + "'s shop";
            for (String n : seller.npcNames()) {
                if (Rs2Shop.openShop(n, true) && Rs2Shop.isOpen()) break;
            }
            return;
        }

        for (Map.Entry<ShopItem, Integer> e : shopList.entrySet()) {
            ShopItem item = e.getKey();
            int target = e.getValue() + 0;
            int have = item.carriedCount() + (item.wornNow() ? 1 : 0);
            if (have >= target) continue;
            if (shopFailures >= MAX_SHOP_FAILURES) {
                Rs2Shop.closeShop();
                failure = "couldn't buy a " + item.itemName() + " from the slayer shop";
                return;
            }
            int left = target - have;
            String amount = left >= 10 ? "10" : left >= 5 ? "5" : "1";
            Microbot.status = "Buying " + item.itemName();
            sleep(350, 900);
            int before = item.carriedCount();
            if (Rs2Shop.buyItem(item.itemName(), amount)
                    && sleepUntil(() -> item.carriedCount() > before, 3_000)) {
                stats.addSpent((long) item.price() * (item.carriedCount() - before));
                log.info("[Slayer] Bought {} x {}", item.carriedCount() - before, item.itemName());
            } else {
                shopFailures++;
            }
            return;
        }
        sleepGaussian(500, 150);
        Rs2Shop.closeShop();
        // New gear to put on: gear up again at the bank.
        preparedFor = null;
        shopDone = true;
    }

    private long shopCost() {
        long total = 0;
        for (Map.Entry<ShopItem, Integer> e : shopList.entrySet()) {
            int missing = e.getValue() - e.getKey().carriedCount() - (e.getKey().wornNow() ? 1 : 0);
            if (missing > 0) total += (long) missing * e.getKey().price();
        }
        return total;
    }

    private String describeShopList() {
        return shopList.entrySet().stream()
                .map(e -> e.getValue() + " x " + e.getKey().itemName())
                .collect(Collectors.joining(", "));
    }

    private static Master nearestMaster() {
        WorldPoint here = Rs2Player.getWorldLocation();
        if (here == null) return Master.TURAEL;
        return Arrays.stream(Master.values())
                .min(Comparator.comparingInt(m -> m.location().distanceTo2D(here)))
                .orElse(Master.TURAEL);
    }

    // --- Bank ---

    private void bank() {
        if (Rs2Shop.isOpen()) {
            Rs2Shop.closeShop();
            return;
        }
        if (!Rs2Bank.isOpen()) {
            Microbot.status = "Travelling to a bank";
            travelToBank();
            return;
        }
        bankSeen = true;

        if (!depositedThisVisit) {
            Microbot.status = "Depositing";
            Rs2Bank.depositAllExcept(item -> item.getId() == ItemID.SLAYER_GEM);
            sleepUntil(() -> Rs2Inventory.items(i -> i.getId() != ItemID.SLAYER_GEM).findAny().isEmpty(), 3_000);
            depositedThisVisit = true;
            return;
        }

        // Without a task, for a task that will be skipped, or on the first look at the bank (the plan didn't know
        // what's owned yet, so slayer items may still need buying), only look: PLAN decides what's next.
        if (guide == null || unsupported != null || !planKnewBank) {
            finishBank();
            return;
        }

        if (equipNext()) return;
        if (withdrawNext()) return;

        if (config.foodAmount() > 0 && foodCount() == 0) {
            if (config.buyFood() && !foodRestockFailed) {
                startFoodRestock();
                return;
            }
            failure = "out of food: none in the bank" + (config.buyFood() ? " and buying it failed" : "");
            return;
        }
        finishBank();
    }

    private void finishBank() {
        sleepGaussian(400, 150);
        Rs2Bank.closeBank();
        if (guide != null && unsupported == null && planKnewBank) {
            preparedFor = guide;
            stats.addTrip();
        }
        bankDone = true;
    }

    /** Puts on one missing piece of the task's gear. True if this tick was spent on it. */
    private boolean equipNext() {
        Map<Integer, Integer> desired = desiredWorn();
        // Slots the setup leaves empty: take off what's there (e.g. a shield when switching to a two-hander).
        for (Rs2ItemModel worn : Rs2Equipment.items()) {
            if (worn == null || desired.containsKey(worn.getSlot()) || gear.isEmpty()) continue;
            if (worn.getSlot() == EquipmentInventorySlot.AMMO.getSlotIdx() && style == CombatStyle.RANGED) continue;
            if (equipTries.merge(1000 + worn.getSlot(), 1, Integer::sum) > MAX_EQUIP_TRIES) continue;
            Microbot.status = "Taking off " + worn.getName();
            Rs2Bank.depositEquippedItem(worn.getId());
            sleepGaussian(500, 150);
            return true;
        }
        for (Map.Entry<Integer, Integer> e : desired.entrySet()) {
            int slot = e.getKey();
            int id = e.getValue();
            if (Rs2Equipment.isWearing(id)) continue;
            if (equipTries.merge(slot, 1, Integer::sum) > MAX_EQUIP_TRIES) {
                // Two-handers and shields fight over the slots; give up on the slot rather than loop.
                warnOnce("equip" + id, "Couldn't put on " + ItemProcurer.itemName(id) + "; carrying on without it");
                continue;
            }
            boolean carried = Rs2Inventory.hasItem(id);
            int banked = Rs2Bank.count(id);
            if (!carried && banked <= 0) {
                if (isRequiredWear(id)) {
                    failure = guide.displayName() + " needs " + ItemProcurer.itemName(id) + ", which isn't in the bank";
                } else {
                    warnOnce("missing" + id, ItemProcurer.itemName(id) + " from the " + style.label().toLowerCase()
                            + " setup isn't in the bank; going without it");
                }
                equipTries.put(slot, MAX_EQUIP_TRIES + 1);
                return true;
            }
            Microbot.status = "Putting on " + ItemProcurer.itemName(id);
            if (carried) {
                Rs2Bank.wearItem(id);
            } else {
                boolean ammo = slot == EquipmentInventorySlot.AMMO.getSlotIdx();
                Rs2Bank.withdrawXAndEquip(id, ammo ? banked : 1);
            }
            sleepUntil(() -> Rs2Equipment.isWearing(id), 3_000);
            return true;
        }
        return false;
    }

    /** Slot → item id the task wants worn: the setup, with protective gear and a leaf-bladed weapon on top. */
    private Map<Integer, Integer> desiredWorn() {
        Map<Integer, Integer> desired = new LinkedHashMap<>();
        for (GearSetup.Item item : gear.worn().values()) desired.put(item.slot(), item.id());
        for (ShopItem item : guide.wear()) {
            if (item.coveredBySlayerHelm()) continue;
            int id = ownedIdFor(item);
            if (id > 0) desired.put(item.slot().getSlotIdx(), id);
        }
        if (guide.leafBladed() && !setupHasLeafWeapon()) {
            int id = ownedIdFor(ShopItem.LEAF_BLADED_SPEAR);
            if (id > 0) desired.put(EquipmentInventorySlot.WEAPON.getSlotIdx(), id);
        }
        return desired;
    }

    private boolean isRequiredWear(int id) {
        for (ShopItem item : guide.wear()) {
            if (ownedIdFor(item) == id) return true;
        }
        return guide.leafBladed() && ownedIdFor(ShopItem.LEAF_BLADED_SPEAR) == id;
    }

    /** The id of an owned item that counts as {@code item} (worn, carried or banked), or the shop item's id. */
    private static int ownedIdFor(ShopItem item) {
        Rs2ItemModel worn = Rs2Equipment.get(i -> item.matches(i.getName()));
        if (worn != null) return worn.getId();
        Rs2ItemModel carried = Rs2Inventory.get(i -> item.matches(i.getName()));
        if (carried != null) return carried.getId();
        return Rs2Bank.bankItems().stream()
                .filter(i -> item.matches(i.getName()))
                .map(Rs2ItemModel::getId)
                .findFirst().orElse(item.itemId());
    }

    /** Takes out one missing trip item. True if this tick was spent on it. */
    private boolean withdrawNext() {
        // The gem: always carried, for checking the task.
        if (!Rs2Inventory.hasItem(ItemID.SLAYER_GEM) && tryWithdraw("gem", ItemID.SLAYER_GEM, 1)) return true;

        ShopItem finisher = guide.finisher();
        if (finisher != null) {
            int want = finisher == ShopItem.ICE_COOLER
                    ? Math.min(Rs2Slayer.getSlayerTaskSize() + 3, MAX_ICE_COOLERS) : 1;
            int have = finisher.carriedCount();
            if (have < want && tryWithdraw("finisher", ownedIdFor(finisher), want - have)) return true;
        }

        if (guide.light() && !hasLightSource() && getLight()) return true;
        if (needsSwampRope() && !Rs2Inventory.hasItem(ItemID.ROPE)) {
            if (Rs2Bank.count(ItemID.ROPE) > 0) {
                if (tryWithdraw("swampRope", ItemID.ROPE, 1)) return true;
            } else {
                return buyTripItems(List.of(ItemNeed.of(ItemID.ROPE, "Rope", 1)),
                        "a rope for the Lumbridge Swamp Caves entrance");
            }
        }

        for (TaskGuide.Supply supply : guide.supplies()) {
            int have = Rs2Inventory.itemQuantity(supply.itemId());
            if (have >= supply.quantity()) continue;
            if (Rs2Bank.count(supply.itemId()) <= 0) {
                if (!supply.optional()) {
                    failure = guide.displayName() + " needs " + supply.name() + ", which isn't in the bank";
                    return true;
                }
                warnOnce("supply" + supply.itemId(), "No " + supply.name() + " in the bank; going without");
                continue;
            }
            if (tryWithdraw("supply" + supply.itemId(), supply.itemId(), supply.quantity() - have)) return true;
        }

        if (guide.antipoison() && !hasPotion("antipoison", "antidote", "anti-venom")) {
            Rs2ItemModel potion = Rs2Bank.bankItems().stream()
                    .filter(i -> potionName(i.getName(), "antipoison", "antidote", "anti-venom"))
                    .findFirst().orElse(null);
            if (potion != null && tryWithdraw("antipoison", potion.getId(), 1)) return true;
        }

        for (GearSetup.Item item : gear.carried()) {
            int have = Rs2Inventory.itemQuantity(item.id());
            if (have >= item.quantity()) continue;
            if (Rs2Bank.count(item.id()) <= 0) {
                warnOnce("carry" + item.id(), "No " + item.name() + " in the bank for the magic setup");
                continue;
            }
            if (tryWithdraw("carry" + item.id(), item.id(), item.quantity() - have)) return true;
        }

        if (config.protectionPrayers() && config.prayerPotions() > 0) {
            int have = (int) Rs2Inventory.items(i -> potionName(i.getName(), "prayer potion", "super restore")).count();
            if (have < config.prayerPotions()) {
                Rs2ItemModel potion = Rs2Bank.bankItems().stream()
                        .filter(i -> potionName(i.getName(), "prayer potion", "super restore"))
                        .max(Comparator.comparing(Rs2ItemModel::getName)).orElse(null);
                if (potion != null && tryWithdraw("prayer", potion.getId(), config.prayerPotions() - have)) return true;
            }
        }

        if (config.staminaPotions() > 0) {
            int have = staminaCount();
            if (have < config.staminaPotions()) {
                // Highest dose first: "Stamina potion(4)" sorts after "(1)".
                Rs2ItemModel potion = Rs2Bank.bankItems().stream()
                        .filter(i -> potionName(i.getName(), "stamina potion"))
                        .max(Comparator.comparing(Rs2ItemModel::getName)).orElse(null);
                if (potion != null && tryWithdraw("stamina", potion.getId(), config.staminaPotions() - have)) {
                    return true;
                }
            }
        }

        for (Map.Entry<String, Integer> extra : extraItems().entrySet()) {
            String name = extra.getKey();
            int have = Rs2Inventory.count(name, true);
            if (have >= extra.getValue() || withdrawTried.contains("extra" + name)) continue;
            withdrawTried.add("extra" + name);
            if (!Rs2Bank.hasItem(name, true)) {
                warnOnce("extra" + name, "No " + name + " in the bank");
                continue;
            }
            Microbot.status = "Withdrawing " + name;
            Rs2Bank.withdrawX(name, extra.getValue() - have, true);
            sleepUntil(() -> Rs2Inventory.count(name, true) > have, 3_000);
            return true;
        }

        int missingFood = config.foodAmount() - foodCount();
        if (missingFood > 0 && !withdrawTried.contains("food")) {
            withdrawTried.add("food");
            String name = config.foodName().trim();
            int before = foodCount();
            Microbot.status = "Withdrawing food";
            boolean ok = !name.isEmpty() && Rs2Bank.hasItem(name, true)
                    ? Rs2Bank.withdrawX(name, missingFood, true)
                    : Rs2Bank.withdrawX(Rs2ItemModel::isFood, missingFood);
            if (ok) sleepUntil(() -> foodCount() > before, 3_000);
            return true;
        }
        return false;
    }

    /** Withdraws once per bank visit per key; true if a withdraw was attempted. */
    private boolean tryWithdraw(String key, int itemId, int amount) {
        if (amount <= 0 || withdrawTried.contains(key)) return false;
        withdrawTried.add(key);
        if (Rs2Bank.count(itemId) <= 0) return false;
        if (Rs2Inventory.isFull() && !Rs2Inventory.hasItem(itemId)) {
            warnOnce("full" + key, "Inventory full; couldn't take " + ItemProcurer.itemName(itemId));
            return false;
        }
        int before = Rs2Inventory.itemQuantity(itemId);
        Microbot.status = "Withdrawing " + ItemProcurer.itemName(itemId);
        if (amount == 1) {
            Rs2Bank.withdrawOne(itemId);
        } else {
            Rs2Bank.withdrawX(itemId, amount);
        }
        sleepUntil(() -> Rs2Inventory.itemQuantity(itemId) > before, 3_000);
        return true;
    }

    private Map<String, Integer> extraItems() {
        Map<String, Integer> items = new LinkedHashMap<>();
        for (String part : config.extraItems().split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int colon = p.lastIndexOf(':');
            String name = colon > 0 ? p.substring(0, colon).trim() : p;
            int qty = 1;
            if (colon > 0) {
                try {
                    qty = Math.max(1, Integer.parseInt(p.substring(colon + 1).trim()));
                } catch (NumberFormatException ignored) {
                    // Keep 1.
                }
            }
            items.put(name, qty);
        }
        return items;
    }

    private static void travelToBank() {
        WorldPoint here = Rs2Player.getWorldLocation();
        if (here != null) {
            WorldPoint bank = Rs2Bank.getNearestBank() == null ? null : Rs2Bank.getNearestBank().getWorldPoint();
            if (bank != null && (here.getPlane() != bank.getPlane() || here.distanceTo(bank) > 25)) {
                Rs2Walker.walkWithBankedTransports(bank, 4, false);
                return;
            }
        }
        Rs2Bank.walkToBankAndUseBank();
    }

    // --- Light source & swamp rope ---

    /**
     * Gets a lit light source into the inventory: a banked lit one, else a candle lit with a tinderbox, else buys
     * a candle and tinderbox. True if this tick was spent on it.
     */
    private boolean getLight() {
        Rs2ItemModel lit = Rs2Bank.bankItems().stream().filter(SlayerScript::isLightSource).findFirst().orElse(null);
        if (lit != null) return tryWithdraw("light", lit.getId(), 1);

        boolean candle = Rs2Inventory.hasItem(ItemID.UNLIT_CANDLE);
        boolean tinderbox = Rs2Inventory.hasItem(ItemID.TINDERBOX);
        if (candle && tinderbox) {
            lightCandle();
            return true;
        }
        if (!candle && Rs2Bank.count(ItemID.UNLIT_CANDLE) > 0) return tryWithdraw("candle", ItemID.UNLIT_CANDLE, 1);
        if (!tinderbox && Rs2Bank.count(ItemID.TINDERBOX) > 0) return tryWithdraw("tinderbox", ItemID.TINDERBOX, 1);

        List<ItemNeed> buy = new ArrayList<>();
        if (!candle) buy.add(ItemNeed.of(ItemID.UNLIT_CANDLE, "Candle", 1));
        if (!tinderbox) buy.add(ItemNeed.of(ItemID.TINDERBOX, "Tinderbox", 1));
        // Get the rope in the same trip to the GE.
        if (needsSwampRope() && !Rs2Inventory.hasItem(ItemID.ROPE) && Rs2Bank.count(ItemID.ROPE) <= 0) {
            buy.add(ItemNeed.of(ItemID.ROPE, "Rope", 1));
        }
        return buyTripItems(buy, "a lit light source");
    }

    /** Uses the tinderbox on the candle. The bank is closed first; the next tick reopens it. */
    private void lightCandle() {
        if (Rs2Bank.isOpen()) {
            Rs2Bank.closeBank();
            sleepUntil(() -> !Rs2Bank.isOpen(), 2_000);
            return;
        }
        Microbot.status = "Lighting a candle";
        if (Rs2Inventory.combine(ItemID.TINDERBOX, ItemID.UNLIT_CANDLE)) {
            sleepUntil(() -> Rs2Inventory.hasItem(ItemID.LIT_CANDLE), 3_000);
        }
        if (!Rs2Inventory.hasItem(ItemID.LIT_CANDLE) && ++lightTries >= 3) {
            failure = guide.displayName() + ": couldn't light the candle";
        }
    }

    private int lightTries;

    /** Starts a GE run for trip items, or fails the task when buying is off or already failed. Always true. */
    private boolean buyTripItems(List<ItemNeed> items, String what) {
        if (!canBuyTripItems()) {
            failure = guide.displayName() + " needs " + what + " and none is in the bank"
                    + (config.buySlayerItems() ? " (buying it failed)" : " (buying slayer items is off)");
            return true;
        }
        log.info("[Slayer] Buying {} on the Grand Exchange for {}", items.stream().map(ItemNeed::toString)
                .collect(Collectors.joining(", ")), guide.displayName());
        procurer = new ItemProcurer(items, config.slayerItemBudget(), GE_MARKUP_PERCENT);
        restockingTripItems = true;
        restockNeeded = true;
        return true;
    }

    private boolean canBuyTripItems() {
        return config.buySlayerItems() && !tripBuyFailed;
    }

    private static boolean canMakeCandle() {
        boolean candle = Rs2Inventory.hasItem(ItemID.UNLIT_CANDLE) || Rs2Bank.count(ItemID.UNLIT_CANDLE) > 0;
        boolean tinderbox = Rs2Inventory.hasItem(ItemID.TINDERBOX) || Rs2Bank.count(ItemID.TINDERBOX) > 0;
        return candle && tinderbox;
    }

    /** The task is in the swamp caves and the entrance has no rope tied to it yet. */
    private boolean needsSwampRope() {
        return guide != null && guide.swampRope() && Microbot.getVarbitValue(VarbitID.SWAMP_CAVES_ROPED_ENTRANCE) == 0;
    }

    /** Walks to the swamp cave hole and ties the rope. */
    private void tieSwampRope() {
        WorldPoint here = Rs2Player.getWorldLocation();
        if (here == null) return;
        if (here.getPlane() != 0 || here.distanceTo(SWAMP_HOLE) > 6) {
            Microbot.status = "Walking to the Lumbridge Swamp Caves entrance";
            Rs2Walker.walkWithBankedTransports(SWAMP_HOLE, 3, false);
            return;
        }
        Microbot.status = "Tying a rope to the swamp cave entrance";
        if (Rs2Inventory.useItemOnObject(ItemID.ROPE, SWAMP_HOLE_OBJECT)) {
            sleepUntil(() -> Microbot.getVarbitValue(VarbitID.SWAMP_CAVES_ROPED_ENTRANCE) != 0, 6_000);
        }
        if (Microbot.getVarbitValue(VarbitID.SWAMP_CAVES_ROPED_ENTRANCE) != 0) {
            log.info("[Slayer] Tied a rope to the Lumbridge Swamp Caves entrance");
        } else if (++ropeTries >= 5) {
            failure = "couldn't tie the rope to the Lumbridge Swamp Caves entrance";
        }
    }

    private int ropeTries;

    // --- Restock (food, or trip items) ---

    private void startFoodRestock() {
        Fish fish = Fish.forHitpoints(Rs2Player.getRealSkillLevel(Skill.HITPOINTS));
        int amount = Math.max(config.foodAmount() * 3, 20);
        long price = ItemPrices.guidePrice(fish.itemId);
        if (price > 0) amount = (int) Math.max(1, Math.min(amount, config.foodBudget() / (price * 12 / 10)));
        log.info("[Slayer] No food in the bank: buying {} x {} on the Grand Exchange", amount, fish.itemName);
        procurer = new ItemProcurer(List.of(ItemNeed.of(fish.itemId, fish.itemName, amount)),
                config.foodBudget(), GE_MARKUP_PERCENT);
        restockNeeded = true;
    }

    private void restock() {
        if (procurer == null) return;
        ItemProcurer.Status status = procurer.tick();
        if (status == ItemProcurer.Status.WORKING) {
            Microbot.status = "Buying " + (restockingTripItems ? "trip items" : "food") + " on the Grand Exchange";
            return;
        }
        if (status == ItemProcurer.Status.FAILED) {
            log.warn("[Slayer] Buying {} failed", restockingTripItems ? "trip items" : "food");
            if (restockingTripItems) tripBuyFailed = true;
            else foodRestockFailed = true;
        }
        restockingTripItems = false;
        if (Rs2GrandExchange.isOpen()) Rs2GrandExchange.closeExchange();
        procurer = null;
    }

    // --- Travel ---

    private void travel() {
        if (Rs2Bank.isOpen()) {
            Rs2Bank.closeBank();
            return;
        }
        if (Rs2Shop.isOpen()) {
            Rs2Shop.closeShop();
            return;
        }
        if (needsSwampRope()) {
            tieSwampRope();
            return;
        }
        Microbot.status = "Travelling to " + spot.label();
        Rs2Walker.walkWithBankedTransports(spot.centre(), Math.max(2, spot.radius() / 3), false);
    }

    private boolean atSpot() {
        WorldPoint here = Rs2Player.getWorldLocation();
        return spot != null && here != null && here.getPlane() == spot.centre().getPlane()
                && here.distanceTo(spot.centre()) <= Math.max(4, spot.radius() / 2);
    }

    private boolean nearSpot() {
        WorldPoint here = Rs2Player.getWorldLocation();
        return spot != null && here != null && here.getPlane() == spot.centre().getPlane()
                && here.distanceTo(spot.centre()) <= spot.radius() + 12;
    }

    // --- Fight ---

    private void fight() {
        if (Rs2Dialogue.isInDialogue() && Rs2Dialogue.hasContinue()) {
            Rs2Dialogue.clickContinue();
            return;
        }
        if (!autocastChecked) {
            autocastChecked = true;
            Rs2CombatSpells spell = gear.autocastSpell();
            if (style == CombatStyle.MAGIC && spell != null && Rs2Magic.getCurrentAutoCastSpell() != spell) {
                Microbot.status = "Setting autocast to " + pretty(spell.name());
                Rs2Combat.setAutoCastSpell(spell, false);
                return;
            }
        }
        if (config.protectionPrayers()) handlePrayer();
        if (useFinisher()) return;

        Rs2TileItemModel loot = findLoot();
        if (loot != null && !Rs2Player.isInteracting()) {
            loot(loot);
            return;
        }

        if (Rs2Player.isInteracting() || Rs2Combat.inCombat() && attacker() != null) {
            waitingSince = 0;
            noTargetsSince = 0;
            Microbot.status = "Fighting " + guide.displayName().toLowerCase(Locale.ROOT)
                    + " (" + Rs2Slayer.getSlayerTaskSize() + " left)";
            if (config.antiban()) glanceAround();
            return;
        }

        if (gemCheckDue && checkGem()) return;

        Rs2NpcModel target = findTarget();
        if (target == null) {
            long now = System.currentTimeMillis();
            if (waitingSince == 0) waitingSince = now;
            if (!anyTaskMonsterAround()) {
                if (noTargetsSince == 0) noTargetsSince = now;
                if (now - noTargetsSince > EMPTY_SPOT_MS) {
                    log.warn("[Slayer] No {} at {} for {}s; trying the next spot", guide.displayName(), spot.label(),
                            EMPTY_SPOT_MS / 1000);
                    badSpots.add(spot);
                    planned = false;
                    return;
                }
            } else {
                noTargetsSince = 0;
            }
            if (now - waitingSince > WANDER_AFTER_MS) {
                wander();
                waitingSince = now;
                return;
            }
            Microbot.status = "Waiting for " + guide.displayName().toLowerCase(Locale.ROOT) + " to respawn";
            return;
        }
        waitingSince = 0;
        noTargetsSince = 0;

        Microbot.status = "Attacking " + target.getName();
        if (target.click("Attack") && sleepUntil(Rs2Player::isInteracting, 3_000)) {
            antibanAfterAction();
        }
    }

    /** A task monster already attacking us comes first, then the nearest free one around the spot. */
    private Rs2NpcModel findTarget() {
        Rs2NpcModel attacker = attacker();
        if (attacker != null) return attacker;
        Player me = Microbot.getClient().getLocalPlayer();
        List<Rs2NpcModel> free = Microbot.getRs2NpcCache().query()
                .where(npc -> guide.isTarget(npc.getName()))
                .where(npc -> !npc.isDead() && npc.getHealthRatio() != 0)
                .where(npc -> npc.getInteracting() == null || npc.getInteracting() == me)
                .within(spot.centre(), spot.radius() + 4)
                .toListOnClientThread();
        if (free.isEmpty()) return null;
        WorldPoint here = Rs2Player.getWorldLocation();
        free.sort(Comparator.comparingInt(npc -> here == null ? 0 : npc.getWorldLocation().distanceTo(here)));
        // Now and then the second closest, like a person who doesn't always pick the nearest.
        if (config.antiban() && free.size() > 1 && Rs2Random.between(0, 100) < 15) {
            Rs2NpcModel second = free.get(1);
            if (Rs2Reachable.isReachableFromPlayer(second.getWorldLocation())) return second;
        }
        // Reachability is checked from the player: across a river or behind a wall doesn't count.
        for (Rs2NpcModel npc : free) {
            if (Rs2Reachable.isReachableFromPlayer(npc.getWorldLocation())) return npc;
        }
        return null;
    }

    private Rs2NpcModel attacker() {
        Player me = Microbot.getClient().getLocalPlayer();
        return Microbot.getRs2NpcCache().query()
                .where(npc -> guide.isTarget(npc.getName()))
                .where(npc -> !npc.isDead() && npc.getInteracting() == me)
                .nearestOnClientThread(12);
    }

    private boolean anyTaskMonsterAround() {
        return Microbot.getRs2NpcCache().query()
                .where(npc -> guide.isTarget(npc.getName()))
                .within(spot.centre(), spot.radius() + 10)
                .nearestOnClientThread() != null;
    }

    /** Walks to a random tile around the spot, to wake crabs and find monsters out of view. */
    private void wander() {
        WorldPoint c = spot.centre();
        int r = Math.max(2, spot.radius() / 2);
        WorldPoint to = new WorldPoint(c.getX() + Rs2Random.between(-r, r), c.getY() + Rs2Random.between(-r, r), c.getPlane());
        Microbot.status = "Looking around for " + guide.displayName().toLowerCase(Locale.ROOT);
        Rs2Walker.walkTo(to, 1);
    }

    /**
     * Gargoyles (rock hammer) and lizards (ice cooler) don't die on their own: the item is used on them once their
     * health is down to the task's weakness threshold.
     */
    private boolean useFinisher() {
        ShopItem finisher = guide.finisher();
        Task task = guide.task();
        if (finisher == null || task.getWeaknessThreshold() < 0) return false;
        if (System.currentTimeMillis() - lastFinisherAt < 1_800) return false;
        Rs2ItemModel item = Rs2Inventory.get(i -> finisher.matches(i.getName()));
        if (item == null) return false;
        Player me = Microbot.getClient().getLocalPlayer();
        Rs2NpcModel weak = Microbot.getRs2NpcCache().query()
                .where(npc -> guide.isTarget(npc.getName()))
                .where(npc -> npc.getInteracting() == me || me != null && me.getInteracting() == npc.getActor())
                .where(npc -> isWeakEnough(npc, task.getWeaknessThreshold()))
                .nearestOnClientThread(6);
        if (weak == null) return false;
        Microbot.status = "Finishing the " + weak.getName() + " with the " + finisher.itemName().toLowerCase(Locale.ROOT);
        lastFinisherAt = System.currentTimeMillis();
        if (Rs2Inventory.use(item.getId()) && sleepUntil(Rs2Inventory::isItemSelected, 1_000)) {
            weak.click();
            sleep(600, 1_000);
        }
        return true;
    }

    /** Health at or below {@code threshold} hit points, from the health bar and the monster's known max health. */
    private static boolean isWeakEnough(Rs2NpcModel npc, int threshold) {
        int scale = npc.getHealthScale();
        int ratio = npc.getHealthRatio();
        if (scale <= 0 || ratio < 0) return false;
        int max = Rs2NpcManager.getHealth(npc.getId());
        if (max <= 0) return ratio == 0;
        return Math.ceil(ratio / (double) scale * max) <= threshold;
    }

    /** Checks the gem out of combat: what a player does to see how many are left. True if it did. */
    private boolean checkGem() {
        gemCheckDue = false;
        killsUntilGemCheck = Rs2Random.between(12, 30);
        if (!Rs2Inventory.hasItem(ItemID.SLAYER_GEM)) return false;
        Microbot.status = "Checking the enchanted gem";
        if (Rs2Inventory.interact(ItemID.SLAYER_GEM, "Check")) {
            sleep(900, 1_600);
            if (Rs2Dialogue.isInDialogue() && Rs2Dialogue.hasContinue()) Rs2Dialogue.clickContinue();
            return true;
        }
        return false;
    }

    private void handlePrayer() {
        Rs2Player.drinkPrayerPotionAt(Rs2Random.between(12, 22));
        Rs2NpcModel attacker = attacker();
        if (attacker == null) {
            if (!Rs2Combat.inCombat()) prayersOff();
            return;
        }
        Rs2PrayerEnum prayer = protectionFor(attacker.getId());
        if (Rs2Player.getRealSkillLevel(Skill.PRAYER) < prayer.getLevel()) return;
        if (Rs2Player.getBoostedSkillLevel(Skill.PRAYER) <= 0) return;
        if (!Rs2Prayer.isPrayerActive(prayer)) Rs2Prayer.toggle(prayer, true);
    }

    private static Rs2PrayerEnum protectionFor(int npcId) {
        String style = Rs2NpcManager.getAttackStyle(npcId);
        String s = style == null ? "" : style.toLowerCase(Locale.ROOT);
        if (s.contains("magic") && !s.contains("melee")) return Rs2PrayerEnum.PROTECT_MAGIC;
        if (s.contains("range")) return Rs2PrayerEnum.PROTECT_RANGE;
        return Rs2PrayerEnum.PROTECT_MELEE;
    }

    private static void prayersOff() {
        for (Rs2PrayerEnum p : new Rs2PrayerEnum[]{Rs2PrayerEnum.PROTECT_MELEE, Rs2PrayerEnum.PROTECT_MAGIC,
                Rs2PrayerEnum.PROTECT_RANGE}) {
            if (Rs2Prayer.isPrayerActive(p)) Rs2Prayer.toggle(p, false);
        }
    }

    // --- Loot ---

    private Rs2TileItemModel findLoot() {
        if (spot == null) return null;
        int minValue = config.lootMinValue();
        Set<String> always = alwaysLoot();
        Rs2ItemModel ammo = style == CombatStyle.RANGED ? Rs2Equipment.get(EquipmentInventorySlot.AMMO) : null;
        int ammoId = ammo == null ? -1 : ammo.getId();
        return Microbot.getRs2TileItemCache().query()
                .where(Rs2TileItemModel::isLootAble)
                .where(item -> !item.isDespawned() && !isSkippedLoot(item) && stillOnGround(item))
                .where(item -> item.getWorldLocation() != null
                        && item.getWorldLocation().distanceTo(spot.centre()) <= spot.radius() + 6)
                .where(item -> {
                    if (item.getId() == ammoId) return true;
                    String name = item.getName();
                    if (name == null) return false;
                    String lower = name.toLowerCase(Locale.ROOT);
                    for (String a : always) {
                        if (lower.startsWith(a)) return true;
                    }
                    if (minValue <= 0) return false;
                    long value = ItemPrices.guidePrice(item.getId()) * Math.max(1, item.getQuantity());
                    return value >= minValue;
                })
                .nearestReachableOnClientThread(14);
    }

    private void loot(Rs2TileItemModel loot) {
        String name = loot.getName();
        int id = loot.getId();
        int quantity = loot.getQuantity();
        boolean stacks = loot.isStackable() && Rs2Inventory.hasItem(id);
        if (Rs2Inventory.isFull() && !stacks) {
            if (!Rs2Inventory.getInventoryFood().isEmpty()) {
                Microbot.status = "Eating to make room for " + name;
                eatFood();
            }
            return;
        }
        Microbot.status = "Looting " + name;
        String key = lootKey(loot);
        int before = Rs2Inventory.itemQuantity(id);
        if (!(loot.pickup() && sleepUntil(() -> Rs2Inventory.itemQuantity(id) > before, 5_000))) {
            if (pickupFailures.merge(key, 1, Integer::sum) >= MAX_PICKUP_ATTEMPTS) {
                pickupFailures.remove(key);
                skippedLoot.put(key, System.currentTimeMillis() + SKIP_LOOT_MS);
            }
            return;
        }
        pickupFailures.remove(key);
        stats.addLoot(ItemPrices.guidePrice(id) * Math.max(1, quantity));
        antibanAfterAction();
    }

    private Set<String> alwaysLoot() {
        return Arrays.stream(config.alwaysLoot().split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    private static String lootKey(Rs2TileItemModel item) {
        return item.getId() + "@" + item.getWorldLocation();
    }

    private boolean isSkippedLoot(Rs2TileItemModel item) {
        Long until = skippedLoot.get(lootKey(item));
        if (until == null) return false;
        if (until > System.currentTimeMillis()) return true;
        skippedLoot.remove(lootKey(item));
        return false;
    }

    /** Whether the drop really is on its tile; the tile item cache can keep ones already gone. Client thread. */
    private static boolean stillOnGround(Rs2TileItemModel item) {
        WorldPoint location = item.getWorldLocation();
        WorldView view = Microbot.getClient().getTopLevelWorldView();
        if (location == null || view == null) return false;
        LocalPoint local = LocalPoint.fromWorld(view, location);
        if (local == null) return false;
        Tile[][][] tiles = view.getScene().getTiles();
        int plane = location.getPlane();
        int x = local.getSceneX();
        int y = local.getSceneY();
        if (plane < 0 || plane >= tiles.length || x < 0 || x >= tiles[plane].length || y < 0 || y >= tiles[plane][x].length) {
            return false;
        }
        Tile tile = tiles[plane][x][y];
        List<TileItem> onTile = tile == null ? null : tile.getGroundItems();
        return onTile != null && onTile.stream().anyMatch(t -> t.getId() == item.getId());
    }

    // --- Supplies & guards ---

    /** Out of food and low, out of the finishing item, or the light went missing. */
    private boolean needsBankNow() {
        if (guide == null || unsupported != null) return false;
        // With food turned off there's nothing a bank visit could fix about health.
        boolean noFood = config.foodAmount() > 0 && Rs2Inventory.getInventoryFood().isEmpty();
        if (noFood && Rs2Player.getHealthPercentage() <= config.bankAtPercent()) return true;
        if (noFood && Rs2Inventory.isFull()) return true;
        if (guide.finisher() != null && guide.finisher().carriedCount() == 0) return true;
        if (needsSwampRope() && !Rs2Inventory.hasItem(ItemID.ROPE)) return true;
        return guide.light() && !hasLightSource();
    }

    private boolean taskOver() {
        return !Rs2Slayer.hasSlayerTask();
    }

    private boolean needsToEat() {
        return !Rs2Inventory.getInventoryFood().isEmpty() && Rs2Player.getHealthPercentage() <= config.eatAtPercent();
    }

    /**
     * Drinks a stamina potion while travelling when run energy is low and no stamina effect is running, and
     * drops the empty vial. Not in fights, where the bot barely runs.
     */
    private boolean drinkStaminaIfNeeded() {
        if (Rs2Inventory.hasItem(ItemID.VIAL_EMPTY) && !Rs2Bank.isOpen() && !Rs2Shop.isOpen()
                && !Rs2GrandExchange.isOpen()) {
            Rs2Inventory.drop(ItemID.VIAL_EMPTY);
            return true;
        }
        State state = getCurrentState();
        if (state == State.FIGHT || state == State.SETUP || state == State.FINISH) return false;
        if (staminaCount() == 0 || Rs2Player.hasStaminaActive()) return false;
        if (Rs2Player.getRunEnergy() > staminaAt) return false;
        if (Rs2Bank.isOpen() || Rs2Shop.isOpen() || Rs2GrandExchange.isOpen()) return false;
        Rs2ItemModel potion = Rs2Inventory.items(i -> potionName(i.getName(), "stamina potion"))
                .min(Comparator.comparing(Rs2ItemModel::getName)).orElse(null);
        if (potion == null) return false;
        Microbot.status = "Drinking a stamina potion";
        if (Rs2Inventory.interact(potion, "Drink")) {
            sleepUntil(Rs2Player::hasStaminaActive, 2_400);
            if (!Rs2Player.isRunEnabled()) Rs2Player.toggleRunEnergy(true);
        }
        staminaAt = Rs2Random.between(20, 45);
        return true;
    }

    // Run energy at or below which a stamina potion is drunk; re-rolled after each drink.
    private int staminaAt = 35;

    private static int staminaCount() {
        return (int) Rs2Inventory.items(i -> potionName(i.getName(), "stamina potion")).count();
    }

    private boolean eatIfNeeded() {
        if (!needsToEat()) return false;
        if (Rs2Bank.isOpen()) {
            Rs2Bank.closeBank();
            return true;
        }
        if (Rs2Shop.isOpen()) {
            Rs2Shop.closeShop();
            return true;
        }
        Microbot.status = "Eating";
        eatFood();
        return true;
    }

    private void eatFood() {
        int before = foodCount();
        if (Rs2Player.useFood()) {
            sleepUntil(() -> foodCount() < before, 1_800);
        }
    }

    private static int foodCount() {
        return Rs2Inventory.getInventoryFood().size();
    }

    private static boolean hasLightSource() {
        return Rs2Inventory.items(SlayerScript::isLightSource).findAny().isPresent()
                || Rs2Equipment.isWearing(SlayerScript::isLightSource);
    }

    private static int countLightInBank() {
        return Rs2Bank.count(SlayerScript::isLightSource);
    }

    private static boolean isLightSource(Rs2ItemModel item) {
        if (item == null) return false;
        if (LIT_LIGHTS.contains(item.getId())) return true;
        String name = item.getName();
        if (name == null) return false;
        for (String gear : LIGHT_GEAR) {
            if (gear.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private static boolean hasPotion(String... kinds) {
        return Rs2Inventory.items(i -> potionName(i.getName(), kinds)).findAny().isPresent();
    }

    private static boolean potionName(String name, String... kinds) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String k : kinds) {
            if (lower.startsWith(k)) return true;
        }
        return false;
    }

    private void warnOnce(String key, String message) {
        if (warned.add(key)) {
            log.warn("[Slayer] {}", message);
            Microbot.log(message);
        }
    }

    // --- Finish ---

    private boolean shouldFinish() {
        if (failure != null || doneReason != null) return true;
        if (Rs2Slayer.hasSlayerTask()) return false;
        if (config.stopAfterTasks() > 0 && stats.tasksDone() >= config.stopAfterTasks()) {
            doneReason = "finished " + stats.tasksDone() + " tasks";
            return true;
        }
        int level = Rs2Player.getRealSkillLevel(Skill.SLAYER);
        if (config.stopAtLevel() > 0 && level >= config.stopAtLevel()) {
            doneReason = "reached Slayer level " + level;
            return true;
        }
        return false;
    }

    private void finish() {
        if (finishing) return;
        finishing = true;
        String reason = failure != null ? "Stopped: " + failure : "Done: " + doneReason;
        log.info("[Slayer] {} ({} tasks, {} kills, {} skips)", reason, stats.tasksDone(), stats.kills(), stats.skips());
        Microbot.status = reason;
        Microbot.log(reason);
        if (Rs2Bank.isOpen()) Rs2Bank.closeBank();
        if (Rs2Shop.isOpen()) Rs2Shop.closeShop();
        prayersOff();
        Rs2Player.logoutWithoutAutoLogin();
        Microbot.stopPlugin(SlayerPlugin.class);
    }

    // --- Tracking ---

    private void track() {
        int count = Rs2Slayer.getSlayerTaskSize();
        if (lastCount > 0 && count < lastCount && count >= 0) {
            int killed = lastCount - count;
            stats.addKills(killed);
            killsUntilGemCheck -= killed;
            if (killsUntilGemCheck <= 0) gemCheckDue = true;
            if (count == 0) {
                stats.addTaskDone();
                log.info("[Slayer] Task complete: {} ({} done this session)", taskName, stats.tasksDone());
            }
        }
        lastCount = count;
        if (count > 0 && taskName != null) {
            stats.setTask(pretty(taskName), count, Microbot.getVarbitPlayerValue(VarPlayerID.SLAYER_COUNT_ORIGINAL));
        }
    }

    // --- Antiban ---

    private void antibanAfterAction() {
        if (!config.antiban()) return;
        if (Rs2Random.between(0, 100) < 7) {
            sleep(1_200, 3_000);
        }
        Rs2Antiban.actionCooldown();
        Rs2Antiban.takeMicroBreakByChance();
    }

    private void glanceAround() {
        if (Rs2Random.between(0, 1000) >= 10 || spot == null) return;
        int angle = Rs2Camera.angleToTile(spot.centre()) + Rs2Random.between(-45, 45);
        Rs2Camera.setAngle(((angle % 360) + 360) % 360, 20);
    }

    /**
     * The antiban cooldown only counts down out of combat, so it could hold the script mid-fight. Waits it out
     * briefly when idle, and cancels it in a fight or once it has run a few seconds. True while pausing.
     */
    private boolean antibanPause() {
        if (!Rs2AntibanSettings.actionCooldownActive) {
            cooldownDeadline = 0;
            return false;
        }
        long now = System.currentTimeMillis();
        if (cooldownDeadline == 0) {
            cooldownDeadline = now + ThreadLocalRandom.current().nextLong(MIN_COOLDOWN_MS, MAX_COOLDOWN_MS);
        }
        if (!Rs2Combat.inCombat() && now < cooldownDeadline) {
            Microbot.status = "Antiban pause";
            return true;
        }
        Rs2AntibanSettings.actionCooldownActive = false;
        cooldownDeadline = 0;
        return false;
    }

    // --- Watchdog ---

    /** Logs out and stops when nothing changes for 3 minutes (the setup window and GE offers excepted). */
    private boolean runWatchdog() {
        State state = getCurrentState();
        if (state == null || state == State.FINISH) return false;
        long now = System.currentTimeMillis();
        String signature = progressSignature();
        boolean waiting = state == State.SETUP || setupOpen || (procurer != null && procurer.isWaitingForOffers());
        if (state != watchedState || !signature.equals(progressSignature) || waiting) {
            watchedState = state;
            progressSignature = signature;
            lastProgressAt = now;
            return false;
        }
        if (now - lastProgressAt < STALL_MS) return false;
        String reason = "no progress in " + state + " for " + (now - lastProgressAt) / 1000 + "s";
        log.warn("[Slayer] Watchdog: {}; logging out and stopping", reason);
        prayersOff();
        StuckGuard.trip(reason);
        return true;
    }

    private String progressSignature() {
        long xp = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            long total = 0;
            for (Skill s : new Skill[]{Skill.SLAYER, Skill.HITPOINTS, Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE,
                    Skill.RANGED, Skill.MAGIC}) {
                total += Microbot.getClient().getSkillExperience(s);
            }
            return total;
        }).orElse(0L);
        int inventory = Rs2Inventory.items().mapToInt(Rs2ItemModel::getQuantity).sum()
                + 31 * Rs2Inventory.items().mapToInt(Rs2ItemModel::getId).sum();
        return xp + "|" + Rs2Player.getWorldLocation() + "|" + inventory + "|" + Rs2Bank.isOpen() + "|"
                + Rs2Shop.isOpen() + "|" + Rs2Slayer.getSlayerTaskSize() + "|" + Rs2Dialogue.isInDialogue();
    }

    private static String pretty(String s) {
        if (s == null || s.isEmpty()) return "-";
        String lower = s.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    // --- Overlay accessors ---

    TaskGuide guide() {
        return guide;
    }

    GearSetup gear() {
        return gear;
    }

    // --- Lifecycle ---

    public boolean run(SlayerConfig config) {
        this.config = config;
        stats.reset();
        gearStore.invalidate();
        setupOpen = false;
        setupDone = false;
        planned = false;
        master = Master.TURAEL;
        guide = null;
        preparedFor = null;
        spot = null;
        style = CombatStyle.MELEE;
        gear = GearSetup.empty();
        taskName = null;
        unsupported = null;
        shopList.clear();
        badSpots.clear();
        bankSeen = false;
        warned.clear();
        foodRestockFailed = false;
        tripBuyFailed = false;
        restockingTripItems = false;
        procurer = null;
        pickupFailures.clear();
        skippedLoot.clear();
        lastCount = -1;
        gemCheckDue = true;
        failure = null;
        doneReason = null;
        finishing = false;
        watchedState = null;
        progressSignature = "";
        cooldownDeadline = 0;

        if (config.antiban()) {
            Rs2Antiban.resetAntibanSettings();
            Rs2Antiban.antibanSetupTemplates.applyCombatSetup();
            MouseIdleHabits.enable();
        }

        StuckGuard.attach("Shadowfax Slayer", SlayerPlugin.class);

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (StuckGuard.isTripped()) return;
                if (!Microbot.isLoggedIn()) return;
                stats.captureBaselineIfNeeded();
                track();
                // The gear window pauses everything except the start-up state that's waiting on it.
                if (setupOpen && getCurrentState() != State.SETUP) {
                    Microbot.status = "Paused: gear setup window open";
                    return;
                }
                if (runWatchdog()) return;
                if (eatIfNeeded()) return;
                if (drinkStaminaIfNeeded()) return;
                if (antibanPause()) return;
                step();
            } catch (Throwable ex) {
                // Throwable, not Exception: an uncaught Error would cancel the scheduled task without a log line.
                log.error("[Slayer] Unexpected error in scheduled loop", ex);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);

        return true;
    }

    @Override
    public void shutdown() {
        StuckGuard.detach(SlayerPlugin.class);
        SwingUtilities.invokeLater(() -> {
            if (dialog != null) dialog.dispose();
            dialog = null;
        });
        super.shutdown();
        Rs2Antiban.resetAntibanSettings();
    }
}
