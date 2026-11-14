package net.runelite.client.plugins.microbot.shadowfaxaccountstarter;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigManager;
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
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeAction;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeRequest;
import net.runelite.client.plugins.microbot.util.grandexchange.Rs2GrandExchange;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.player.StaminaPotions;
import net.runelite.client.plugins.microbot.util.safety.StuckGuard;
import net.runelite.client.plugins.microbot.util.shop.Rs2Shop;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.walker.WalkerState;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Starts a fresh account on the cows in the pen east of Lumbridge. Gathers and cooks raw beef in the Lumbridge
 * castle kitchen when health runs low, banks cowhides at Lumbridge, and every N attack levels sells the hides on
 * the Grand Exchange to buy the next scimitar from Zeke in Al Kharid.
 * <p>
 * FIGHT ⇄ COOK, FIGHT → BANK → (SELL_HIDES →) BUY_SCIMITAR → WALK_TO_PEN → FIGHT.
 * Dying or wandering off from any state ends up back in WALK_TO_PEN. A watchdog re-targets a stalled fight and
 * resets a stalled walk; a state that makes no progress at all for 90 seconds trips {@link StuckGuard}, which logs
 * out and stops the plugin.
 */
@Slf4j
@Singleton
public class AccountStarterScript extends StateMachineScript<AccountStarterScript.State> {

    static final String LAST_UPGRADE_LEVEL_KEY = "lastUpgradeAttackLevel";

    enum State {
        WALK_TO_PEN,
        FIGHT,
        COOK,
        BANK,
        SELL_HIDES,
        BUY_SCIMITAR
    }

    /** Scimitars Zeke sells, cheapest first. */
    enum Scimitar {
        BRONZE("Bronze scimitar", 1, 32),
        IRON("Iron scimitar", 1, 112),
        STEEL("Steel scimitar", 5, 400),
        MITHRIL("Mithril scimitar", 20, 1040);

        final String itemName;
        final int attackLevel;
        final int price;

        Scimitar(String itemName, int attackLevel, int price) {
            this.itemName = itemName;
            this.attackLevel = attackLevel;
            this.price = price;
        }
    }

    private enum BankResult { PENDING, TO_PEN, TO_GE, TO_ZEKE }

    // Lumbridge cow field, east of the river.
    private static final WorldArea COW_PEN = new WorldArea(3253, 3255, 13, 44, 0);
    private static final WorldPoint COW_PEN_CENTER = new WorldPoint(3259, 3272, 0);
    private static final WorldPoint LUMBRIDGE_KITCHEN = new WorldPoint(3209, 3214, 0);
    private static final WorldPoint ZEKE_SHOP = new WorldPoint(3287, 3189, 0);

    private static final String COW = "Cow";
    private static final String COWHIDE = "Cowhide";
    private static final String RAW_BEEF = "Raw beef";
    private static final String COOKED_MEAT = "Cooked meat";
    private static final String BURNT_MEAT = "Burnt meat";
    private static final String BONES = "Bones";
    private static final String COINS = "Coins";
    // Partial name match, so every dose is kept.
    private static final String STAMINA_POTION = "Stamina potion";
    // The walker may route through the Al Kharid toll gate.
    private static final int TOLL_FEE = 10;
    private static final long GE_OFFER_TIMEOUT_MS = 45_000;
    // Accounts below this total level are restricted from the Grand Exchange.
    private static final int GE_MIN_TOTAL_LEVEL = 100;

    // Watchdog: how long a state may go without progress before each recovery step.
    private static final long FIGHT_RETARGET_MS = 15_000;
    private static final long WALK_RESET_MS = 20_000;
    private static final long ANY_STATE_STUCK_MS = 90_000;
    // Deliberate waits (respawns, regenerating health, GE offers) count as progress for this long.
    private static final long INTENTIONAL_WAIT_GRACE_MS = 2_000;

    @Inject
    private ConfigManager configManager;

    @Inject
    private AccountStarterStats stats;

    private AccountStarterConfig config;

    // Per-state flags, reset on every transition.
    private BankResult bankResult = BankResult.PENDING;
    private boolean bankTripCounted;
    private boolean sellDone;
    private boolean buyDone;

