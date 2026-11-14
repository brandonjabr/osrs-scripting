package net.runelite.client.plugins.microbot.shadowfaxhillgiants;

import net.runelite.client.plugins.microbot.util.safety.StuckGuard;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.WorldView;
import net.runelite.api.TileItem;
import net.runelite.api.Tile;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.api.tileitem.models.Rs2TileItemModel;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.statemachine.StateMachineScript;
import net.runelite.client.plugins.microbot.statemachine.Transition;
import net.runelite.client.plugins.microbot.util.antiban.MouseIdleHabits;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeAction;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeRequest;
import net.runelite.client.plugins.microbot.util.grandexchange.Rs2GrandExchange;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.player.StaminaPotions;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.walker.WalkerState;
import net.runelite.client.plugins.microbot.util.shopping.ItemPrices;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Kills hill giants in Edgeville Dungeon, entering through the brass key shed west of the Cooks' Guild.
 * Buries the big bones they drop and picks up grimy herbs worth at least the configured GE price (plus any
 * extra items the user lists). Banks at Varrock west when the inventory is full and out of food,
 * or when health is low with nothing left to eat. When the bank runs out of food, it buys fish at the Grand
 * Exchange, selling the cheapest banked herbs first if coins run short.
 * <p>
 * WALK_TO_GIANTS → FIGHT → BANK (→ RESTOCK → BANK) → WALK_TO_GIANTS. Low health with no food underground
 * goes to ESCAPE, which climbs the shed ladder next to the giants before anything else. Without a brass key, the bank is checked
 * first and then GET_KEY picks up the one that spawns in the dungeon.
 * Dying or wandering off ends up back in WALK_TO_GIANTS. With food turned off, low health with nothing to eat
 * goes to LOGOUT, which walks to the bank, logs out and stops the plugin.
 */
@Slf4j
@Singleton
public class HillGiantScript extends StateMachineScript<HillGiantScript.State> {

    enum State {
        WALK_TO_GIANTS,
        GET_KEY,
        FIGHT,
        ESCAPE,
        BANK,
        RESTOCK,
        LOGOUT
    }

