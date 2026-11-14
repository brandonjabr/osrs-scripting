package net.runelite.client.plugins.microbot.shadowfaxmagictrainer;

import net.runelite.client.plugins.microbot.util.safety.StuckGuard;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.game.ItemStats;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.statemachine.StateMachineScript;
import net.runelite.client.plugins.microbot.statemachine.Transition;
import net.runelite.client.plugins.microbot.util.antiban.MouseIdleHabits;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeAction;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeRequest;
import net.runelite.client.plugins.microbot.util.grandexchange.Rs2GrandExchange;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.magic.Rs2Spellbook;
import net.runelite.client.plugins.microbot.util.magic.Runes;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.walker.WalkerState;
import net.runelite.client.plugins.microbot.util.shopping.ItemPrices;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Splashes the caged Monk of Zamorak under the stairs in Varrock Palace with the best curse spell the
 * player's Magic level allows (Wind Strike → Confuse → Weaken → Curse). Runes come from the Grand Exchange
 * bank first, then GE buy offers within a budget.
 * <p>
 * RESTOCK → WALK_TO_MONK → SPLASH, back to RESTOCK whenever the best spell runs out of runes (including right
 * after unlocking a new one). FINISH logs out and stops the plugin at the target level, or when nothing is
 * castable and restocking failed.
 */
@Slf4j
@Singleton
public class MagicTrainerScript extends StateMachineScript<MagicTrainerScript.State> {

    enum State {
        RESTOCK,
        WALK_TO_MONK,
        SPLASH,
        FINISH
    }

    private static final String MONK = "Monk of Zamorak";
    // Cage under the stairs on the ground floor of Varrock Palace.
    private static final WorldPoint MONK_SPOT = new WorldPoint(3213, 3476, 0);
    // Once the monk is loaded within this range, stop walking: his tile is inside the cage, so the walker can
    // never arrive. Casting on him makes the game path us into line of sight instead.
    private static final int MONK_SIGHT_RANGE = 12;
    // Near top-down, so the palace staircase doesn't hide the cage.
    private static final float SPLASH_PITCH = 0.9f;
    // Re-aim the camera (and nudge closer) after this many casts in a row that gave no xp.
    private static final int REAIM_AFTER_FAILED_CASTS = 2;

    // A total Magic attack bonus at or below this never hits, so every cast splashes.
    static final int SPLASH_MAX_MAGIC_BONUS = -64;
    private static final int MAX_FAILED_CASTS = 5;

    private static final String COINS = "Coins";
    // Accounts below this total level are restricted from the Grand Exchange.
    private static final int GE_MIN_TOTAL_LEVEL = 100;
    private static final long GE_OFFER_TIMEOUT_MS = 45_000;
    private static final int GE_MAX_BUY_OFFERS = 8;
    // After a failed restock, keep using whatever is castable instead of retrying the GE for this long.
    private static final long RESTOCK_RETRY_MS = 10 * 60_000;

    // Longest the antiban action cooldown may idle the script, picked at random per pause.
    private static final long MIN_COOLDOWN_MS = 2_000;
    private static final long MAX_COOLDOWN_MS = 7_000;
    private long cooldownDeadline;

    @Inject
    private MagicTrainerStats stats;

    private MagicTrainerConfig config;

    // Per-visit RESTOCK state, reset on every transition.
    private boolean restockDone;
    private boolean bankVisited;
    private int buyOffers;
    private long offerPlacedAt;
    private long budgetLeft;
    private int coinsAtStart;

    private long restockBlockedUntil;
    private int failedCasts;
    private boolean cameraReady;
    private boolean finishing;

    @Override
    protected State initialState() {
        return nearMonk() ? State.SPLASH : State.WALK_TO_MONK;
    }