    // Set when banked coins + hides can't pay for the next scimitar; cleared on the next full-inventory bank trip.
    private boolean fundsShort;
    private int sellAttempts;
    private long offerPlacedAt;

    // Watchdog state.
    private State watchedState;
    private long lastProgressAt;
    private long lastIntentionalWaitAt;
    private int recoveryLevel;
    private String progressSignature = "";

    @Override
    protected State initialState() {
        return inPen() ? State.FIGHT : State.WALK_TO_PEN;
    }

    @Override
    protected List<Transition<State>> defineTransitions() {
        return List.of(
                Transition.<State>from(State.WALK_TO_PEN)
                        .when(this::needsCooking, "needsCooking()")
                        .because("Low on health with raw beef to cook")
                        .goTo(State.COOK),
                Transition.<State>from(State.WALK_TO_PEN)
                        .when(this::inPen, "inPen()")
                        .because("Arrived at the cow pen")
                        .goTo(State.FIGHT),

                Transition.<State>from(State.FIGHT)
                        .when(this::needsCooking, "needsCooking()")
                        .because("Low on health with raw beef to cook")
                        .goTo(State.COOK),
                Transition.<State>from(State.FIGHT)
                        .when(Rs2Inventory::isFull, "Rs2Inventory.isFull()")
                        .because("Inventory full, banking hides")
                        .goTo(State.BANK),
                Transition.<State>from(State.FIGHT)
                        .when(() -> upgradeDue() && !fundsShort, "upgradeDue() && !fundsShort")
                        .because("Reached the attack level for the next scimitar")
                        .goTo(State.BANK),
                Transition.<State>from(State.FIGHT)
                        .when(() -> !inPen(), "!inPen()")
                        .because("Left the cow pen (died or wandered off)")
                        .goTo(State.WALK_TO_PEN),

                Transition.<State>from(State.COOK)
                        .when(() -> !Rs2Inventory.hasItem(RAW_BEEF, true), "!hasItem(Raw beef)")
                        .because("All beef cooked")
                        .goTo(State.WALK_TO_PEN),

                Transition.<State>from(State.BANK)
                        .when(() -> bankResult == BankResult.TO_GE, "bankResult == TO_GE")
                        .because("Withdrew noted hides to sell")
                        .goTo(State.SELL_HIDES),
                Transition.<State>from(State.BANK)
                        .when(() -> bankResult == BankResult.TO_ZEKE, "bankResult == TO_ZEKE")
                        .because("Enough coins already banked for the next scimitar")
                        .goTo(State.BUY_SCIMITAR),
                Transition.<State>from(State.BANK)
                        .when(() -> bankResult == BankResult.TO_PEN, "bankResult == TO_PEN")
                        .because("Banking done")
                        .goTo(State.WALK_TO_PEN),

                Transition.<State>from(State.SELL_HIDES)
                        .when(() -> sellDone && canAffordUpgrade(), "sellDone && canAffordUpgrade()")
                        .because("Hides sold, enough coins for the next scimitar")
                        .goTo(State.BUY_SCIMITAR),
                Transition.<State>from(State.SELL_HIDES)
                        .when(() -> sellDone, "sellDone")
                        .because("Hides sold but not enough coins yet")
                        .goTo(State.WALK_TO_PEN),

                Transition.<State>from(State.BUY_SCIMITAR)
                        .when(() -> buyDone, "buyDone")
                        .because("Scimitar purchase finished")
                        .goTo(State.WALK_TO_PEN)
        );
    }

    @Override
    protected void onTransition(State from, State to, String reason) {
        super.onTransition(from, to, reason);
        bankResult = BankResult.PENDING;
        bankTripCounted = false;
        sellDone = false;
        buyDone = false;
        sellAttempts = 0;
        offerPlacedAt = 0;
    }

    @Override
    protected void onState(State state) {
        switch (state) {
            case WALK_TO_PEN:
                walkToPen();
                break;
            case FIGHT:
                fight();
                break;
            case COOK:
                cook();
                break;
            case BANK:
                bank();
                break;
            case SELL_HIDES:
                sellHides();
                break;
            case BUY_SCIMITAR:
                buyScimitar();
                break;
        }
    }

