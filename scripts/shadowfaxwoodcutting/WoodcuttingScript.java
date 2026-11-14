package net.runelite.client.plugins.microbot.shadowfaxwoodcutting;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.statemachine.StateMachineScript;
import net.runelite.client.plugins.microbot.statemachine.Transition;
import net.runelite.client.plugins.microbot.util.antiban.MouseIdleHabits;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.grounditem.Rs2GroundItem;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ShiftDrop;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.safety.StuckGuard;
import net.runelite.client.plugins.microbot.util.shopping.ItemNeed;
import net.runelite.client.plugins.microbot.util.shopping.ItemPrices;
import net.runelite.client.plugins.microbot.util.shopping.ItemProcurer;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.woodcutting.Axe;
import net.runelite.client.plugins.microbot.util.woodcutting.TreeSpot;
import net.runelite.client.plugins.microbot.util.woodcutting.TreeType;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Chops trees at the best known spot for the chosen tree (or, on Automatic, the best tree the player's level and
 * world allow), banking or shift-dropping the logs, and keeps the best axe the level allows.
 * <p>
 * PLAN → (UPGRADE) → WALK → CHOP ⇄ DROP / BANK, back to PLAN on every Woodcutting level:
 * <ul>
 *   <li><b>PLAN</b>: picks the tree, its spot (best first, members spots only on members worlds) and the axe.</li>
 *   <li><b>UPGRADE</b>: a better axe is owned (taken from the bank) or affordable (bought on the GE, then taken
 *       out); wielded when Attack allows, carried otherwise. The old axe goes in the bank.</li>
 *   <li><b>WALK</b>: to the spot.</li>
 *   <li><b>CHOP</b>: the closest standing tree of the type around the spot (sometimes the next closest), picking
 *       up bird nests. A spot with no trees of the type at all for a while is given up for the next one.</li>
 *   <li><b>DROP</b>: shift-drops the logs on the spot. <b>BANK</b>: deposits all but the axes at the spot's bank.</li>
 *   <li><b>FINISH</b>: stop level reached, or nothing left it can do: logs out and stops.</li>
 * </ul>
 */
@Slf4j
@Singleton
public class WoodcuttingScript extends StateMachineScript<WoodcuttingScript.State> {

    enum State { PLAN, UPGRADE, WALK, CHOP, DROP, BANK, FINISH }

    // The tree ladder Automatic climbs (teak is left out: its spots are single trees far from banks).
    private static final TreeType[] AUTO_LADDER = {
            TreeType.TREE, TreeType.OAK, TreeType.WILLOW, TreeType.MAPLE, TreeType.YEW, TreeType.MAGIC
    };
    private static final String NEST = "Bird nest";
    private static final int GE_MARKUP_PERCENT = 10;

    // Watchdog: no xp, movement or inventory change this long means stuck (GE offers being waited on excepted).
    private static final long STALL_MS = 240_000;
    // A spot with none of its trees standing or felled in view this long is wrong for this tree: try the next.
    private static final long EMPTY_SPOT_MS = 90_000;
    // A tree clicked that didn't start a chop (across water, behind a fence) is passed over this long.
    private static final long BAD_TREE_MS = 120_000;

    @Inject
    private WoodcuttingStats stats;

    private WoodcuttingConfig config;

    // Plan.
    private TreeType tree;
    private TreeSpot spot;
    private Axe targetAxe;
    private int plannedLevel = -1;
    private boolean planned;
    private final Set<TreeSpot> badSpots = new HashSet<>();
    private final Set<Axe> unaffordable = new HashSet<>();

    // Upgrade.
    private ItemProcurer procurer;
    private boolean upgradeDone;
    private int withdrawTries;

    // Chopping.
    private long lastChopClickAt;
    private long noTreesSince;
    private boolean wasChopping;
    private WorldPoint clickedTree;
    private final Map<WorldPoint, Long> badTrees = new HashMap<>();

    // Banking.
    private boolean bankDone;

    private String failure;
    private String doneReason;
    private boolean finishing;
    private int lastLogCount = -1;

    private State watchedState;
    private String progressSignature = "";
    private long lastProgressAt;

    @Override
    protected State initialState() {
        return State.PLAN;
    }