    @Override
    protected List<Transition<State>> defineTransitions() {
        return List.of(
                Transition.<State>from(State.WALK_TO_MONK)
                        .when(this::finished, "finished()")
                        .because("Target level reached or out of runes")
                        .goTo(State.FINISH),
                Transition.<State>from(State.WALK_TO_MONK)
                        .when(this::needsRestock, "needsRestock()")
                        .because("Out of runes for the best spell")
                        .goTo(State.RESTOCK),
                Transition.<State>from(State.WALK_TO_MONK)
                        .when(this::nearMonk, "nearMonk()")
                        .because("Arrived at the caged monk")
                        .goTo(State.SPLASH),

                Transition.<State>from(State.SPLASH)
                        .when(this::finished, "finished()")
                        .because("Target level reached or out of runes")
                        .goTo(State.FINISH),
                Transition.<State>from(State.SPLASH)
                        .when(this::needsRestock, "needsRestock()")
                        .because("Out of runes for the best spell")
                        .goTo(State.RESTOCK),
                Transition.<State>from(State.SPLASH)
                        .when(() -> !nearMonk(), "!nearMonk()")
                        .because("Moved away from the monk")
                        .goTo(State.WALK_TO_MONK),

                Transition.<State>from(State.RESTOCK)
                        .when(() -> restockDone, "restockDone")
                        .because("Rune restock finished")
                        .goTo(State.WALK_TO_MONK)
        );
    }

    @Override
    protected void onTransition(State from, State to, String reason) {
        super.onTransition(from, to, reason);
        restockDone = false;
        bankVisited = false;
        buyOffers = 0;
        offerPlacedAt = 0;
        budgetLeft = config.runeBudget();
        coinsAtStart = 0;
        failedCasts = 0;
        cameraReady = false;
    }

    @Override
    protected void onState(State state) {
        switch (state) {
            case RESTOCK:
                restock();
                break;
            case WALK_TO_MONK:
                walkToMonk();
                break;
            case SPLASH:
                splash();
                break;
            case FINISH:
                finish();
                break;
        }
    }

    // --- States ---

    private void walkToMonk() {
        if (closeInterfaces()) return;
        Microbot.status = "Walking to Varrock Palace";
        if (Rs2Walker.walkWithState(MONK_SPOT, 5) == WalkerState.UNREACHABLE) {
            // The cage tile itself can't be reached; click towards it and let the game path to the bars.
            WorldPoint here = Rs2Player.getWorldLocation();
            if (here != null && here.getPlane() == MONK_SPOT.getPlane() && here.distanceTo(MONK_SPOT) <= 20) {
                Rs2Walker.walkFastCanvas(MONK_SPOT);
                sleepUntil(() -> !Rs2Player.isMoving(), 3000);
            }
        }
    }

    private void splash() {
        if (closeInterfaces()) return;

        if (!Rs2Magic.isSpellbook(Rs2Spellbook.MODERN)) {
            Microbot.status = "Switch to the standard spellbook";
            return;
        }

        SplashSpell spell = spellToCast();
        if (spell == null) {
            return;
        }

        Rs2NpcModel monk = findMonk();
        if (monk == null) {
            Microbot.status = "Looking for the caged monk";
            return;
        }

        if (failedCasts >= REAIM_AFTER_FAILED_CASTS && failedCasts % REAIM_AFTER_FAILED_CASTS == 0) {
            cameraReady = false;
        }
        if (!cameraReady || !isOnScreen(monk)) {
            Microbot.status = "Turning the camera to the monk";
            Rs2Camera.turnTo(monk);
            Rs2Camera.adjustPitch(SPLASH_PITCH);
            cameraReady = true;
            if (failedCasts >= REAIM_AFTER_FAILED_CASTS) {
                // Still nothing landing: step towards the cage so the click has a clear line of sight.
                Rs2Walker.walkFastCanvas(MONK_SPOT);
                sleepUntil(() -> !Rs2Player.isMoving(), 3000);
            }
            return;
        }

        if (failedCasts >= MAX_FAILED_CASTS) {
            // The Defender of Varrock version of this monk can't be attacked.
            Microbot.status = "Can't cast on the monk; is he attackable on this account?";
        } else {
            Microbot.status = "Casting " + spell.displayName;
        }

        int xpBefore = magicXp();
        boolean cast = Rs2Magic.castOn(spell.magicAction,
                new net.runelite.client.plugins.microbot.util.npc.Rs2NpcModel(monk.getNpc()));
        if (cast && sleepUntil(() -> magicXp() > xpBefore, 4000)) {
            failedCasts = 0;
            stats.addCast();
            antibanAfterAction();
            // Combat spells fire every 5 ticks; give the cast time to finish before queuing the next.
            sleep(1200, 1800);
        } else {
            failedCasts++;
        }
    }