    // --- States ---

    private void walkToPen() {
        Microbot.status = "Walking to the cow pen";
        if (Rs2Bank.isOpen()) Rs2Bank.closeBank();
        if (Rs2Shop.isOpen()) Rs2Shop.closeShop();
        if (Rs2GrandExchange.isOpen()) Rs2GrandExchange.closeExchange();
        if (Rs2Walker.walkWithState(COW_PEN_CENTER, 4) == WalkerState.UNREACHABLE) {
            log.warn("[AccountStarter] Cow pen unreachable from {}", Rs2Player.getWorldLocation());
        }
    }

    private void fight() {
        if (hasFood() && Rs2Player.eatAt(config.eatAtPercent())) {
            Microbot.status = "Eating";
            Rs2Player.waitForAnimation();
            return;
        }

        if (config.buryBones() && Rs2Inventory.hasItem(BONES, true)) {
            Microbot.status = "Burying bones";
            Rs2Inventory.interact(BONES, "Bury", true);
            Rs2Player.waitForAnimation();
            return;
        }

        if (Rs2Player.isInteracting()) {
            Microbot.status = "Fighting";
            return;
        }

        Rs2TileItemModel loot = findLoot();
        if (loot != null) {
            Microbot.status = "Looting " + loot.getName();
            int before = Rs2Inventory.count();
            if (loot.pickup()) {
                sleepUntil(() -> Rs2Inventory.count() != before, 5000);
            }
            return;
        }

        if (!hasFood() && Rs2Player.getHealthPercentage() <= config.restAtPercent()) {
            Microbot.status = "Out of food, waiting for health to regenerate";
            markIntentionalWait();
            return;
        }

        Rs2NpcModel cow = Microbot.getRs2NpcCache().query()
                .withName(COW)
                .where(npc -> !npc.isDead() && npc.getHealthRatio() != 0)
                .where(npc -> COW_PEN.contains(npc.getWorldLocation()))
                .where(npc -> npc.getInteracting() == null
                        || npc.getInteracting() == Microbot.getClient().getLocalPlayer())
                .nearestReachableOnClientThread();
        if (cow == null) {
            Microbot.status = "Waiting for cows to respawn";
            markIntentionalWait();
            return;
        }

        Microbot.status = "Attacking cow";
        if (cow.click("Attack") && sleepUntil(Rs2Player::isInteracting, 3000)) {
            antibanAfterAction();
        }
    }

    private void cook() {
        if (Rs2Inventory.hasItem(BURNT_MEAT, true)) {
            Rs2Inventory.dropAll(true, BURNT_MEAT);
            return;
        }

        if (Rs2Dialogue.hasContinue()) {
            Rs2Dialogue.clickContinue();
            return;
        }

        if (Rs2Widget.isProductionWidgetOpen()) {
            Microbot.status = "Cooking";
            // Space picks the highlighted "Cook" option at the selected quantity (All by default).
            Rs2Keyboard.keyPress(KeyEvent.VK_SPACE);
            if (sleepUntil(() -> !Rs2Widget.isProductionWidgetOpen(), 2000)) {
                antibanAfterAction();
            }
            int left = Rs2Inventory.count(RAW_BEEF, true);
            // A level-up dialogue interrupts the batch; stop waiting so the next tick can restart it.
            sleepUntil(() -> !Rs2Inventory.hasItem(RAW_BEEF, true) || Rs2Dialogue.hasContinue(), left * 2400 + 3000);
            stats.addMeatCooked(left - Rs2Inventory.count(RAW_BEEF, true));
            return;
        }

        if (Rs2Player.isAnimating(1800)) {
            return;
        }

        Rs2TileObjectModel range = Microbot.getRs2TileObjectCache().query()
                .withNameContains("range")
                .withAction("Cook")
                .within(LUMBRIDGE_KITCHEN, 6)
                .nearestOnClientThread();
        if (range == null) {
            Microbot.status = "Walking to the Lumbridge kitchen";
            Rs2Walker.walkWithState(LUMBRIDGE_KITCHEN, 2);
            return;
        }

        Microbot.status = "Using raw beef on the range";
        if (Rs2Inventory.useUnNotedItemOnObject(RAW_BEEF, range.getTileObject())) {
            sleepUntil(Rs2Widget::isProductionWidgetOpen, 6000);
        }
    }