    @Override
    protected List<Transition<State>> defineTransitions() {
        return List.of(
                Transition.<State>from(State.PLAN)
                        .when(this::shouldFinish, "failure or stop level")
                        .because("Nothing left to do")
                        .goTo(State.FINISH),
                Transition.<State>from(State.PLAN)
                        .when(() -> planned && needsUpgrade(), "a better axe is owned or affordable")
                        .because("Getting a better axe")
                        .goTo(State.UPGRADE),
                Transition.<State>from(State.PLAN)
                        .when(() -> planned && Rs2Inventory.isFull(), "inventory full")
                        .because("Inventory full before starting")
                        .goTo(State.BANK),
                Transition.<State>from(State.PLAN)
                        .when(() -> planned, "planned")
                        .because("Plan ready")
                        .goTo(State.WALK),

                Transition.<State>from(State.UPGRADE)
                        .when(this::shouldFinish, "failure")
                        .because("Couldn't get an axe")
                        .goTo(State.FINISH),
                Transition.<State>from(State.UPGRADE)
                        .when(() -> upgradeDone, "upgradeDone")
                        .because("Axe ready")
                        .goTo(State.WALK),

                Transition.<State>from(State.WALK)
                        .when(this::shouldFinish, "failure or stop level")
                        .because("Stopping")
                        .goTo(State.FINISH),
                Transition.<State>from(State.WALK)
                        .when(this::needsReplan, "level changed")
                        .because("Woodcutting level went up")
                        .goTo(State.PLAN),
                Transition.<State>from(State.WALK)
                        .when(Rs2Inventory::isFull, "inventory full")
                        .because("Inventory full")
                        .goTo(State.BANK),
                Transition.<State>from(State.WALK)
                        .when(this::atSpot, "atSpot()")
                        .because("At the trees")
                        .goTo(State.CHOP),

                Transition.<State>from(State.CHOP)
                        .when(this::shouldFinish, "failure or stop level")
                        .because("Stopping")
                        .goTo(State.FINISH),
                Transition.<State>from(State.CHOP)
                        .when(() -> !planned, "spot given up")
                        .because("No trees at this spot")
                        .goTo(State.PLAN),
                Transition.<State>from(State.CHOP)
                        .when(() -> Rs2Inventory.isFull() && dropping() && logCount() > 0, "full, dropping")
                        .because("Inventory full: dropping logs")
                        .goTo(State.DROP),
                Transition.<State>from(State.CHOP)
                        .when(Rs2Inventory::isFull, "full")
                        .because("Inventory full: banking")
                        .goTo(State.BANK),
                Transition.<State>from(State.CHOP)
                        .when(() -> needsReplan() && !isChopping(), "level changed")
                        .because("Woodcutting level went up")
                        .goTo(State.PLAN),
                Transition.<State>from(State.CHOP)
                        .when(() -> !nearSpot(), "left the spot")
                        .because("Wandered off the spot")
                        .goTo(State.WALK),

                Transition.<State>from(State.DROP)
                        .when(() -> logCount() == 0, "no logs left")
                        .because("Logs dropped")
                        .goTo(State.CHOP),

                Transition.<State>from(State.BANK)
                        .when(this::shouldFinish, "failure")
                        .because("Couldn't bank")
                        .goTo(State.FINISH),
                Transition.<State>from(State.BANK)
                        .when(() -> bankDone, "bankDone")
                        .because("Logs banked")
                        .goTo(State.PLAN)
        );
    }

    @Override
    protected void onState(State state) {
        switch (state) {
            case PLAN:
                plan();
                break;
            case UPGRADE:
                upgrade();
                break;
            case WALK:
                walk();
                break;
            case CHOP:
                chop();
                break;
            case DROP:
                drop();
                break;
            case BANK:
                bank();
                break;
            case FINISH:
                finish();
                break;
        }
    }

    @Override
    protected void onTransition(State from, State to, String reason) {
        super.onTransition(from, to, reason);
        if (to == State.PLAN) planned = false;
        if (to == State.UPGRADE) {
            upgradeDone = false;
            procurer = null;
            withdrawTries = 0;
        }
        if (to == State.BANK) bankDone = false;
        if (to == State.CHOP) {
            noTreesSince = 0;
            wasChopping = false;
        }
    }

    // --- Plan ---