    private void restock() {
        if (Rs2GrandExchange.isOpen() && !Rs2GrandExchange.isAllSlotsEmpty()) {
            handlePendingOffers();
            return;
        }

        SplashSpell spell = bestSpell();

        if (!bankVisited) {
            if (Rs2GrandExchange.isOpen()) {
                Rs2GrandExchange.closeExchange();
                return;
            }
            if (!Rs2Bank.isOpen()) {
                Microbot.status = "Walking to the Grand Exchange bank";
                Rs2Bank.walkToBankAndUseBank(BankLocation.GRAND_EXCHANGE);
                return;
            }
            Microbot.status = "Withdrawing runes";
            for (Runes rune : spell.runes.keySet()) {
                int id = rune.getItemId();
                if (Rs2Bank.hasItem(id)) {
                    Rs2Bank.withdrawAll(id);
                    sleepUntil(() -> !Rs2Bank.hasItem(id), 3000);
                }
            }
            if (Rs2Bank.hasItem(COINS, true)) {
                Rs2Bank.withdrawAll(COINS, true);
                sleepUntil(() -> !Rs2Bank.hasItem(COINS, true), 3000);
            }
            coinsAtStart = Rs2Inventory.itemQuantity(COINS, true);
            bankVisited = true;
            Rs2Bank.closeBank();
            return;
        }

        Map<Runes, Integer> missing = missingRunes(spell, config.castsPerTrip());
        if (missing.isEmpty()) {
            finishRestock();
            return;
        }
        if (!config.buyRunes() || buyOffers >= GE_MAX_BUY_OFFERS) {
            finishRestock();
            return;
        }
        int totalLevel = totalLevel();
        if (totalLevel < GE_MIN_TOTAL_LEVEL) {
            log.info("[MagicTrainer] Total level {} is below {}; the Grand Exchange is restricted", totalLevel, GE_MIN_TOTAL_LEVEL);
            finishRestock();
            return;
        }

        if (!Rs2GrandExchange.isOpen()) {
            Microbot.status = "Opening the Grand Exchange";
            if (!Rs2GrandExchange.openExchange()) {
                Rs2GrandExchange.walkToGrandExchange();
            }
            return;
        }

        Map.Entry<Runes, Integer> next = missing.entrySet().iterator().next();
        Runes rune = next.getKey();
        String name = SplashSpell.runeName(rune);
        int percent = Math.min(50, 10 + 10 * buyOffers);
        long price = ItemPrices.guidePrice(rune.getItemId());
        long each = Math.max(1, price * (100 + percent) / 100 + 1);
        long budget = Math.min(budgetLeft, Rs2Inventory.itemQuantity(COINS, true));
        int quantity = (int) Math.min(next.getValue(), budget / each);
        if (quantity < 1) {
            log.info("[MagicTrainer] Can't afford any more {} (budget left {})", name, budgetLeft);
            finishRestock();
            return;
        }

        Microbot.status = "Buying " + quantity + " " + name + "s (+" + percent + "%)";
        GrandExchangeRequest request = GrandExchangeRequest.builder()
                .action(GrandExchangeAction.BUY)
                .itemName(name)
                .exact(true)
                .quantity(quantity)
                .percent(percent)
                .build();
        if (Rs2GrandExchange.processOffer(request)) {
            offerPlacedAt = System.currentTimeMillis();
            budgetLeft -= quantity * each;
        }
        buyOffers++;
    }

    private void handlePendingOffers() {
        if (Rs2GrandExchange.hasFinishedBuyingOffers()) {
            Microbot.status = "Collecting runes";
            Rs2GrandExchange.collectAllToInventory();
            sleepUntil(Rs2GrandExchange::isAllSlotsEmpty, 3000);
        } else if (offerPlacedAt == 0 || System.currentTimeMillis() - offerPlacedAt > GE_OFFER_TIMEOUT_MS) {
            // Unfilled (or left over from before the script started): pull it back and rebuy higher.
            Microbot.status = "Aborting unfilled offer";
            Rs2GrandExchange.abortAllOffers(false);
            sleepUntil(Rs2GrandExchange::isAllSlotsEmpty, 5000);
        } else {
            Microbot.status = "Waiting for runes to buy";
        }
    }