    private void bank() {
        if (!Rs2Bank.isOpen()) {
            Microbot.status = "Walking to Lumbridge bank";
            Rs2Bank.walkToBankAndUseBank(BankLocation.LUMBRIDGE_TOP);
            return;
        }

        // A full inventory brings fresh hides, so re-check whether an upgrade is affordable.
        if (Rs2Inventory.isFull()) {
            fundsShort = false;
        }

        Microbot.status = "Banking";
        int hides = Rs2Inventory.count(COWHIDE, true);
        Rs2Bank.depositAllExcept(COINS, RAW_BEEF, COOKED_MEAT, STAMINA_POTION);
        if (!sleepUntil(() -> !Rs2Inventory.hasItem(COWHIDE, true), 3000)) {
            return;
        }
        stats.addHidesBanked(hides);
        if (bankResult == BankResult.PENDING && !bankTripCounted) {
            bankTripCounted = true;
            stats.addBankTrip();
        }
        if (config.useStaminas() && StaminaPotions.topUp(config.staminaCount())) {
            return;
        }

        bankResult = planUpgradeFromBank();
        Rs2Bank.closeBank();
    }

    private BankResult planUpgradeFromBank() {
        if (!upgradeDue() || fundsShort) {
            return BankResult.TO_PEN;
        }

        Scimitar target = nextScimitar();
        int needed = target.price + TOLL_FEE;
        int coins = Rs2Bank.count(COINS, true) + Rs2Inventory.itemQuantity(COINS, true);
        int hides = Rs2Bank.count(COWHIDE, true);

        if (coins >= needed) {
            withdrawAllCoins();
            return Rs2Inventory.itemQuantity(COINS, true) >= needed ? BankResult.TO_ZEKE : BankResult.PENDING;
        }

        int totalLevel = totalLevel();
        if (totalLevel < GE_MIN_TOTAL_LEVEL) {
            log.info("[AccountStarter] Total level {} is below {}; the Grand Exchange is restricted. Collecting more hides.",
                    totalLevel, GE_MIN_TOTAL_LEVEL);
            fundsShort = true;
            return BankResult.TO_PEN;
        }

        long estimate = coins + (long) hides * config.hidePriceEstimate();
        if (hides == 0 || estimate < needed) {
            log.info("[AccountStarter] {} needs {} coins; have {} coins and {} hides (~{}). Collecting more hides.",
                    target.itemName, needed, coins, hides, estimate);
            fundsShort = true;
            return BankResult.TO_PEN;
        }

        if (!Rs2Bank.setWithdrawAs(true)) {
            return BankResult.PENDING;
        }
        Rs2Bank.withdrawAll(COWHIDE, true);
        boolean gotHides = sleepUntil(this::hasNotedHides, 3000);
        Rs2Bank.setWithdrawAs(false);
        withdrawAllCoins();
        return gotHides ? BankResult.TO_GE : BankResult.PENDING;
    }

    private void withdrawAllCoins() {
        if (Rs2Bank.hasItem(COINS, true)) {
            Rs2Bank.withdrawAll(COINS, true);
            sleepUntil(() -> !Rs2Bank.hasItem(COINS, true), 3000);
        }
    }