    private void plan() {
        int level = wcLevel();
        plannedLevel = level;
        if (config.stopAtLevel() > 0 && level >= config.stopAtLevel()) {
            doneReason = "reached Woodcutting level " + level;
            return;
        }

        TreeType chosen = config.tree().tree();
        if (chosen == null) {
            chosen = bestAutoTree();
        } else if (!chosen.levelMet()) {
            failure = chosen.displayName() + " trees need Woodcutting " + chosen.level() + " (you're " + level + ")";
            return;
        }
        if (chosen == null) {
            failure = "no tree spot for your level on this world";
            return;
        }

        TreeSpot next = TreeSpot.forTree(chosen).stream().filter(s -> !badSpots.contains(s)).findFirst().orElse(null);
        if (next == null) {
            failure = TreeSpot.hasSpot(chosen)
                    ? "none of the " + chosen.displayName().toLowerCase() + " spots had trees"
                    : "no known " + chosen.displayName().toLowerCase() + " spot on a " + (ItemProcurer.onMembersWorld()
                    ? "members" : "free-to-play") + " world";
            return;
        }
        if (tree != chosen || spot != next) {
            log.info("[Woodcutting] Level {}: chopping {} at {}", level, chosen.displayName(), next.label());
        }
        tree = chosen;
        spot = next;

        targetAxe = pickAxe();
        if (targetAxe == null) {
            failure = "no axe: none owned that your level can use, and "
                    + (config.upgradeAxes() ? "none affordable within the axe budget" : "axe upgrades are off");
            return;
        }
        refreshPlanStats();
        planned = true;
    }

    /** The highest tree on the ladder the level and world allow. */
    private static TreeType bestAutoTree() {
        TreeType best = null;
        for (TreeType t : AUTO_LADDER) {
            if (t.levelMet() && TreeSpot.hasSpot(t)) best = t;
        }
        return best;
    }

    /**
     * The axe to chop with: the best usable one owned, or a better one that can be bought within the budget (and
     * hasn't failed to buy this session).
     */
    private Axe pickAxe() {
        Axe best = Axe.bestOwned();
        if (!config.upgradeAxes()) return best != null ? best : Axe.bestCarried();
        for (Axe axe : Axe.values()) {
            if (!axe.buyable() || !axe.canUse() || unaffordable.contains(axe)) continue;
            if (best != null && axe.tier() <= best.tier()) continue;
            long price = ItemPrices.guidePrice(axe.itemId());
            if (price > 0 && price <= config.axeBudget()) best = axe;
        }
        return best;
    }

    private boolean needsUpgrade() {
        if (targetAxe == null) return false;
        Axe carried = Axe.bestCarried();
        if (carried == null || targetAxe.tier() > carried.tier()) return true;
        // Carried, but could be wielded now (frees the slot).
        return carried == targetAxe && !carried.isWorn() && carried.canWield();
    }

    private boolean needsReplan() {
        if (plannedLevel < 0) return false;
        int level = wcLevel();
        if (level == plannedLevel) return false;
        if (config.stopAtLevel() > 0 && level >= config.stopAtLevel()) return true;
        // Only worth stopping work if the tree or the axe would change.
        TreeType nextTree = config.tree().tree() == null ? bestAutoTree() : tree;
        Axe nextAxe = pickAxe();
        boolean change = nextTree != tree || (nextAxe != null && targetAxe != null && nextAxe.tier() > targetAxe.tier());
        if (!change) plannedLevel = level;
        return change;
    }

    private void refreshPlanStats() {
        Axe carried = Axe.bestCarried();
        String axe = carried == null ? "none" : carried.displayName() + (carried.isWorn() ? "" : " (carried)");
        if (targetAxe != null && (carried == null || targetAxe.tier() > carried.tier())) {
            axe += " → " + targetAxe.displayName();
        }
        stats.setPlan(tree == null ? "-" : tree.displayName(), spot == null ? "-" : spot.label(), axe,
                dropping() ? "Shift-drop" : "Bank at " + (spot == null ? "-" : pretty(spot.bank().name())));
    }

    // --- Upgrade ---