    private void finishRestock() {
        closeInterfaces();
        if (bankVisited) {
            stats.addGpSpent(coinsAtStart - Rs2Inventory.itemQuantity(COINS, true));
        }
        if (!hasRunes(bestSpell(), 1)) {
            log.info("[MagicTrainer] Couldn't get runes for {}; using what's castable for now", bestSpell().displayName);
            restockBlockedUntil = System.currentTimeMillis() + RESTOCK_RETRY_MS;
        }
        restockDone = true;
    }

    private void finish() {
        if (finishing) return;
        finishing = true;
        int level = magicLevel();
        if (config.stopAtLevel() > 0 && level >= config.stopAtLevel()) {
            log.info("[MagicTrainer] Reached Magic level {}", level);
        } else {
            log.info("[MagicTrainer] No runes to cast any spell and restocking failed");
        }
        closeInterfaces();
        if (config.logoutWhenDone()) {
            Rs2Player.logoutWithoutAutoLogin();
        }
        Microbot.stopPlugin(MagicTrainerPlugin.class);
    }

    // --- Spells & runes ---

    /** Best spell our Magic level allows, capped at the configured highest spell. */
    private SplashSpell bestSpell() {
        int level = magicLevel();
        SplashSpell best = SplashSpell.WIND_STRIKE;
        for (SplashSpell spell : SplashSpell.values()) {
            if (spell.ordinal() <= config.maxSpell().ordinal() && spell.level <= level) {
                best = spell;
            }
        }
        return best;
    }

    /** The best spell we have runes for, falling back to weaker ones; null if nothing is castable. */
    private SplashSpell spellToCast() {
        SplashSpell[] spells = SplashSpell.values();
        for (int i = bestSpell().ordinal(); i >= 0; i--) {
            if (hasRunes(spells[i], 1)) return spells[i];
        }
        return null;
    }

    private Map<Runes, Integer> missingRunes(SplashSpell spell, int casts) {
        Map<Runes, Integer> have = Rs2Magic.getRunes();
        Map<Runes, Integer> missing = new LinkedHashMap<>();
        spell.runes.forEach((rune, perCast) -> {
            long need = (long) perCast * casts - have.getOrDefault(rune, 0);
            if (need > 0) missing.put(rune, (int) Math.min(Integer.MAX_VALUE, need));
        });
        return missing;
    }

    private boolean hasRunes(SplashSpell spell, int casts) {
        return missingRunes(spell, casts).isEmpty();
    }

    /** Casts left for the spell, limited by its scarcest rune; MAX_VALUE if staves cover every rune. */
    private int castsLeft(SplashSpell spell, Map<Runes, Integer> have) {
        int casts = Integer.MAX_VALUE;
        for (Map.Entry<Runes, Integer> e : spell.runes.entrySet()) {
            casts = Math.min(casts, have.getOrDefault(e.getKey(), 0) / e.getValue());
        }
        return casts;
    }

    // --- Guards & helpers ---

    private boolean needsRestock() {
        return !hasRunes(bestSpell(), 1) && System.currentTimeMillis() >= restockBlockedUntil;
    }

    private boolean finished() {
        if (config.stopAtLevel() > 0 && magicLevel() >= config.stopAtLevel()) return true;
        return spellToCast() == null && System.currentTimeMillis() < restockBlockedUntil;
    }

    /** Close enough to splash: on the palace ground floor with the caged monk loaded nearby. */
    private boolean nearMonk() {
        WorldPoint here = Rs2Player.getWorldLocation();
        if (here == null || here.getPlane() != MONK_SPOT.getPlane() || here.distanceTo(MONK_SPOT) > MONK_SIGHT_RANGE) {
            return false;
        }
        return findMonk() != null;
    }

    private Rs2NpcModel findMonk() {
        return Microbot.getRs2NpcCache().query()
                .withName(MONK)
                .where(npc -> npc.getWorldLocation() != null && npc.getWorldLocation().distanceTo(MONK_SPOT) <= 3)
                .nearestOnClientThread();
    }