    private void sellHides() {
        if (!Rs2GrandExchange.isOpen()) {
            Microbot.status = "Walking to the Grand Exchange";
            if (!Rs2GrandExchange.openExchange()) {
                Rs2GrandExchange.walkToGrandExchange();
            }
            return;
        }

        if (!Rs2GrandExchange.isAllSlotsEmpty()) {
            if (Rs2GrandExchange.hasFinishedSellingOffers()) {
                Microbot.status = "Collecting coins";
                Rs2GrandExchange.collectAllToInventory();
                sleepUntil(Rs2GrandExchange::isAllSlotsEmpty, 3000);
            } else if (offerPlacedAt == 0 || System.currentTimeMillis() - offerPlacedAt > GE_OFFER_TIMEOUT_MS) {
                // Unfilled (or left over from before the script started): pull it back and relist lower.
                Microbot.status = "Aborting unfilled offer";
                Rs2GrandExchange.abortAllOffers(false);
                sleepUntil(Rs2GrandExchange::isAllSlotsEmpty, 5000);
            } else {
                Microbot.status = "Waiting for cowhides to sell";
                markIntentionalWait();
            }
            return;
        }

        int hides = Rs2Inventory.itemQuantity(COWHIDE, true);
        if (hides > 0) {
            int percent = -Math.min(50, 10 + 10 * sellAttempts);
            Microbot.status = "Selling " + hides + " cowhides (" + percent + "%)";
            GrandExchangeRequest request = GrandExchangeRequest.builder()
                    .action(GrandExchangeAction.SELL)
                    .itemName(COWHIDE)
                    .exact(true)
                    .quantity(hides)
                    .percent(percent)
                    .build();
            if (Rs2GrandExchange.processOffer(request)) {
                offerPlacedAt = System.currentTimeMillis();
                sellAttempts++;
            }
            return;
        }

        Rs2GrandExchange.closeExchange();
        if (!canAffordUpgrade()) {
            log.info("[AccountStarter] Sold hides but only have {} coins", Rs2Inventory.itemQuantity(COINS, true));
            fundsShort = true;
        }
        sellDone = true;
    }

    private void buyScimitar() {
        Scimitar target = nextScimitar();
        if (target == null) {
            buyDone = true;
            return;
        }

        if (Rs2Inventory.hasItem(target.itemName, true)) {
            if (Rs2Shop.isOpen()) {
                Rs2Shop.closeShop();
                return;
            }
            Microbot.status = "Wielding " + target.itemName;
            Rs2Inventory.wield(target.itemName);
            if (sleepUntil(() -> target.itemName.equalsIgnoreCase(equippedWeaponName()), 3000)) {
                log.info("[AccountStarter] Upgraded to {} at attack level {}", target.itemName, attackLevel());
                saveLastUpgradeLevel(attackLevel());
                stats.recordUpgrade(target.itemName);
                buyDone = true;
            }
            return;
        }

        if (Rs2Inventory.itemQuantity(COINS, true) < target.price) {
            log.info("[AccountStarter] Not enough coins for {}", target.itemName);
            fundsShort = true;
            buyDone = true;
            return;
        }

        if (Rs2Shop.isOpen()) {
            if (!Rs2Shop.hasStock(target.itemName)) {
                log.info("[AccountStarter] Zeke is out of {}", target.itemName);
                fundsShort = true;
                buyDone = true;
                return;
            }
            Microbot.status = "Buying " + target.itemName;
            Rs2Shop.buyItem(target.itemName, "1");
            sleepUntil(() -> Rs2Inventory.hasItem(target.itemName, true), 3000);
            return;
        }

        WorldPoint here = Rs2Player.getWorldLocation();
        if (here == null || here.distanceTo(ZEKE_SHOP) > 6) {
            Microbot.status = "Walking to Zeke in Al Kharid";
            Rs2Walker.walkWithState(ZEKE_SHOP, 3);
            return;
        }

        Microbot.status = "Opening Zeke's shop";
        Rs2Shop.openShop("Zeke", true);
    }

    // --- Guards & helpers ---

    private boolean inPen() {
        WorldPoint here = Rs2Player.getWorldLocation();
        return here != null && COW_PEN.contains(here);
    }

    private boolean hasFood() {
        return !Rs2Inventory.getInventoryFood().isEmpty();
    }

    private boolean needsCooking() {
        int rawBeef = Rs2Inventory.count(RAW_BEEF, true);
        if (rawBeef == 0 || hasFood()) {
            return false;
        }
        return rawBeef >= config.foodAmount() || Rs2Player.getHealthPercentage() <= config.eatAtPercent();
    }