    private void upgrade() {
        Axe axe = targetAxe;
        if (axe.isCarried()) {
            if (!axe.isWorn() && axe.canWield()) {
                // Wield with the bank closed: inventory clicks behind the bank interface aren't reliable.
                if (Rs2Bank.isOpen()) {
                    Rs2Bank.closeBank();
                    return;
                }
                if (++withdrawTries > 6) {
                    log.warn("[Woodcutting] Couldn't wield the {}; chopping with it from the inventory",
                            axe.displayName());
                } else {
                    Microbot.status = "Wielding the " + axe.displayName();
                    Rs2Inventory.wield(axe.itemId());
                    sleepUntil(axe::isWorn, 2_400);
                    return;
                }
            }
            // Old axes (including the one just swapped off) go back in the bank: they only take up space.
            if (otherAxesInInventory(axe)) {
                if (!Rs2Bank.isOpen()) {
                    Microbot.status = "Banking the old axe";
                    Rs2Bank.walkToBankAndUseBank();
                    return;
                }
                Rs2Bank.depositAll(item -> Axe.isAxe(item.getId()) && item.getId() != axe.itemId());
                sleepUntil(() -> !otherAxesInInventory(axe), 2_400);
                return;
            }
            if (Rs2Bank.isOpen()) Rs2Bank.closeBank();
            log.info("[Woodcutting] Chopping with the {} ({})", axe.displayName(), axe.isWorn() ? "wielded" : "carried");
            refreshPlanStats();
            upgradeDone = true;
            return;
        }

        // Not owned (as far as the bank was last seen): buy it. The procurer checks the bank first, so an axe that
        // was in the bank after all isn't bought twice.
        if (procurer == null && !axe.isOwned()) {
            log.info("[Woodcutting] Buying a {} on the Grand Exchange (budget {} gp)", axe.displayName(),
                    config.axeBudget());
            procurer = new ItemProcurer(List.of(ItemNeed.of(axe.itemId(), axe.displayName(), 1)),
                    config.axeBudget(), GE_MARKUP_PERCENT);
        }
        if (procurer != null) {
            ItemProcurer.Status status = procurer.tick();
            if (status == ItemProcurer.Status.WORKING) {
                Microbot.status = "Buying a " + axe.displayName();
                return;
            }
            procurer = null;
            if (status == ItemProcurer.Status.FAILED) {
                giveUpOn(axe, "couldn't buy it");
                return;
            }
            stats.addAxeBought();
        }

        // In the bank: take it out (wielding happens next tick, with old axes deposited while the bank is open).
        if (!Rs2Bank.isOpen()) {
            Microbot.status = "Walking to a bank for the " + axe.displayName();
            Rs2Bank.walkToBankAndUseBank();
            return;
        }
        if (Rs2Bank.count(axe.itemId()) <= 0) {
            giveUpOn(axe, "it isn't in the bank");
            return;
        }
        if (Rs2Inventory.isFull()) {
            Rs2Bank.depositAllExcept(item -> Axe.isAxe(item.getId()));
            sleepUntil(() -> !Rs2Inventory.isFull(), 2_400);
            return;
        }
        if (++withdrawTries > 3) {
            giveUpOn(axe, "withdrawing it kept failing");
            return;
        }
        Microbot.status = "Withdrawing the " + axe.displayName();
        Rs2Bank.withdrawOne(axe.itemId());
        sleepUntil(() -> Rs2Inventory.hasItem(axe.itemId()), 3_000);
    }

    /** Falls back to the best axe in hand, or stops when there's none. */
    private void giveUpOn(Axe axe, String why) {
        log.warn("[Woodcutting] Not upgrading to the {}: {}", axe.displayName(), why);
        unaffordable.add(axe);
        if (Rs2Bank.isOpen()) Rs2Bank.closeBank();
        Axe fallback = Axe.bestCarried();
        if (fallback == null) {
            failure = "no usable axe (the " + axe.displayName() + ": " + why + ")";
            return;
        }
        targetAxe = fallback;
        refreshPlanStats();
        upgradeDone = true;
    }

    private static boolean otherAxesInInventory(Axe keep) {
        return Rs2Inventory.items(item -> Axe.isAxe(item.getId()) && item.getId() != keep.itemId()).findAny().isPresent();
    }

    // --- Walk ---