    /** Fish bought on the GE: the cheapest one whose max Hitpoints tier covers ours. */
    enum Fish {
        TUNA("Tuna", ItemID.TUNA, 20),
        LOBSTER("Lobster", ItemID.LOBSTER, 40),
        SWORDFISH("Swordfish", ItemID.SWORDFISH, 99);

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
            return SWORDFISH;
        }
    }

    static final String HILL_GIANT = "Hill Giant";

    // Hill giant room in Edgeville Dungeon; spawns sit around (3099-3122, 9827-9850).
    // The brass key shed's ladder lands at (3115, 9852), at the room's north edge.
    private static final WorldArea GIANT_AREA = new WorldArea(3095, 9823, 32, 32, 0);
    private static final WorldPoint GIANT_CENTER = new WorldPoint(3110, 9838, 0);
    // The brass key respawns here every minute, in the small room north of the giants.
    private static final WorldPoint BRASS_KEY_SPAWN = new WorldPoint(3131, 9862, 0);
    private static final String BRASS_KEY = "Brass key";
    // Foot of the brass key shed ladder, at the north edge of the giant room: the quickest way out.
    private static final WorldPoint ESCAPE_LADDER = new WorldPoint(3115, 9852, 0);
    // Anything this far north on the map is underground.
    private static final int DUNGEON_MIN_Y = 9000;

    private static final String GRIMY_PREFIX = "grimy ";
    private static final String BIG_BONES = "Big bones";
    // Without food, stay at the bank until health is back to this before heading out again.
    private static final int REST_UNTIL_PERCENT = 80;
    // With food turned off and none carried, log out at the bank below this health.
    private static final int LOGOUT_AT_PERCENT = 25;

    private static final String COINS = "Coins";
    // Accounts below this total level are restricted from the Grand Exchange.
    private static final int GE_MIN_TOTAL_LEVEL = 100;
    private static final long GE_OFFER_TIMEOUT_MS = 45_000;
    private static final int GE_MAX_BUY_ATTEMPTS = 4;
    // Assumed GE sale price as a share of the guide price when picking how many herbs to sell.
    private static final double HERB_SALE_FACTOR = 0.8;
    // After a failed restock, rest at the bank instead of retrying the GE for this long.
    private static final long RESTOCK_RETRY_MS = 10 * 60_000;

    // Drops that failed to pick up repeatedly (unreachable, or gone from the cache), keyed by id@tile.
    private static final int MAX_PICKUP_ATTEMPTS = 2;
    private static final long SKIP_LOOT_MS = 120_000;
    private final Map<String, Integer> pickupFailures = new ConcurrentHashMap<>();
    private final Map<String, Long> skippedLoot = new ConcurrentHashMap<>();

    // Longest the antiban action cooldown may idle the script, picked at random per pause.
    private static final long MIN_COOLDOWN_MS = 2_000;
    private static final long MAX_COOLDOWN_MS = 7_000;
    private long cooldownDeadline;

    // Watchdog: how long a state may go without progress before each recovery step.
    private static final long FIGHT_RETARGET_MS = 15_000;
    private static final long FIGHT_REWALK_MS = 30_000;
    private static final long WALK_RESET_MS = 20_000;
    private static final long ANY_STATE_RESTART_MS = 90_000;
    // A wait the script chose (respawns, resting, GE offers) counts as progress for this long.
    private static final long INTENTIONAL_WAIT_GRACE_MS = 2_000;

    private State watchedState;
    private long lastProgressAt;
    private long lastIntentionalWaitAt;
    private int recoveryLevel;
    private String progressSignature = "";

    @Inject
    private HillGiantStats stats;

    private HillGiantConfig config;

    // Per-state flags, reset on every transition.
    private boolean bankDone;
    private boolean foodWithdrawTried;
    private boolean restockPlanned;
    private boolean restockDone;
    private int sellAttempts;
    private int buyAttempts;
    private long offerPlacedAt;

    // Chosen at the bank and carried into RESTOCK.
    private Fish restockFish;
    private int restockTarget;
    private long restockBlockedUntil;
    // Set once the escape is over (out of the dungeon and, without food, rested).
    private boolean escapeDone;
    // Whether a bank visit has confirmed there's no brass key to withdraw.
    private boolean bankCheckedForKey;

    @Override
    protected State initialState() {
        if (inGiantArea()) return State.FIGHT;
        // Look for a brass key in the bank before walking in.
        return config.useBrassKey() && !hasKey() ? State.BANK : State.WALK_TO_GIANTS;
    }

    @Override
    protected List<Transition<State>> defineTransitions() {
        return List.of(
                Transition.<State>from(State.WALK_TO_GIANTS)
                        .when(this::needsEscape, "needsEscape()")
                        .because("Low health with no food in the dungeon")
                        .goTo(State.ESCAPE),
                Transition.<State>from(State.WALK_TO_GIANTS)
                        .when(this::needsLogout, "needsLogout()")
                        .because("Food is off and health is low with nothing to eat")
                        .goTo(State.LOGOUT),
                Transition.<State>from(State.WALK_TO_GIANTS)
                        .when(this::needsBank, "needsBank()")
                        .because("Out of food with a full inventory or low health")
                        .goTo(State.BANK),
                Transition.<State>from(State.WALK_TO_GIANTS)
                        .when(this::inGiantArea, "inGiantArea()")
                        .because("Arrived at the hill giants")
                        .goTo(State.FIGHT),
                Transition.<State>from(State.WALK_TO_GIANTS)
                        .when(this::needsKey, "needsKey()")
                        .because("No brass key in the inventory or bank")
                        .goTo(State.GET_KEY),

                Transition.<State>from(State.GET_KEY)
                        .when(this::needsEscape, "needsEscape()")
                        .because("Low health with no food in the dungeon")
                        .goTo(State.ESCAPE),
                Transition.<State>from(State.GET_KEY)
                        .when(this::needsLogout, "needsLogout()")
                        .because("Food is off and health is low with nothing to eat")
                        .goTo(State.LOGOUT),
                Transition.<State>from(State.GET_KEY)
                        .when(this::needsBank, "needsBank()")
                        .because("Out of food with a full inventory or low health")
                        .goTo(State.BANK),
                Transition.<State>from(State.GET_KEY)
                        .when(this::hasKey, "hasKey()")
                        .because("Picked up the brass key")
                        .goTo(State.WALK_TO_GIANTS),

                Transition.<State>from(State.FIGHT)
                        .when(this::needsEscape, "needsEscape()")
                        .because("Low health with no food in the dungeon")
                        .goTo(State.ESCAPE),
                Transition.<State>from(State.FIGHT)
                        .when(this::needsLogout, "needsLogout()")
                        .because("Food is off and health is low with nothing to eat")
                        .goTo(State.LOGOUT),
                Transition.<State>from(State.FIGHT)
                        .when(this::needsBank, "needsBank()")
                        .because("Out of food with a full inventory or low health")
                        .goTo(State.BANK),
                Transition.<State>from(State.FIGHT)
                        .when(() -> !nearGiants(), "!nearGiants()")
                        .because("Left the hill giant room (died or wandered off)")
                        .goTo(State.WALK_TO_GIANTS),

                Transition.<State>from(State.BANK)
                        .when(this::needsEscape, "needsEscape()")
                        .because("Low health with no food in the dungeon")
                        .goTo(State.ESCAPE),
                Transition.<State>from(State.BANK)
                        .when(this::needsLogout, "needsLogout()")
                        .because("Food is off and health is low with nothing to eat")
                        .goTo(State.LOGOUT),
                Transition.<State>from(State.BANK)
                        .when(() -> restockPlanned, "restockPlanned")
                        .because("Bank is out of food, buying fish on the Grand Exchange")
                        .goTo(State.RESTOCK),
                Transition.<State>from(State.BANK)
                        .when(() -> bankDone, "bankDone")
                        .because("Loot banked and food restocked")
                        .goTo(State.WALK_TO_GIANTS),

                Transition.<State>from(State.ESCAPE)
                        .when(() -> escapeDone && config.useFood(), "escapeDone && useFood()")
                        .because("Safe on the surface, going to restock food")
                        .goTo(State.BANK),
                Transition.<State>from(State.ESCAPE)
                        .when(() -> escapeDone, "escapeDone")
                        .because("Rested on the surface, heading back down")
                        .goTo(State.WALK_TO_GIANTS),

                Transition.<State>from(State.RESTOCK)
                        .when(() -> restockDone, "restockDone")
                        .because("Grand Exchange trip finished")
                        .goTo(State.BANK)
        );
    }

    @Override
    protected void onTransition(State from, State to, String reason) {
        super.onTransition(from, to, reason);
        bankDone = false;
        escapeDone = false;
        foodWithdrawTried = false;
        restockPlanned = false;
        restockDone = false;
        sellAttempts = 0;
        buyAttempts = 0;
        offerPlacedAt = 0;
    }

    @Override
    protected void onState(State state) {
        switch (state) {
            case WALK_TO_GIANTS:
                walkToGiants();
                break;
            case GET_KEY:
                getKey();
                break;
            case FIGHT:
                fight();
                break;
            case ESCAPE:
                escape();
                break;
            case BANK:
                bank();
                break;
            case RESTOCK:
                restock();
                break;
            case LOGOUT:
                logoutAtBank();
                break;
        }
    }

    // --- States ---

    private void walkToGiants() {
        if (Rs2Bank.isOpen()) {
            Rs2Bank.closeBank();
            return;
        }
        if (Rs2GrandExchange.isOpen()) {
            Rs2GrandExchange.closeExchange();
            return;
        }
        Microbot.status = hasKey() ? "Walking to the brass key shed" : "Walking to Edgeville Dungeon";
        if (Rs2Walker.walkWithState(GIANT_CENTER, 4) == WalkerState.UNREACHABLE) {
            log.warn("[HillGiants] Hill giant room unreachable from {}", Rs2Player.getWorldLocation());
        }
    }

    /** Walks in through the Edgeville trapdoor (no key yet) and picks up the brass key at its spawn. */
    private void getKey() {
        if (Rs2Bank.isOpen()) {
            Rs2Bank.closeBank();
            return;
        }
        if (needsToEat()) {
            eatFood();
            return;
        }

        Rs2TileItemModel key = Microbot.getRs2TileItemCache().query()
                .withName(BRASS_KEY)
                .where(Rs2TileItemModel::isLootAble)
                .nearestOnClientThread(10);
        if (key != null) {
            if (Rs2Inventory.isFull() && hasFood()) {
                eatFood();
                return;
            }
            Microbot.status = "Picking up the brass key";
            if (key.pickup()) {
                sleepUntil(this::hasKey, 5000);
            }
            return;
        }

        WorldPoint here = Rs2Player.getWorldLocation();
        if (here != null && here.distanceTo(BRASS_KEY_SPAWN) <= 3) {
            Microbot.status = "Waiting for the brass key to respawn";
            markIntentionalWait();
            return;
        }
        Microbot.status = "Walking to the brass key spawn";
        Rs2Walker.walkWithState(BRASS_KEY_SPAWN, 2);
    }

    private void fight() {
        if (needsToEat()) {
            Microbot.status = "Eating";
            eatFood();
            return;
        }

        // Collect big bones until the inventory fills, then bury the whole batch.
        if (shouldBuryBatch()) {
            buryAllBigBones();
            return;
        }

        // Loot before the combat check: these monsters are aggressive, so another one usually attacks (and we
        // auto-retaliate) before we're ever idle, which would leave drops on the ground indefinitely.
        Rs2TileItemModel loot = findLoot();
        if (loot != null) {
            loot(loot);
            return;
        }

        if (Rs2Player.isInteracting()) {
            Microbot.status = "Fighting";
            return;
        }

        Rs2NpcModel giant = findTarget();
        if (giant == null) {
            if (freeGiantInRoom()) {
                // A free giant is in the room but we can't path to it: not a real wait, let the watchdog act.
                Microbot.status = "Can't reach a free hill giant";
            } else {
                Microbot.status = "Waiting for hill giants to respawn";
                markIntentionalWait();
            }
            return;
        }

        Microbot.status = "Attacking hill giant";
        if (giant.click("Attack") && sleepUntil(Rs2Player::isInteracting, 3000)) {
            antibanAfterAction();
        }
    }

    private void loot(Rs2TileItemModel loot) {
        String name = loot.getName();
        int id = loot.getId();
        int quantity = loot.getQuantity();
        boolean stacks = loot.isStackable() && Rs2Inventory.hasItem(id);

        if (Rs2Inventory.isFull() && !stacks) {
            // Trade a piece of food for the drop; with no food left, needsBank() takes over.
            if (hasFood()) {
                Microbot.status = "Eating to make room for " + name;
                eatFood();
            }
            return;
        }

        Microbot.status = "Looting " + name;
        String key = lootKey(loot);
        int before = Rs2Inventory.itemQuantity(id);
        if (!(loot.pickup() && sleepUntil(() -> Rs2Inventory.itemQuantity(id) > before, 5000))) {
            int failures = pickupFailures.merge(key, 1, Integer::sum);
            if (failures >= MAX_PICKUP_ATTEMPTS) {
                // Stop retrying a drop we can't take, so fighting resumes instead of stalling on it.
                pickupFailures.remove(key);
                skippedLoot.put(key, System.currentTimeMillis() + SKIP_LOOT_MS);
                log.info("[HillGiants] Couldn't pick up {} at {}; skipping it for a while", name, loot.getWorldLocation());
            }
            return;
        }
        pickupFailures.remove(key);
        // Buried bones show up as Prayer xp and in the bones counter instead of the loot value.
        if (!(config.buryBigBones() && BIG_BONES.equalsIgnoreCase(name))) {
            stats.addLoot(name, quantity, unitPrice(id) * quantity);
        }
        antibanAfterAction();
    }

    /** Climbs the shed ladder out of the dungeon; without food, then rests on the surface before going back. */
    private void escape() {
        if (inDungeon()) {
            Microbot.status = "Low health, no food: escaping up the ladder";
            Rs2TileObjectModel ladder = Microbot.getRs2TileObjectCache().query()
                    .withName("Ladder")
                    .withAction("Climb-up")
                    .within(ESCAPE_LADDER, 3)
                    .nearestOnClientThread();
            WorldPoint here = Rs2Player.getWorldLocation();
            if (ladder != null && here != null && here.distanceTo(ESCAPE_LADDER) <= 6) {
                if (ladder.click("Climb-up")) {
                    sleepUntil(() -> !inDungeon(), 5000);
                }
                return;
            }
            Rs2Walker.walkWithState(ESCAPE_LADDER, 1);
            return;
        }

        if (!config.useFood() && Rs2Player.getHealthPercentage() < REST_UNTIL_PERCENT) {
            Microbot.status = "Safe; resting until " + REST_UNTIL_PERCENT + "% health";
            markIntentionalWait();
            return;
        }
        escapeDone = true;
    }

    private void bank() {
        // Bury a partial batch on the way out rather than banking the bones, unless we're leaving because
        // health is low with nothing to eat; then the bones just go in the bank.
        if (!Rs2Bank.isOpen() && config.buryBigBones() && Rs2Inventory.hasItem(BIG_BONES, true)
                && Rs2Player.getHealthPercentage() > config.eatAtPercent()) {
            buryAllBigBones();
            return;
        }
        if (!Rs2Bank.isOpen()) {
            Microbot.status = "Walking to Varrock west bank";
            Rs2Bank.walkToBankAndUseBank(BankLocation.VARROCK_WEST);
            return;
        }

        if (Rs2Inventory.items(this::isBankable).findAny().isPresent()) {
            Microbot.status = "Banking loot";
            Rs2Bank.depositAll(this::isBankable);
            if (sleepUntil(() -> Rs2Inventory.items(this::isBankable).findAny().isEmpty(), 4000)) {
                stats.addBankTrip();
            }
            return;
        }

        if (config.useBrassKey() && !hasKey() && !bankCheckedForKey) {
            bankCheckedForKey = true;
            if (Rs2Bank.hasItem(BRASS_KEY, true)) {
                Microbot.status = "Withdrawing the brass key";
                Rs2Bank.withdrawX(BRASS_KEY, 1, true);
                sleepUntil(this::hasKey, 3000);
            }
            return;
        }

        if (config.useStaminas() && StaminaPotions.topUp(config.staminaCount())) {
            return;
        }

        if (!config.useFood()) {
            Rs2Bank.closeBank();
            bankDone = true;
            return;
        }

        int missing = config.foodAmount() - foodCount();
        if (missing > 0 && !foodWithdrawTried) {
            foodWithdrawTried = true;
            Microbot.status = "Withdrawing food";
            int before = foodCount();
            String name = config.foodName().trim();
            // Fall back to any food, so fish bought on the GE get used even if a different food is configured.
            boolean withdrew = !name.isEmpty() && Rs2Bank.hasItem(name, true)
                    ? Rs2Bank.withdrawX(name, missing, true)
                    : Rs2Bank.withdrawX(Rs2ItemModel::isFood, missing);
            if (!withdrew || !sleepUntil(() -> foodCount() > before, 3000)) {
                log.info("[HillGiants] Couldn't withdraw {} from the bank", name.isEmpty() ? "any food" : name);
            }
            return;
        }

        if (!hasFood() && config.foodAmount() > 0 && config.restockFromGe()
                && System.currentTimeMillis() >= restockBlockedUntil) {
            if (planRestock()) {
                Rs2Bank.closeBank();
                restockPlanned = true;
            } else {
                restockBlockedUntil = System.currentTimeMillis() + RESTOCK_RETRY_MS;
            }
            return;
        }

        if (!hasFood() && Rs2Player.getHealthPercentage() < REST_UNTIL_PERCENT) {
            Microbot.status = "No food, waiting at the bank for health to regenerate";
            markIntentionalWait();
            return;
        }

        Rs2Bank.closeBank();
        bankDone = true;
    }

    private void logoutAtBank() {
        if (Rs2GrandExchange.isOpen()) {
            Rs2GrandExchange.closeExchange();
            return;
        }
        if (!Rs2Bank.isOpen()) {
            Microbot.status = "Low health with no food, walking to Varrock west bank to log out";
            Rs2Bank.walkToBankAndUseBank(BankLocation.VARROCK_WEST);
            return;
        }

        Microbot.status = "Logging out";
        if (Rs2Player.logoutWithoutAutoLogin() == Rs2Player.TerminalLogoutState.LOGGED_OUT) {
            log.info("[HillGiants] Out of food at {}% health; logged out at Varrock west bank", Rs2Player.getHealthPercentage());
            Microbot.stopPlugin(HillGiantPlugin.class);
        }
    }

    /**
     * With the bank open and no food anywhere: picks the fish for our Hitpoints level, withdraws coins up to the
     * budget and, if they fall short, notes of the cheapest herbs to cover the gap. False if the trip can't work.
     */
    private boolean planRestock() {
        int totalLevel = totalLevel();
        if (totalLevel < GE_MIN_TOTAL_LEVEL) {
            log.info("[HillGiants] Out of food, but total level {} is below {} so the Grand Exchange is restricted",
                    totalLevel, GE_MIN_TOTAL_LEVEL);
            return false;
        }

        Fish fish = Fish.forHitpoints(Rs2Player.getRealSkillLevel(Skill.HITPOINTS));
        long buyPrice = buyPrice(fish, GE_MAX_BUY_ATTEMPTS);
        int quantity = (int) Math.min(config.restockAmount(), config.restockBudget() / buyPrice);
        if (buyPrice <= 0 || quantity < 1) {
            log.info("[HillGiants] Food budget of {} can't buy any {}", config.restockBudget(), fish.itemName);
            return false;
        }
        long cost = quantity * buyPrice;

        int bankCoins = Rs2Bank.count(COINS, true);
        int coins = Rs2Inventory.itemQuantity(COINS, true);
        int toWithdraw = (int) Math.min(bankCoins, Math.max(0, cost - coins));
        if (toWithdraw > 0) {
            Rs2Bank.withdrawX(COINS, toWithdraw, true);
            int expected = coins + toWithdraw;
            sleepUntil(() -> Rs2Inventory.itemQuantity(COINS, true) >= expected, 3000);
        }
        coins = Rs2Inventory.itemQuantity(COINS, true);

        long shortfall = cost - coins;
        if (shortfall > 0 && !withdrawHerbsToSell(shortfall) && coins < buyPrice) {
            log.info("[HillGiants] Out of food with {} coins and no herbs to sell", coins);
            return false;
        }

        restockFish = fish;
        restockTarget = quantity;
        log.info("[HillGiants] Restocking {} x {} (budget {}, carrying {} coins)",
                quantity, fish.itemName, config.restockBudget(), coins);
        return true;
    }

    /** Withdraws noted grimy herbs, cheapest first, until their estimated sale covers {@code coinsNeeded}. */
    private boolean withdrawHerbsToSell(long coinsNeeded) {
        List<Rs2ItemModel> herbs = Rs2Bank.bankItems().stream()
                .filter(item -> item.getName() != null && item.getName().toLowerCase(Locale.ROOT).startsWith(GRIMY_PREFIX))
                .filter(item -> unitPrice(item.getId()) > 0)
                .sorted(Comparator.comparingLong(item -> unitPrice(item.getId())))
                .collect(Collectors.toList());
        if (herbs.isEmpty() || !Rs2Bank.setWithdrawAs(true)) {
            return false;
        }

        boolean any = false;
        long covered = 0;
        for (Rs2ItemModel herb : herbs) {
            if (covered >= coinsNeeded) break;
            long each = (long) (unitPrice(herb.getId()) * HERB_SALE_FACTOR);
            int quantity = (int) Math.min(herb.getQuantity(), (coinsNeeded - covered + each - 1) / Math.max(1, each));
            if (quantity < 1) continue;
            String name = herb.getName();
            int before = Rs2Inventory.itemQuantity(name, true);
            if (Rs2Bank.withdrawX(name, quantity, true)
                    && sleepUntil(() -> Rs2Inventory.itemQuantity(name, true) > before, 3000)) {
                covered += quantity * each;
                any = true;
            }
        }
        Rs2Bank.setWithdrawAs(false);
        log.info("[HillGiants] Selling herbs worth ~{} to cover {} coins", covered, coinsNeeded);
        return any;
    }

    private void restock() {
        if (Rs2Bank.isOpen()) {
            Rs2Bank.closeBank();
            return;
        }
        if (!Rs2GrandExchange.isOpen()) {
            Microbot.status = "Walking to the Grand Exchange";
            if (!Rs2GrandExchange.openExchange()) {
                Rs2GrandExchange.walkToGrandExchange();
            }
            return;
        }

        if (!Rs2GrandExchange.isAllSlotsEmpty()) {
            if (Rs2GrandExchange.hasFinishedSellingOffers() || Rs2GrandExchange.hasFinishedBuyingOffers()) {
                Microbot.status = "Collecting from the Grand Exchange";
                Rs2GrandExchange.collectAllToInventory();
                sleepUntil(Rs2GrandExchange::isAllSlotsEmpty, 3000);
            } else if (offerPlacedAt == 0 || System.currentTimeMillis() - offerPlacedAt > GE_OFFER_TIMEOUT_MS) {
                // Unfilled (or left over from before the script started): pull it back and relist.
                Microbot.status = "Aborting unfilled offer";
                Rs2GrandExchange.abortAllOffers(false);
                sleepUntil(Rs2GrandExchange::isAllSlotsEmpty, 5000);
            } else {
                Microbot.status = "Waiting for the offer to fill";
                markIntentionalWait();
            }
            return;
        }

        Rs2ItemModel herb = Rs2Inventory.items(item -> item.isNoted() && item.getName() != null
                && item.getName().toLowerCase(Locale.ROOT).startsWith(GRIMY_PREFIX)).findFirst().orElse(null);
        if (herb != null) {
            int percent = -Math.min(50, 10 + 10 * sellAttempts);
            Microbot.status = "Selling " + herb.getQuantity() + " x " + herb.getName() + " (" + percent + "%)";
            GrandExchangeRequest request = GrandExchangeRequest.builder()
                    .action(GrandExchangeAction.SELL)
                    .itemName(herb.getName())
                    .exact(true)
                    .quantity(herb.getQuantity())
                    .percent(percent)
                    .build();
            if (Rs2GrandExchange.processOffer(request)) {
                offerPlacedAt = System.currentTimeMillis();
                sellAttempts++;
            }
            return;
        }

        Fish fish = restockFish;
        boolean gotFish = fish != null && Rs2Inventory.itemQuantity(fish.itemName, true) > 0;
        if (fish == null || gotFish || buyAttempts >= GE_MAX_BUY_ATTEMPTS) {
            finishRestock(gotFish);
            return;
        }

        int percent = Math.min(50, 10 + 10 * buyAttempts);
        int coins = Rs2Inventory.itemQuantity(COINS, true);
        int quantity = (int) Math.min(restockTarget, coins / Math.max(1, buyPrice(fish, buyAttempts)));
        if (quantity < 1) {
            finishRestock(false);
            return;
        }

        Microbot.status = "Buying " + quantity + " x " + fish.itemName + " (+" + percent + "%)";
        GrandExchangeRequest request = GrandExchangeRequest.builder()
                .action(GrandExchangeAction.BUY)
                .itemName(fish.itemName)
                .exact(true)
                .quantity(quantity)
                .percent(percent)
                .build();
        if (Rs2GrandExchange.processOffer(request)) {
            offerPlacedAt = System.currentTimeMillis();
        }
        buyAttempts++;
    }

    private void finishRestock(boolean gotFish) {
        Rs2GrandExchange.closeExchange();
        if (gotFish) {
            log.info("[HillGiants] Bought {} x {}", Rs2Inventory.itemQuantity(restockFish.itemName, true), restockFish.itemName);
        } else {
            log.info("[HillGiants] Grand Exchange restock failed; resting at the bank instead for a while");
            restockBlockedUntil = System.currentTimeMillis() + RESTOCK_RETRY_MS;
        }
        restockDone = true;
    }

    /** GE price plus the markup used on the given buy attempt, so the quantity stays affordable. */
    private static long buyPrice(Fish fish, int attempt) {
        long price = unitPrice(fish.itemId);
        int percent = Math.min(50, 10 + 10 * attempt);
        return price <= 0 ? 0 : price * (100 + percent) / 100 + 1;
    }

    // --- Targeting & loot ---

    /** A giant already attacking us comes first; otherwise the nearest free one in the room. */
    private Rs2NpcModel findTarget() {
        Player me = Microbot.getClient().getLocalPlayer();
        Rs2NpcModel attacker = Microbot.getRs2NpcCache().query()
                .withName(HILL_GIANT)
                .where(npc -> !npc.isDead() && npc.getInteracting() == me)
                .nearestReachableOnClientThread();
        if (attacker != null) {
            return attacker;
        }
        return Microbot.getRs2NpcCache().query()
                .withName(HILL_GIANT)
                .where(npc -> !npc.isDead() && npc.getHealthRatio() != 0)
                .where(npc -> GIANT_AREA.contains(npc.getWorldLocation()))
                .where(npc -> npc.getInteracting() == null || npc.getInteracting() == me)
                .nearestReachableOnClientThread();
    }

    private Rs2TileItemModel findLoot() {
        Set<String> extras = extraLootNames();
        int minHerbValue = config.minHerbValue();
        return Microbot.getRs2TileItemCache().query()
                .where(Rs2TileItemModel::isLootAble)
                .where(item -> !item.isDespawned() && !isSkippedLoot(item) && stillOnGround(item))
                .where(item -> GIANT_AREA.contains(item.getWorldLocation()))
                .where(item -> {
                    String name = item.getName();
                    if (name == null) return false;
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (extras.contains(lower)) return true;
                    if (BIG_BONES.equalsIgnoreCase(name)) return true;
                    return lower.startsWith(GRIMY_PREFIX) && unitPrice(item.getId()) >= minHerbValue;
                })
                .nearestReachableOnClientThread(12);
    }

    private static String lootKey(Rs2TileItemModel item) {
        return item.getId() + "@" + item.getWorldLocation();
    }

    /**
     * Whether the item is really lying on its tile in the live scene. The tile item cache can hold on to a drop
     * that's already gone, which would otherwise be targeted forever. Runs on the client thread inside the query.
     */
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

    private boolean isSkippedLoot(Rs2TileItemModel item) {
        Long until = skippedLoot.get(lootKey(item));
        if (until == null) return false;
        if (until > System.currentTimeMillis()) return true;
        skippedLoot.remove(lootKey(item));
        return false;
    }

    private Set<String> extraLootNames() {
        return Arrays.stream(config.extraLoot().split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    // --- Guards & helpers ---

    private boolean inGiantArea() {
        WorldPoint here = Rs2Player.getWorldLocation();
        return here != null && GIANT_AREA.contains(here);
    }

    private boolean hasKey() {
        return Rs2Inventory.hasItem(BRASS_KEY, true);
    }

    /** Only once the bank has been checked, so a banked key is withdrawn instead of fetching a new one. */
    private boolean needsKey() {
        return config.useBrassKey() && !hasKey() && bankCheckedForKey;
    }

    /** Everything goes in the bank except food and the brass key. */
    private boolean isBankable(Rs2ItemModel item) {
        return !item.isFood() && !BRASS_KEY.equalsIgnoreCase(item.getName()) && !StaminaPotions.isStamina(item.getId());
    }

    private boolean hasFood() {
        return !Rs2Inventory.getInventoryFood().isEmpty();
    }

    private int foodCount() {
        return Rs2Inventory.getInventoryFood().size();
    }

    /** Still around the hill giant room; a little slack past its edge so chasing a target there doesn't end the fight. */
    private boolean nearGiants() {
        WorldPoint here = Rs2Player.getWorldLocation();
        return here != null && here.distanceTo(GIANT_CENTER) <= 20;
    }

    /** Eats if health is at the threshold; true when this tick was spent on it. */
    private boolean eatIfNeeded() {
        if (!needsToEat()) return false;
        if (Rs2Bank.isOpen()) {
            Rs2Bank.closeBank();
            return true;
        }
        if (Rs2GrandExchange.isOpen()) {
            Rs2GrandExchange.closeExchange();
            return true;
        }
        Microbot.status = "Eating";
        eatFood();
        return true;
    }

    /**
     * Eats one piece of food and waits for it to leave the inventory. Waiting on the animation instead would
     * stall for seconds in combat, where attack animations never stop.
     */
    private void eatFood() {
        int before = foodCount();
        if (Rs2Player.useFood()) {
            sleepUntil(() -> foodCount() < before, 1800);
        }
    }

    // --- Watchdog ---

    /**
     * Keeps the state machine moving when a state stops making progress (no combat xp, no movement, no inventory
     * change, and no deliberate wait). Recovery escalates per state:
     * FIGHT re-targets at 15s and re-walks to the room at 30s; WALK_TO_GIANTS resets the walker at 20s; any state
     * restarts from WALK_TO_GIANTS (whose guards re-route to ESCAPE, BANK or GET_KEY as needed) at 90s.
     *
     * @return true if this tick was spent on a recovery action
     */
    private boolean runWatchdog() {
        State state = getCurrentState();
        long now = System.currentTimeMillis();
        if (state == null || state == State.LOGOUT) return false;

        String signature = progressSignature();
        boolean progressed = !signature.equals(progressSignature)
                || now - lastIntentionalWaitAt < INTENTIONAL_WAIT_GRACE_MS;
        progressSignature = signature;
        if (state != watchedState || progressed) {
            watchedState = state;
            lastProgressAt = now;
            recoveryLevel = 0;
            return false;
        }

        long idle = now - lastProgressAt;
        if (idle >= ANY_STATE_RESTART_MS && recoveryLevel < 3) {
            // Re-targeting and walking back didn't help: stuck. Log out and stop rather than keep trying.
            recoveryLevel = 3;
            stats.addRecovery();
            StuckGuard.trip("no progress in " + state + " for " + idle / 1000 + "s");
            return true;
        }
        if (state == State.FIGHT) {
            if (idle >= FIGHT_REWALK_MS && recoveryLevel < 2) {
                recoveryLevel = 2;
                restart(State.WALK_TO_GIANTS, "no combat for " + idle / 1000 + "s; walking back to the middle of the room");
                return true;
            }
            if (idle >= FIGHT_RETARGET_MS && recoveryLevel < 1) {
                recoveryLevel = 1;
                log.warn("[HillGiants] Watchdog: no combat for {}s, re-targeting", idle / 1000);
                Microbot.status = "Watchdog: re-targeting";
                stats.addRecovery();
                Rs2AntibanSettings.actionCooldownActive = false;
                Rs2NpcModel giant = findTarget();
                if (giant != null && giant.click("Attack")) {
                    sleepUntil(Rs2Player::isInteracting, 3000);
                }
                return true;
            }
        }
        if (state == State.WALK_TO_GIANTS && idle >= WALK_RESET_MS && recoveryLevel < 1) {
            recoveryLevel = 1;
            log.warn("[HillGiants] Watchdog: walker made no progress for {}s, resetting it", idle / 1000);
            Microbot.status = "Watchdog: resetting the walker";
            stats.addRecovery();
            Rs2Walker.setTarget(null, "Hill giants watchdog reset");
            return true;
        }
        return false;
    }

    /** Forces a state as if a normal transition fired, after clearing anything that could be holding us. */
    private void restart(State to, String reason) {
        State from = getCurrentState();
        log.warn("[HillGiants] Watchdog: {} ({} -> {})", reason, from, to);
        Microbot.status = "Watchdog: " + reason;
        stats.addRecovery();
        Rs2AntibanSettings.actionCooldownActive = false;
        Rs2Walker.setTarget(null, "Hill giants watchdog restart");
        closeOpenInterfaces();
        pickupFailures.clear();
        forceState(to, "Watchdog: " + reason);
        // forceState skips onTransition, so reset the per-state flags here.
        onTransition(from, to, reason);
    }

    /** What counts as progress: combat/prayer xp, position and inventory contents. */
    private String progressSignature() {
        long xp = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            long total = 0;
            for (Skill skill : HillGiantStats.TRACKED_SKILLS) {
                total += Microbot.getClient().getSkillExperience(skill);
            }
            return total;
        }).orElse(0L);
        int inventory = Rs2Inventory.items().mapToInt(Rs2ItemModel::getQuantity).sum();
        // Health is left out on purpose: taking hits without hitting back is exactly the stall to catch.
        return xp + "|" + Rs2Player.getWorldLocation() + "|" + inventory;
    }

    private void markIntentionalWait() {
        lastIntentionalWaitAt = System.currentTimeMillis();
    }

    /** A live giant in the room that nobody else is fighting, reachable or not. */
    private boolean freeGiantInRoom() {
        Player me = Microbot.getClient().getLocalPlayer();
        return Microbot.getRs2NpcCache().query()
                .withName(HILL_GIANT)
                .where(npc -> !npc.isDead() && npc.getHealthRatio() != 0)
                .where(npc -> GIANT_AREA.contains(npc.getWorldLocation()))
                .where(npc -> npc.getInteracting() == null || npc.getInteracting() == me)
                .nearestOnClientThread() != null;
    }

    private void closeOpenInterfaces() {
        if (Rs2Bank.isOpen()) Rs2Bank.closeBank();
        if (Rs2GrandExchange.isOpen()) Rs2GrandExchange.closeExchange();
    }

    /** Whether any NPC is currently targeting us. */
    private boolean underAttack() {
        Player me = Microbot.getClient().getLocalPlayer();
        return me != null && Microbot.getRs2NpcCache().query()
                .where(npc -> npc.getInteracting() == me)
                .nearestOnClientThread() != null;
    }

    private boolean needsToEat() {
        return hasFood() && Rs2Player.getHealthPercentage() <= config.eatAtPercent();
    }

    /** Food covers both low health (eat) and a full inventory (eat to make room), so only bank once it's gone. */
    private boolean needsBank() {
        if (hasFood()) {
            return false;
        }
        // A full inventory with big bones to bury makes room by burying, not banking.
        boolean fullOfLoot = Rs2Inventory.isFull() && !shouldBuryBatch();
        // Without food, low health is handled by needsLogout() instead.
        return fullOfLoot || (config.useFood() && Rs2Player.getHealthPercentage() <= config.eatAtPercent());
    }

    private boolean shouldBuryBatch() {
        return config.buryBigBones() && Rs2Inventory.isFull() && Rs2Inventory.hasItem(BIG_BONES, true);
    }

    /** Buries every big bone in the inventory back to back, stopping to eat if health drops. */
    private void buryAllBigBones() {
        while (isRunning() && Rs2Inventory.hasItem(BIG_BONES, true)) {
            if (needsToEat()) {
                eatFood();
                continue;
            }
            if (needsEscape()) {
                break;
            }
            int before = Rs2Inventory.count(BIG_BONES, true);
            Microbot.status = "Burying big bones (" + before + " left)";
            if (!Rs2Inventory.interact(BIG_BONES, "Bury", true)
                    || !sleepUntil(() -> Rs2Inventory.count(BIG_BONES, true) < before, 2400)) {
                break;
            }
            stats.addBoneBuried();
            // Burying takes two ticks; don't click the next bone before the last one finishes.
            sleep(700, 1000);
        }
        antibanAfterAction();
    }

    /** Out of food and under the escape threshold while underground with the giants. */
    private boolean needsEscape() {
        return !hasFood() && inDungeon() && Rs2Player.getHealthPercentage() < config.escapeAtPercent();
    }

    private boolean inDungeon() {
        WorldPoint here = Rs2Player.getWorldLocation();
        return here != null && here.getY() > DUNGEON_MIN_Y;
    }

    private boolean needsLogout() {
        return !config.useFood() && !hasFood() && Rs2Player.getHealthPercentage() < LOGOUT_AT_PERCENT;
    }

    private int totalLevel() {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> Microbot.getClient().getTotalLevel()).orElse(0);
    }

    private static long unitPrice(int itemId) {
        return ItemPrices.guidePrice(itemId);
    }

    private void antibanAfterAction() {
        if (!config.antiban()) return;
        Rs2Antiban.actionCooldown();
        Rs2Antiban.takeMicroBreakByChance();
    }

    // --- Lifecycle ---

    public boolean run(HillGiantConfig config) {
        this.config = config;
        pickupFailures.clear();
        skippedLoot.clear();
        watchedState = null;
        recoveryLevel = 0;
        progressSignature = "";
        bankCheckedForKey = false;
        stats.reset();

        if (config.antiban()) {
            Rs2Antiban.resetAntibanSettings();
            Rs2Antiban.antibanSetupTemplates.applyCombatSetup();
            MouseIdleHabits.enable();
        }

        StuckGuard.attach("Shadowfax Hill Giants", HillGiantPlugin.class);

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (StuckGuard.isTripped()) return;
                if (!Microbot.isLoggedIn()) return;
                stats.captureBaselineIfNeeded();
                if (runWatchdog()) return;
                // Eating comes before every state, so walking or banking at low health never skips it.
                if (eatIfNeeded()) return;
                if (Rs2AntibanSettings.actionCooldownActive) {
                    // The antiban cooldown only counts down while out of combat, which barely happens here, so it
                    // could hold the script for good. Wait it out briefly when idle; in a fight, or once it has run
                    // a few seconds, cancel it and keep acting.
                    long now = System.currentTimeMillis();
                    if (cooldownDeadline == 0) {
                        cooldownDeadline = now + ThreadLocalRandom.current().nextLong(MIN_COOLDOWN_MS, MAX_COOLDOWN_MS);
                    }
                    if (!Rs2Combat.inCombat() && !underAttack() && now < cooldownDeadline) {
                        Microbot.status = "Antiban pause";
                        return;
                    }
                    Rs2AntibanSettings.actionCooldownActive = false;
                }
                cooldownDeadline = 0;
                // Only on the move: fighting doesn't drain run energy, so a dose there would be wasted.
                if (config.useStaminas() && getCurrentState() != State.FIGHT && getCurrentState() != State.LOGOUT
                        && StaminaPotions.drinkIfNeeded(config.staminaAtEnergy())) {
                    return;
                }
                step();
            } catch (Throwable ex) {
                // Throwable, not Exception: an uncaught Error (e.g. a client-thread AssertionError) would otherwise
                // cancel the scheduled task without any log line.
                log.error("[HillGiants] Unexpected error in scheduled loop", ex);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);

        return true;
    }

    @Override
    public void shutdown() {
        StuckGuard.detach(HillGiantPlugin.class);
        super.shutdown();
        Rs2Antiban.resetAntibanSettings();
    }
}