    private boolean wantsRawBeef() {
        return Rs2Inventory.count(RAW_BEEF, true) + Rs2Inventory.getInventoryFood().size() < config.foodAmount();
    }

    private Rs2TileItemModel findLoot() {
        boolean wantsBeef = wantsRawBeef();
        boolean wantsBones = config.buryBones();
        boolean wantsHides = config.lootCowhides();
        if (!wantsBeef && !wantsBones && !wantsHides) {
            return null;
        }
        return Microbot.getRs2TileItemCache().query()
                .where(Rs2TileItemModel::isLootAble)
                .where(item -> COW_PEN.contains(item.getWorldLocation()))
                .where(item -> {
                    String name = item.getName();
                    return (wantsHides && COWHIDE.equalsIgnoreCase(name))
                            || (wantsBeef && RAW_BEEF.equalsIgnoreCase(name))
                            || (wantsBones && BONES.equalsIgnoreCase(name));
                })
                .nearestOnClientThread(10);
    }

    private boolean hasNotedHides() {
        Rs2ItemModel hides = Rs2Inventory.get(COWHIDE, true);
        return hides != null && hides.isNoted();
    }

    private int attackLevel() {
        return Rs2Player.getRealSkillLevel(Skill.ATTACK);
    }

    private boolean needsToEat() {
        return hasFood() && Rs2Player.getHealthPercentage() <= config.eatAtPercent();
    }

    private void antibanAfterAction() {
        if (!config.antiban()) return;
        Rs2Antiban.actionCooldown();
        Rs2Antiban.takeMicroBreakByChance();
    }

    /** Overlay text for the upcoming scimitar, e.g. "Steel scimitar @ 11 Atk". */
    private String nextUpgradeLabel() {
        if (!config.upgradeScimitars()) return "Upgrades off";
        int dueAt = config.lastUpgradeAttackLevel() + config.levelsPerUpgrade();
        Scimitar next = nextScimitar();
        if (next != null) return next.itemName + " @ " + dueAt + " Atk";
        // Nothing wieldable beats our weapon yet; name the next tier Zeke has, if any.
        String weapon = equippedWeaponName();
        for (Scimitar s : Scimitar.values()) {
            if (s.attackLevel > attackLevel() && !s.itemName.equalsIgnoreCase(weapon)) {
                return s.itemName + " @ " + Math.max(dueAt, s.attackLevel) + " Atk";
            }
        }
        return "Best scimitar owned";
    }