    private void walk() {
        if (Rs2Bank.isOpen()) {
            Rs2Bank.closeBank();
            return;
        }
        Microbot.status = "Travelling to " + spot.label();
        // Teleports count even when they're in the bank: the walker compares walking straight there with a stop at
        // a bank to take out a teleport (tablet, jewellery, …), and takes whichever is faster.
        Rs2Walker.walkWithBankedTransports(spot.centre(), Math.max(2, spot.radius() / 3), false);
    }

    /** Travel to a bank, teleporting with carried or banked items when that's faster, then open it. */
    private static void travelToBank(BankLocation bank) {
        WorldPoint here = Rs2Player.getWorldLocation();
        WorldPoint booth = bank == null ? null : bank.getWorldPoint();
        if (booth != null && here != null && (here.getPlane() != booth.getPlane() || here.distanceTo(booth) > 25)) {
            Rs2Walker.walkWithBankedTransports(booth, 4, false);
            return;
        }
        if (bank == null) {
            Rs2Bank.walkToBankAndUseBank();
        } else {
            Rs2Bank.walkToBankAndUseBank(bank);
        }
    }

    private boolean atSpot() {
        WorldPoint here = Rs2Player.getWorldLocation();
        return here != null && here.getPlane() == spot.centre().getPlane()
                && here.distanceTo(spot.centre()) <= Math.max(3, spot.radius() / 2);
    }

    private boolean nearSpot() {
        WorldPoint here = Rs2Player.getWorldLocation();
        return here != null && here.getPlane() == spot.centre().getPlane()
                && here.distanceTo(spot.centre()) <= spot.radius() + 8;
    }

    // --- Chop ---

    private void chop() {
        if (Rs2Dialogue.isInDialogue() && Rs2Dialogue.hasContinue()) {
            // Level-up messages and the like.
            Rs2Dialogue.clickContinue();
            return;
        }
        if (config.pickUpNests() && lootNest()) return;

        if (isChopping()) {
            wasChopping = true;
            noTreesSince = 0;
            Microbot.status = "Chopping " + tree.displayName().toLowerCase();
            if (config.antiban()) glanceAround();
            return;
        }
        // The tree just fell (or a log filled the last slot): react like a person, not on the same tick.
        if (wasChopping) {
            wasChopping = false;
            if (config.antiban()) {
                if (Rs2Random.between(0, 100) < 8) {
                    sleep(1_800, 4_200);
                } else {
                    sleepGaussian(650, 260);
                }
                Rs2Antiban.takeMicroBreakByChance();
            }
        }
        // A click that didn't turn into chopping: that tree can't be reached from here.
        if (clickedTree != null && System.currentTimeMillis() - lastChopClickAt > 6_000 && !Rs2Player.isMoving()) {
            log.info("[Woodcutting] The tree at {} didn't start a chop; passing it over for a while", clickedTree);
            badTrees.put(clickedTree, System.currentTimeMillis());
            clickedTree = null;
        }

        Rs2TileObjectModel target = pickTree();
        if (target == null) {
            if (noTreesSince == 0) noTreesSince = System.currentTimeMillis();
            Microbot.status = "Waiting for a " + tree.displayName().toLowerCase() + " tree to grow back";
            if (System.currentTimeMillis() - noTreesSince > EMPTY_SPOT_MS && !anyTreeOfTypeNearby()) {
                log.warn("[Woodcutting] No {} trees at {} for {}s; trying the next spot", tree.displayName(),
                        spot.label(), EMPTY_SPOT_MS / 1000);
                badSpots.add(spot);
                planned = false;
            }
            return;
        }
        noTreesSince = 0;

        Microbot.status = "Chopping a " + tree.displayName().toLowerCase() + " tree";
        if (!target.click("Chop down")) return;
        clickedTree = target.getWorldLocation();
        lastChopClickAt = System.currentTimeMillis();
        if (sleepUntil(Rs2Player::isAnimating, 5_000)) {
            clickedTree = null;
            wasChopping = true;
            if (config.antiban()) Rs2Antiban.actionCooldown();
        }
    }

    /** Chopping: animating, or just clicked a tree and walking to it / starting the swing. */
    private boolean isChopping() {
        if (Rs2Player.isAnimating()) return true;
        return System.currentTimeMillis() - lastChopClickAt < 2_400 && Rs2Player.isMoving();
    }