    /** Whether any part of the monk's model is drawn inside the game view. */
    private boolean isOnScreen(Rs2NpcModel npc) {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Shape hull = npc.getConvexHull();
            if (hull == null) return false;
            Rectangle view = new Rectangle(0, 0, Microbot.getClient().getCanvasWidth(), Microbot.getClient().getCanvasHeight());
            return hull.getBounds().intersects(view);
        }).orElse(false);
    }

    /** Closes the bank or GE if open; true if something was closed this tick. */
    private boolean closeInterfaces() {
        if (Rs2Bank.isOpen()) {
            Rs2Bank.closeBank();
            return true;
        }
        if (Rs2GrandExchange.isOpen()) {
            Rs2GrandExchange.closeExchange();
            return true;
        }
        return false;
    }

    private int magicLevel() {
        return Rs2Player.getRealSkillLevel(Skill.MAGIC);
    }

    private int magicXp() {
        return Microbot.getClientThread()
                .runOnClientThreadOptional(() -> Microbot.getClient().getSkillExperience(Skill.MAGIC))
                .orElse(0);
    }

    private int totalLevel() {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> Microbot.getClient().getTotalLevel()).orElse(0);
    }

    /** Sum of the Magic attack bonus of everything worn. */
    private int magicAttackBonus() {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            int total = 0;
            for (Rs2ItemModel item : Rs2Equipment.items()) {
                if (item == null) continue;
                ItemStats itemStats = Microbot.getItemManager().getItemStats(item.getId());
                if (itemStats != null && itemStats.getEquipment() != null) {
                    total += itemStats.getEquipment().getAmagic();
                }
            }
            return total;
        }).orElse(0);
    }

    /** Publishes the current spell, rune counts and gear check for the overlay. */
    private void updateStats() {
        SplashSpell spell = spellToCast();
        SplashSpell shown = spell != null ? spell : bestSpell();
        Map<Runes, Integer> have = Rs2Magic.getRunes();
        Map<String, Integer> runes = new LinkedHashMap<>();
        shown.runes.keySet().forEach(rune -> runes.put(SplashSpell.runeName(rune), have.getOrDefault(rune, 0)));
        stats.update(shown, runes, castsLeft(shown, have), magicAttackBonus());
    }

    private void antibanAfterAction() {
        if (!config.antiban()) return;
        Rs2Antiban.actionCooldown();
        Rs2Antiban.takeMicroBreakByChance();
    }

    // --- Lifecycle ---

    public boolean run(MagicTrainerConfig config) {
        this.config = config;
        restockBlockedUntil = 0;
        finishing = false;
        stats.reset();

        if (config.antiban()) {
            Rs2Antiban.resetAntibanSettings();
            Rs2Antiban.antibanSetupTemplates.applyGeneralBasicSetup();
            MouseIdleHabits.enable();
        }

        StuckGuard.attach("Shadowfax Magic Trainer", MagicTrainerPlugin.class);

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (StuckGuard.isTripped()) return;
                if (!Microbot.isLoggedIn()) return;
                stats.captureBaselineIfNeeded();
                updateStats();
                if (Rs2AntibanSettings.actionCooldownActive) {
                    // The antiban cooldown only counts down while out of combat, which barely happens here, so it
                    // could hold the script for good. Wait it out briefly when idle; in a fight, or once it has run
                    // a few seconds, cancel it and keep acting.
                    long now = System.currentTimeMillis();
                    if (cooldownDeadline == 0) {
                        cooldownDeadline = now + ThreadLocalRandom.current().nextLong(MIN_COOLDOWN_MS, MAX_COOLDOWN_MS);
                    }
                    if (!Rs2Combat.inCombat() && now < cooldownDeadline) {
                        Microbot.status = "Antiban pause";
                        return;
                    }
                    Rs2AntibanSettings.actionCooldownActive = false;
                }
                cooldownDeadline = 0;
                step();
            } catch (Throwable ex) {
                // Throwable, not Exception: an uncaught Error (e.g. a client-thread AssertionError) would otherwise
                // cancel the scheduled task without any log line.
                log.error("[MagicTrainer] Unexpected error in scheduled loop", ex);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);

        return true;
    }

    @Override
    public void shutdown() {
        StuckGuard.detach(MagicTrainerPlugin.class);
        super.shutdown();
        Rs2Antiban.resetAntibanSettings();
    }
}