    private int totalLevel() {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> Microbot.getClient().getTotalLevel()).orElse(0);
    }

    private String equippedWeaponName() {
        Rs2ItemModel weapon = Rs2Equipment.get(EquipmentInventorySlot.WEAPON);
        return weapon != null ? weapon.getName() : null;
    }

    /** Best scimitar we can wield that beats the one equipped, or null if Zeke has nothing better. */
    private Scimitar nextScimitar() {
        String weapon = equippedWeaponName();
        int ownedTier = -1;
        for (Scimitar s : Scimitar.values()) {
            if (s.itemName.equalsIgnoreCase(weapon)) {
                ownedTier = s.ordinal();
            }
        }
        int level = attackLevel();
        Scimitar[] tiers = Scimitar.values();
        for (int i = tiers.length - 1; i > ownedTier; i--) {
            if (tiers[i].attackLevel <= level) {
                return tiers[i];
            }
        }
        return null;
    }

    private boolean upgradeDue() {
        if (!config.upgradeScimitars()) {
            return false;
        }
        return attackLevel() >= config.lastUpgradeAttackLevel() + config.levelsPerUpgrade() && nextScimitar() != null;
    }

    private boolean canAffordUpgrade() {
        Scimitar target = nextScimitar();
        return target != null && Rs2Inventory.itemQuantity(COINS, true) >= target.price + TOLL_FEE;
    }

    private void saveLastUpgradeLevel(int level) {
        configManager.setConfiguration(AccountStarterConfig.GROUP, LAST_UPGRADE_LEVEL_KEY, level);
    }

    // --- Watchdog ---

    /**
     * Keeps the state machine moving when a state stops making progress (no xp, no movement, no inventory change,
     * and no deliberate wait). FIGHT re-targets at 15s; WALK_TO_PEN resets the walker at 20s; any state with no
     * progress for 90s trips StuckGuard, which logs out and stops the plugin.
     *
     * @return true if this tick was spent on a recovery action
     */
    private boolean runWatchdog() {
        State state = getCurrentState();
        long now = System.currentTimeMillis();
        if (state == null) return false;

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
        if (idle >= ANY_STATE_STUCK_MS && recoveryLevel < 2) {
            recoveryLevel = 2;
            stats.addRecovery();
            StuckGuard.trip("no progress in " + state + " for " + idle / 1000 + "s");
            return true;
        }
        if (state == State.FIGHT && idle >= FIGHT_RETARGET_MS && recoveryLevel < 1) {
            recoveryLevel = 1;
            log.warn("[AccountStarter] Watchdog: no combat for {}s, re-targeting", idle / 1000);
            Microbot.status = "Watchdog: re-targeting";
            stats.addRecovery();
            Rs2AntibanSettings.actionCooldownActive = false;
            fight();
            return true;
        }
        if (state == State.WALK_TO_PEN && idle >= WALK_RESET_MS && recoveryLevel < 1) {
            recoveryLevel = 1;
            log.warn("[AccountStarter] Watchdog: walker made no progress for {}s, resetting it", idle / 1000);
            Microbot.status = "Watchdog: resetting the walker";
            stats.addRecovery();
            Rs2Walker.setTarget(null, "Account starter watchdog reset");
            return true;
        }
        return false;
    }

    /** What counts as progress: xp in the tracked skills, position and inventory contents. */
    private String progressSignature() {
        long xp = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            long total = 0;
            for (Skill skill : AccountStarterStats.TRACKED_SKILLS) {
                total += Microbot.getClient().getSkillExperience(skill);
            }
            return total;
        }).orElse(0L);
        int inventory = Rs2Inventory.items().mapToInt(Rs2ItemModel::getQuantity).sum();
        return xp + "|" + Rs2Player.getWorldLocation() + "|" + inventory;
    }

    private void markIntentionalWait() {
        lastIntentionalWaitAt = System.currentTimeMillis();
    }

    // --- Lifecycle ---

    public boolean run(AccountStarterConfig config) {
        this.config = config;
        fundsShort = false;
        watchedState = null;
        recoveryLevel = 0;
        progressSignature = "";
        stats.reset();

        if (config.antiban()) {
            Rs2Antiban.resetAntibanSettings();
            Rs2Antiban.antibanSetupTemplates.applyCombatSetup();
            MouseIdleHabits.enable();
        }

        StuckGuard.attach("Shadowfax Account Starter", AccountStarterPlugin.class);

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (StuckGuard.isTripped()) return;
                if (!Microbot.isLoggedIn()) return;
                stats.captureBaselineIfNeeded();
                if (runWatchdog()) return;
                // First run: count the 10 levels from wherever the character is now.
                if (config.lastUpgradeAttackLevel() == 0) {
                    saveLastUpgradeLevel(attackLevel());
                }
                stats.setNextUpgrade(nextUpgradeLabel());
                // Still eat through a cooldown; everything else waits it out.
                if (Rs2AntibanSettings.actionCooldownActive && !needsToEat()) return;
                // Only on the move: the walker's own top-up waits for long paths, and fighting doesn't drain run.
                if (config.useStaminas() && getCurrentState() != State.FIGHT
                        && StaminaPotions.drinkIfNeeded(config.staminaAtEnergy())) {
                    return;
                }
                step();
            } catch (Throwable ex) {
                // Throwable, not Exception: an uncaught Error (e.g. a client-thread AssertionError) would otherwise
                // cancel the scheduled task without any log line.
                log.error("[AccountStarter] Unexpected error in scheduled loop", ex);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);

        return true;
    }

    @Override
    public void shutdown() {
        StuckGuard.detach(AccountStarterPlugin.class);
        super.shutdown();
        Rs2Antiban.resetAntibanSettings();
    }
}