    /**
     * The closest standing tree of the type around the spot, skipping ones that didn't start a chop. With antiban,
     * now and then the second closest when it's barely further, as people don't always take the nearest.
     */
    private Rs2TileObjectModel pickTree() {
        long now = System.currentTimeMillis();
        badTrees.values().removeIf(at -> now - at > BAD_TREE_MS);
        WorldPoint here = Rs2Player.getWorldLocation();
        if (here == null) return null;
        List<Rs2TileObjectModel> trees = Microbot.getRs2TileObjectCache().query()
                .withAction("Chop down")
                .where(o -> tree.matchesName(o.getName()))
                .within(spot.centre(), spot.radius())
                .toListOnClientThread()
                .stream()
                .filter(o -> o.getWorldLocation() != null && !badTrees.containsKey(o.getWorldLocation()))
                .sorted(Comparator.comparingInt(o -> o.getWorldLocation().distanceTo(here)))
                .collect(Collectors.toList());
        if (trees.isEmpty()) return null;
        if (config.antiban() && trees.size() > 1 && Rs2Random.between(0, 100) < 20
                && trees.get(1).getWorldLocation().distanceTo(here) - trees.get(0).getWorldLocation().distanceTo(here) <= 3) {
            return trees.get(1);
        }
        return trees.get(0);
    }

    /** Any object at the spot named like the tree, standing or not (stumps keep a different name, so: standing). */
    private boolean anyTreeOfTypeNearby() {
        return Microbot.getRs2TileObjectCache().query()
                .where(o -> tree.matchesName(o.getName()))
                .within(spot.centre(), spot.radius() + 6)
                .nearestOnClientThread() != null;
    }

    /** Picks up a bird nest that fell nearby. True if it went for one. */
    private boolean lootNest() {
        if (Rs2Inventory.isFull()) return false;
        if (!Rs2GroundItem.exists(NEST, 8)) return false;
        Microbot.status = "Picking up a bird nest";
        int before = nestCount();
        if (Rs2GroundItem.loot(NEST, 8)) {
            if (sleepUntil(() -> nestCount() > before, 6_000)) {
                stats.addNest();
                log.info("[Woodcutting] Picked up a bird nest");
            }
            return true;
        }
        return false;
    }

    private static int nestCount() {
        return (int) Rs2Inventory.items(item -> item.getName() != null && item.getName().startsWith(NEST)).count();
    }

    /** While chopping: now and then turn the camera a little, the way a player idly looks around. */
    private void glanceAround() {
        if (Rs2Random.between(0, 1000) >= 12) return;
        WorldPoint look = spot.centre();
        int angle = Rs2Camera.angleToTile(look) + Rs2Random.between(-40, 40);
        Rs2Camera.setAngle(((angle % 360) + 360) % 360, 20);
    }

    // --- Drop / bank ---

    private void drop() {
        Microbot.status = "Dropping logs";
        Set<Integer> logs = logIds();
        Rs2ShiftDrop.dropAll(item -> logs.contains(item.getId()));
        sleepUntil(() -> logCount() == 0, 2_400);
        if (config.antiban()) Rs2Antiban.takeMicroBreakByChance();
    }

    private void bank() {
        if (!Rs2Bank.isOpen()) {
            Microbot.status = "Travelling to " + pretty(spot.bank().name()) + " bank";
            travelToBank(spot.bank());
            return;
        }
        if (Rs2Inventory.items(item -> !Axe.isAxe(item.getId())).findAny().isPresent()) {
            Microbot.status = "Banking logs";
            Rs2Bank.depositAllExcept(item -> Axe.isAxe(item.getId()));
            sleepUntil(() -> Rs2Inventory.items(item -> !Axe.isAxe(item.getId())).findAny().isEmpty(), 3_000);
            return;
        }
        stats.addTrip();
        if (config.antiban()) sleepGaussian(450, 180);
        Rs2Bank.closeBank();
        bankDone = true;
    }

    private boolean dropping() {
        return config.logHandling() == WoodcuttingConfig.LogHandling.DROP;
    }

    private static Set<Integer> logIds() {
        Set<Integer> ids = new HashSet<>();
        for (TreeType t : TreeType.values()) ids.add(t.logId());
        return ids;
    }

    private static int logCount() {
        Set<Integer> ids = logIds();
        return Rs2Inventory.items(item -> ids.contains(item.getId())).mapToInt(Rs2ItemModel::getQuantity).sum();
    }

    // --- Finish ---

    private boolean shouldFinish() {
        if (failure != null || doneReason != null) return true;
        int stop = config.stopAtLevel();
        if (stop > 0 && wcLevel() >= stop) {
            doneReason = "reached Woodcutting level " + wcLevel();
            return true;
        }
        return false;
    }

    private void finish() {
        if (finishing) return;
        finishing = true;
        String reason = failure != null ? "Stopped: " + failure : "Done: " + doneReason;
        log.info("[Woodcutting] {} ({} logs, {} nests, {} bank trips)", reason, stats.logs(), stats.nests(),
                stats.trips());
        Microbot.status = reason;
        Rs2Player.logoutWithoutAutoLogin();
        Microbot.stopPlugin(WoodcuttingPlugin.class);
    }

    // --- Tracking ---

    private void track() {
        int count = logCount();
        if (lastLogCount >= 0 && count > lastLogCount && getCurrentState() == State.CHOP) {
            stats.addLogs(count - lastLogCount);
        }
        lastLogCount = count;
    }

    private static int wcLevel() {
        return Rs2Player.getRealSkillLevel(Skill.WOODCUTTING);
    }

    private static String pretty(String enumName) {
        String s = enumName.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // --- Watchdog ---

    /** Logs out and stops when nothing changes for 4 minutes (GE offers being waited on excepted). */
    private boolean runWatchdog() {
        State state = getCurrentState();
        if (state == null || state == State.FINISH) return false;
        long now = System.currentTimeMillis();
        String signature = progressSignature();
        boolean waitingOnGe = procurer != null && procurer.isWaitingForOffers();
        if (state != watchedState || !signature.equals(progressSignature) || waitingOnGe) {
            watchedState = state;
            progressSignature = signature;
            lastProgressAt = now;
            return false;
        }
        if (now - lastProgressAt < STALL_MS) return false;
        String reason = "no progress in " + state + " for " + (now - lastProgressAt) / 1000 + "s";
        log.warn("[Woodcutting] Watchdog: {}; logging out and stopping", reason);
        StuckGuard.trip(reason);
        return true;
    }

    private String progressSignature() {
        int xp = Microbot.getClientThread()
                .runOnClientThreadOptional(() -> Microbot.getClient().getSkillExperience(Skill.WOODCUTTING))
                .orElse(0);
        int inventory = Rs2Inventory.items().mapToInt(Rs2ItemModel::getQuantity).sum()
                + 31 * Rs2Inventory.items().mapToInt(Rs2ItemModel::getId).sum();
        return xp + "|" + Rs2Player.getWorldLocation() + "|" + inventory + "|" + Rs2Bank.isOpen();
    }

    // --- Lifecycle ---

    public boolean run(WoodcuttingConfig config) {
        this.config = config;
        stats.reset();
        tree = null;
        spot = null;
        targetAxe = null;
        plannedLevel = -1;
        planned = false;
        badSpots.clear();
        unaffordable.clear();
        badTrees.clear();
        procurer = null;
        clickedTree = null;
        failure = null;
        doneReason = null;
        finishing = false;
        lastLogCount = -1;
        watchedState = null;
        progressSignature = "";

        if (config.antiban()) {
            Rs2Antiban.resetAntibanSettings();
            Rs2Antiban.antibanSetupTemplates.applyWoodcuttingSetup();
            MouseIdleHabits.enable();
        }

        StuckGuard.attach("Shadowfax Woodcutting", WoodcuttingPlugin.class);

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (StuckGuard.isTripped()) return;
                if (!Microbot.isLoggedIn()) return;
                stats.captureBaselineIfNeeded();
                track();
                if (runWatchdog()) return;
                step();
            } catch (Throwable ex) {
                // Throwable, not Exception: an uncaught Error would cancel the scheduled task without a log line.
                log.error("[Woodcutting] Unexpected error in scheduled loop", ex);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);

        return true;
    }

    @Override
    public void shutdown() {
        StuckGuard.detach(WoodcuttingPlugin.class);
        super.shutdown();
        Rs2Antiban.resetAntibanSettings();
    }
}
