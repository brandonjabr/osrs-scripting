package net.runelite.client.plugins.microbot.shadowfaxgemcrab;

import net.runelite.client.plugins.microbot.util.safety.StuckGuard;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.statemachine.StateMachineScript;
import net.runelite.client.plugins.microbot.statemachine.Transition;
import net.runelite.client.plugins.microbot.util.antiban.MouseIdleHabits;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.combat.MeleeStyles;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.combat.WeaponStyles;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.magic.Rs2CombatSpells;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.tabs.Rs2Tab;
import net.runelite.client.plugins.microbot.globval.enums.InterfaceTab;
import net.runelite.client.plugins.microbot.util.player.BoostPotions;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.player.StaminaPotions;
import net.runelite.client.plugins.microbot.util.shopping.ItemPrices;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.walker.WalkerState;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Fights the Gemstone Crab in the Tlati Rainforest (Varlamore) and follows it as it moves between its three mines.
 * <p>
 * The crab can't be killed: its health bar is a ten-minute timer, and when it runs out the crab burrows, leaves a
 * shell (minable for 90 seconds by the top 16 damage dealers) and resurfaces at another mine. The cave at every
 * mine leads to wherever the crab is now.
 * <p>
 * TRAVEL → FIGHT → (MINE) → TUNNEL → FIGHT … A burrow is noticed three ways, whichever comes first: the game's
 * "burrows away" message, the shell appearing next to us, or the crab being gone with a shell in its place. After a
 * crawl the script waits for the crab to surface and crawls again if it doesn't. Out of food at low health, or a
 * full inventory of gems, goes to BANK at Tal Teklan and back via TRAVEL.
 * <p>
 * Melee, ranged or magic (picked in the config, or read from the weapon): melee balances attack styles and drinks
 * super strength/attack; ranged keeps the chosen ranged style and drinks a ranging potion; magic can set the autocast
 * spell and drinks a magic potion. Running out of ammo or runes stops the script.
 */
@Slf4j
@Singleton
public class GemCrabScript extends StateMachineScript<GemCrabScript.State> {

    enum State {
        TRAVEL,
        FIGHT,
        MINE,
        TUNNEL,
        BANK
    }

    /** The three mines the crab moves between: where it sits and the cave next to it. */
    enum Site {
        NORTH("North mine", new WorldPoint(1273, 3173, 0), new WorldPoint(1278, 3168, 0)),
        EAST("East mine", new WorldPoint(1353, 3112, 0), new WorldPoint(1351, 3124, 0)),
        SOUTH("South mine", new WorldPoint(1239, 3043, 0), new WorldPoint(1246, 3036, 0));

        final String label;
        final WorldPoint crab;
        final WorldPoint cave;

        Site(String label, WorldPoint crab, WorldPoint cave) {
            this.label = label;
            this.crab = crab;
            this.cave = cave;
        }
    }

    static final int CRAB_ID = NpcID.GEMSTONE_CRAB;
    static final int SHELL_ID = NpcID.GEMSTONE_CRAB_REMAINS;
    private static final String CRAB_NAME = "Gemstone Crab";
    private static final int CAVE_ID = 57631;
    private static final String CAVE_NAME = "Cave";
    private static final String CAVE_ACTION = "Crawl-through";

    private static final String BURROW_MESSAGE = "burrows away, leaving a piece of its shell behind";
    private static final String MINE_REFUSED_MESSAGE = "not great enough to mine its shell";
    private static final String GEM_MESSAGE_PREFIX = "you mine an uncut ";
    // Out of ammo or runes: nothing to attack with, so the script stops.
    private static final String NO_AMMO_MESSAGE = "no ammo left";
    private static final String WRONG_AMMO_MESSAGE = "can't use that ammo";
    private static final String NO_RUNES_MESSAGE = "to cast this spell";

    /** Uncut gems by the name used in the "You mine an uncut ..." message. */
    private static final Map<String, Integer> GEMS = Map.of(
            "opal", ItemID.UNCUT_OPAL,
            "jade", ItemID.UNCUT_JADE,
            "red topaz", ItemID.UNCUT_RED_TOPAZ,
            "sapphire", ItemID.UNCUT_SAPPHIRE,
            "emerald", ItemID.UNCUT_EMERALD,
            "ruby", ItemID.UNCUT_RUBY,
            "diamond", ItemID.UNCUT_DIAMOND,
            "dragonstone", ItemID.UNCUT_DRAGONSTONE
    );

    // A crab (or shell) this close is "here"; the mines are 70+ tiles apart.
    private static final int SITE_RADIUS = 15;
    // A crawl moves us to another mine, at least this far.
    private static final int CRAWL_MIN_JUMP = 30;
    private static final int CRAWL_TIMEOUT_MS = 12_000;
    private static final int CRAWL_MAX_FAILURES = 3;
    // After crawling, how long to wait for the crab to surface before crawling again.
    private static final long SPAWN_WAIT_MS = 40_000;
    // A live crab this long after a burrow is a real one, even if we never saw it spawn (the "same spot" bug).
    private static final long BURROW_SETTLE_MS = 8_000;
    // Two burrow signals this close together are the same burrow.
    private static final long BURROW_DEDUPE_MS = 20_000;
    // No crab and no shell for this long while fighting: it's gone somewhere, follow it.
    private static final long CRAB_LOST_MS = 6_000;
    // The shell sinks after 90 seconds; never spend longer than this mining it.
    private static final long MINE_MAX_MS = 30_000;
    private static final int MINE_MAX_CLICKS = 3;
    private static final int GEMS_PER_SHELL = 3;
    private static final long STYLE_CHECK_MS = 20_000;
    private static final long STYLE_FAIL_BACKOFF_MS = 5 * 60_000;
    // Out of food: rest at the bank until health is back to this.
    private static final int REST_UNTIL_PERCENT = 60;

    // Antiban cooldown cap, as in the other Shadowfax combat scripts.
    private static final long MIN_COOLDOWN_MS = 2_000;
    private static final long MAX_COOLDOWN_MS = 7_000;
    private long cooldownDeadline;

    // Watchdog: any state making no progress this long restarts from TRAVEL.
    private static final long STALL_RESTART_MS = 120_000;
    private static final long INTENTIONAL_WAIT_GRACE_MS = 2_000;
    private State watchedState;
    private long lastProgressAt;
    private long lastIntentionalWaitAt;
    private String progressSignature = "";

    @Inject
    private GemCrabStats stats;

    private GemCrabConfig config;

    // Burrow tracking, written from the client thread by the plugin's events.
    private volatile long burrowedAt;
    private volatile boolean burrowPending;
    private volatile long crabSpawnedAt;
    private volatile boolean mineRefused;
    private volatile int gemsThisShell;

    // Shown on the overlay.
    volatile double crabHealth = -1;
    volatile Site currentSite;
    volatile Skill trainedSkill;
    volatile WeaponStyles.Kind combatKind = WeaponStyles.Kind.MELEE;
    volatile String styleLabel = "";

    // Set from the client thread when the game says we're out of ammo or runes.
    private volatile String outOfSupplies;

    // Per-state progress, reset on every transition.
    private boolean travelArrived;
    private long travelArrivedSince;
    private long crabMissingSince;
    private boolean crabLost;
    private boolean mineDone;
    private long mineStartedAt;
    private long lastMiningAt;
    private int mineClicks;
    private long arrivedAt;
    private int crawlFailures;
    private boolean bankDone;
    private boolean foodWithdrawTried;

    private long nextStyleCheckAt;
    // Boost level at which each potion is drunk next; re-rolled after every dose so it's never the same.
    private final Map<Skill, Integer> redrinkAt = new EnumMap<>(Skill.class);
    // Earliest time to try the heart again: 7 minutes after a use, 30s after a failed one (backs up the cooldown varbit).
    private long heartRetryAt;
    private boolean heartMissingLogged;

    @Override
    protected State initialState() {
        return crabReady() ? State.FIGHT : State.TRAVEL;
    }

    @Override
    protected List<Transition<State>> defineTransitions() {
        return List.of(
                Transition.<State>from(State.TRAVEL)
                        .when(this::needsBank, "needsBank()")
                        .because("Out of food at low health, or the inventory is full of gems")
                        .goTo(State.BANK),
                Transition.<State>from(State.TRAVEL)
                        .when(this::crabReady, "crabReady()")
                        .because("The crab is here")
                        .goTo(State.FIGHT),
                Transition.<State>from(State.TRAVEL)
                        .when(() -> travelArrived, "travelArrived")
                        .because("At a mine without the crab; the cave leads to it")
                        .goTo(State.TUNNEL),

                Transition.<State>from(State.FIGHT)
                        .when(this::needsBank, "needsBank()")
                        .because("Out of food at low health, or the inventory is full of gems")
                        .goTo(State.BANK),
                Transition.<State>from(State.FIGHT)
                        .when(() -> burrowPending && wantsToMine(), "burrowPending && wantsToMine()")
                        .because("The crab burrowed; mining its shell first")
                        .goTo(State.MINE),
                Transition.<State>from(State.FIGHT)
                        .when(() -> burrowPending || crabLost, "burrowPending || crabLost")
                        .because("The crab burrowed; following it through the cave")
                        .goTo(State.TUNNEL),
                Transition.<State>from(State.FIGHT)
                        .when(() -> !nearAnySite(), "!nearAnySite()")
                        .because("Away from the mines (died or teleported)")
                        .goTo(State.TRAVEL),

                Transition.<State>from(State.MINE)
                        .when(this::needsBank, "needsBank()")
                        .because("Inventory full of gems")
                        .goTo(State.BANK),
                Transition.<State>from(State.MINE)
                        .when(() -> mineDone, "mineDone")
                        .because("Done with the shell; following the crab")
                        .goTo(State.TUNNEL),

                Transition.<State>from(State.TUNNEL)
                        .when(this::needsBank, "needsBank()")
                        .because("Out of food at low health, or the inventory is full of gems")
                        .goTo(State.BANK),
                Transition.<State>from(State.TUNNEL)
                        .when(this::crabReady, "crabReady()")
                        .because("The crab surfaced next to us")
                        .goTo(State.FIGHT),
                Transition.<State>from(State.TUNNEL)
                        .when(() -> !nearAnySite(), "!nearAnySite()")
                        .because("Away from the mines")
                        .goTo(State.TRAVEL),

                Transition.<State>from(State.BANK)
                        .when(() -> bankDone, "bankDone")
                        .because("Banked and restocked")
                        .goTo(State.TRAVEL)
        );
    }

    @Override
    protected void onTransition(State from, State to, String reason) {
        super.onTransition(from, to, reason);
        travelArrived = false;
        travelArrivedSince = 0;
        crabMissingSince = 0;
        crabLost = false;
        mineDone = false;
        mineStartedAt = 0;
        lastMiningAt = 0;
        mineClicks = 0;
        arrivedAt = 0;
        crawlFailures = 0;
        bankDone = false;
        foodWithdrawTried = false;
        if (to == State.MINE) {
            mineRefused = false;
            gemsThisShell = 0;
        }
        if (to == State.TUNNEL || to == State.FIGHT) {
            // This burrow is being dealt with; the next one sets it again.
            burrowPending = false;
        }
        if (to != State.FIGHT) {
            crabHealth = -1;
        }
    }

    @Override
    protected void onState(State state) {
        currentSite = nearestSite(SITE_RADIUS + 10);
        combatKind = resolveCombatKind();
        switch (state) {
            case TRAVEL:
                travel();
                break;
            case FIGHT:
                fight();
                break;
            case MINE:
                mine();
                break;
            case TUNNEL:
                tunnel();
                break;
            case BANK:
                bank();
                break;
        }
    }

    // --- Events (client thread, from the plugin) ---

    void onGameMessage(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains(BURROW_MESSAGE)) {
            onBurrow("burrow message");
        } else if (lower.contains(NO_AMMO_MESSAGE) || lower.contains(WRONG_AMMO_MESSAGE)
                || (lower.contains("enough") && lower.contains(NO_RUNES_MESSAGE))) {
            outOfSupplies = message;
        } else if (lower.contains(MINE_REFUSED_MESSAGE)) {
            mineRefused = true;
        } else if (lower.startsWith(GEM_MESSAGE_PREFIX)) {
            String gem = lower.substring(GEM_MESSAGE_PREFIX.length()).replaceAll("[.!]+$", "").trim();
            Integer id = GEMS.get(gem);
            gemsThisShell++;
            stats.addGem("Uncut " + gem, id == null ? 0 : ItemPrices.guidePrice(id));
        }
    }

    void onCrabSpawned() {
        crabSpawnedAt = System.currentTimeMillis();
    }

    void onShellSpawned(WorldPoint location) {
        Player me = Microbot.getClient().getLocalPlayer();
        if (me != null && location != null && me.getWorldLocation().distanceTo(location) <= SITE_RADIUS) {
            onBurrow("shell appeared");
        }
    }

    private void onBurrow(String signal) {
        long now = System.currentTimeMillis();
        if (now - burrowedAt < BURROW_DEDUPE_MS) return;
        burrowedAt = now;
        burrowPending = true;
        State state = getCurrentState();
        if (state == State.FIGHT) {
            stats.addCrab();
        }
        log.info("[GemCrab] Crab burrowed ({}) while in {}", signal, state);
    }

    // --- States ---

    /** Walks to the nearest mine's cave (the north one from anywhere else, as it's closest to the bank). */
    private void travel() {
        if (Rs2Bank.isOpen()) {
            Rs2Bank.closeBank();
            return;
        }
        Site site = nearestSite(80);
        if (site == null) site = Site.NORTH;
        WorldPoint here = Rs2Player.getWorldLocation();
        if (here != null && here.distanceTo(site.cave) <= 6) {
            // Give the scene a couple of ticks to show the crab before deciding it isn't here.
            long now = System.currentTimeMillis();
            if (travelArrivedSince == 0) travelArrivedSince = now;
            travelArrived = now - travelArrivedSince >= 1_200;
            Microbot.status = "At the " + site.label.toLowerCase(Locale.ROOT) + ", looking for the crab";
            markIntentionalWait();
            return;
        }
        travelArrivedSince = 0;
        Microbot.status = "Walking to the " + site.label.toLowerCase(Locale.ROOT);
        if (Rs2Walker.walkWithState(site.cave, 4) == WalkerState.UNREACHABLE) {
            log.warn("[GemCrab] {} unreachable from {}", site.label, here);
        }
    }

    private void fight() {
        Rs2NpcModel crab = liveCrab();
        if (crab == null) {
            crabHealth = -1;
            long now = System.currentTimeMillis();
            if (crabMissingSince == 0) crabMissingSince = now;
            if (shellNearby()) {
                // Missed the message and the spawn event: the shell alone says it burrowed.
                onBurrow("shell seen");
            } else if (now - crabMissingSince >= CRAB_LOST_MS) {
                log.info("[GemCrab] Crab gone without a shell; following it through the cave");
                crabLost = true;
            }
            Microbot.status = "Crab gone…";
            return;
        }
        crabMissingSince = 0;
        double health = crab.getHealthPercentage();
        if (health >= 0) crabHealth = health;

        if (manageStyle()) return;
        if (drinkBoosts()) return;

        if (isAttackingCrab()) {
            Microbot.status = "Fighting the Gemstone Crab";
            return;
        }
        Microbot.status = "Attacking the Gemstone Crab";
        if (crab.click("Attack") && sleepUntil(this::isAttackingCrab, 3_000)) {
            antibanAfterAction();
        }
    }

    /** Mines the shell until three gems, the game refuses, the shell sinks, or it takes too long. */
    private void mine() {
        Rs2NpcModel shell = Microbot.getRs2NpcCache().query().withId(SHELL_ID).nearestOnClientThread(SITE_RADIUS);
        long now = System.currentTimeMillis();
        if (shell == null || mineRefused || gemsThisShell >= GEMS_PER_SHELL || Rs2Inventory.isFull()
                || (mineStartedAt > 0 && now - mineStartedAt > MINE_MAX_MS)) {
            finishMining();
            return;
        }
        if (Rs2Player.isAnimating()) {
            lastMiningAt = now;
            Microbot.status = "Mining the shell (" + gemsThisShell + "/" + GEMS_PER_SHELL + ")";
            return;
        }
        if (lastMiningAt > 0 && now - lastMiningAt < 2_400) {
            // Between swings.
            return;
        }
        if (mineClicks >= MINE_MAX_CLICKS) {
            finishMining();
            return;
        }
        Microbot.status = "Mining the shell";
        mineClicks++;
        if (mineStartedAt == 0) mineStartedAt = now;
        if (shell.click("Mine") && sleepUntil(Rs2Player::isAnimating, 3_000)) {
            lastMiningAt = System.currentTimeMillis();
        }
    }

    private void finishMining() {
        if (gemsThisShell > 0) stats.addShellMined();
        if (mineRefused) log.info("[GemCrab] Not in the top 16 for this shell; skipping it");
        mineDone = true;
    }

    /**
     * Crawls through the nearest cave to wherever the crab is, then waits for it to surface. A crawl only counts once
     * we've actually moved to another mine; clicks that don't take are retried, then we walk to the cave and retry.
     */
    private void tunnel() {
        long now = System.currentTimeMillis();
        if (arrivedAt > 0) {
            if (now - arrivedAt < SPAWN_WAIT_MS) {
                Microbot.status = "Waiting for the crab to surface (" + (SPAWN_WAIT_MS - (now - arrivedAt)) / 1000 + "s)";
                markIntentionalWait();
                return;
            }
            log.info("[GemCrab] No crab {}s after crawling; crawling again", SPAWN_WAIT_MS / 1000);
            stats.addRecovery();
            arrivedAt = 0;
        }

        Site site = nearestSite(SITE_RADIUS + 15);
        Rs2TileObjectModel cave = findCave();
        if (cave == null || crawlFailures >= CRAWL_MAX_FAILURES) {
            WorldPoint target = (site != null ? site : Site.NORTH).cave;
            Microbot.status = "Walking to the cave";
            Rs2Walker.walkWithState(target, 2);
            crawlFailures = 0;
            return;
        }

        WorldPoint from = Rs2Player.getWorldLocation();
        if (from == null) return;
        Microbot.status = "Crawling through the cave";
        if (cave.click(CAVE_ACTION) && sleepUntil(() -> movedFrom(from, CRAWL_MIN_JUMP), CRAWL_TIMEOUT_MS)) {
            arrivedAt = System.currentTimeMillis();
            stats.addTunnel();
            Site to = nearestSite(SITE_RADIUS + 10);
            log.info("[GemCrab] Crawled to the {}", to != null ? to.label.toLowerCase(Locale.ROOT) : Rs2Player.getWorldLocation());
            return;
        }
        crawlFailures++;
        log.info("[GemCrab] Crawl didn't take (attempt {})", crawlFailures);
    }

    private void bank() {
        if (!Rs2Bank.isOpen()) {
            Microbot.status = "Walking to the Tal Teklan bank";
            Rs2Bank.walkToBankAndUseBank(BankLocation.TAL_TEKLAN);
            return;
        }

        if (Rs2Inventory.items(this::isBankable).findAny().isPresent()) {
            Microbot.status = "Banking gems";
            Rs2Bank.depositAll(this::isBankable);
            if (sleepUntil(() -> Rs2Inventory.items(this::isBankable).findAny().isEmpty(), 4_000)) {
                stats.addBankTrip();
            }
            return;
        }

        if (config.useStaminas() && StaminaPotions.topUp(config.staminaCount())) return;
        for (String potion : enabledPotions()) {
            if (BoostPotions.topUp(potion, config.potionCount())) return;
        }
        if (withdrawHeart()) return;

        int missing = config.foodAmount() - foodCount();
        if (missing > 0 && !foodWithdrawTried) {
            foodWithdrawTried = true;
            Microbot.status = "Withdrawing food";
            int before = foodCount();
            String name = config.foodName().trim();
            boolean withdrew = !name.isEmpty() && Rs2Bank.hasItem(name, true)
                    ? Rs2Bank.withdrawX(name, missing, true)
                    : Rs2Bank.withdrawX(Rs2ItemModel::isFood, missing);
            if (!withdrew || !sleepUntil(() -> foodCount() > before, 3_000)) {
                log.info("[GemCrab] Couldn't withdraw {} from the bank", name.isEmpty() ? "any food" : name);
            }
            return;
        }

        if (!hasFood() && Rs2Player.getHealthPercentage() < REST_UNTIL_PERCENT) {
            Microbot.status = "No food in the bank; resting until " + REST_UNTIL_PERCENT + "% health";
            markIntentionalWait();
            return;
        }

        Rs2Bank.closeBank();
        bankDone = true;
    }

    // --- Combat type, attack styles & potions ---

    /** The configured combat type, or the weapon's when set to match it. */
    private WeaponStyles.Kind resolveCombatKind() {
        switch (config.combatType()) {
            case MELEE:
                return WeaponStyles.Kind.MELEE;
            case RANGED:
                return WeaponStyles.Kind.RANGED;
            case MAGIC:
                return WeaponStyles.Kind.MAGIC;
            default:
                WeaponStyles.Kind kind = WeaponStyles.currentKind();
                // A staff we're told to set autocast on counts as magic before the autocast is set.
                if (kind == WeaponStyles.Kind.MELEE && config.setAutocast() && WeaponStyles.canCast()) {
                    return WeaponStyles.Kind.MAGIC;
                }
                return kind;
        }
    }

    /** Keeps the style right for the combat type in use. True when this tick was spent on it. */
    private boolean manageStyle() {
        switch (combatKind) {
            case RANGED:
                return rangedStyle();
            case MAGIC:
                return magicAutocast();
            default:
                styleLabel = config.styleMode().toString();
                return balanceStyles();
        }
    }

    private boolean rangedStyle() {
        GemCrabConfig.RangedStyleMode mode = config.rangedStyle();
        styleLabel = mode.toString();
        trainedSkill = Skill.RANGED;
        long now = System.currentTimeMillis();
        if (mode == GemCrabConfig.RangedStyleMode.KEEP || now < nextStyleCheckAt) return false;
        nextStyleCheckAt = now + STYLE_CHECK_MS;
        WeaponStyles.RangedStyle wanted = WeaponStyles.RangedStyle.valueOf(mode.name());
        if (WeaponStyles.isSelected(wanted)) return false;

        Microbot.status = "Switching to " + mode;
        if (WeaponStyles.select(wanted)) {
            stats.addStyleSwap();
            log.info("[GemCrab] Ranged style set to {}", mode);
        } else {
            nextStyleCheckAt = now + STYLE_FAIL_BACKOFF_MS;
            log.info("[GemCrab] Couldn't select {} on this weapon; trying again in a few minutes", mode);
        }
        return true;
    }

    private boolean magicAutocast() {
        trainedSkill = Skill.MAGIC;
        Rs2CombatSpells current = Rs2Magic.getCurrentAutoCastSpell();
        if (!config.setAutocast()) {
            styleLabel = current != null ? "Autocast " + spellName(current) : "Magic";
            return false;
        }
        Rs2CombatSpells spell = config.autocastSpell();
        boolean defensive = config.defensiveCasting();
        styleLabel = (defensive ? "Def. " : "") + "Autocast " + spellName(spell);
        long now = System.currentTimeMillis();
        if (spell == null || now < nextStyleCheckAt) return false;
        nextStyleCheckAt = now + STYLE_CHECK_MS;
        boolean defensiveOn = Microbot.getVarbitValue(VarbitID.AUTOCAST_DEFMODE) == 1;
        if (current == spell && defensiveOn == defensive) return false;

        Microbot.status = "Setting autocast to " + spellName(spell);
        if (Rs2Combat.setAutoCastSpell(spell, defensive)) {
            stats.addStyleSwap();
            log.info("[GemCrab] Autocasting {}{}", spellName(spell), defensive ? " (defensive)" : "");
        } else {
            nextStyleCheckAt = now + STYLE_FAIL_BACKOFF_MS;
            log.info("[GemCrab] Couldn't autocast {} (level, runes, spellbook or staff?); trying again in a few minutes",
                    spellName(spell));
        }
        if (Rs2Tab.getCurrentTab() != InterfaceTab.INVENTORY) Rs2Tab.switchTo(InterfaceTab.INVENTORY);
        return true;
    }

    private static String spellName(Rs2CombatSpells spell) {
        String s = spell.name().replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Switches to the melee style for the skill that should be trained now. True when this tick was spent on it. */
    private boolean balanceStyles() {
        long now = System.currentTimeMillis();
        if (config.styleMode() == GemCrabConfig.StyleMode.KEEP || now < nextStyleCheckAt) return false;
        nextStyleCheckAt = now + STYLE_CHECK_MS;

        Skill current = MeleeStyles.currentSkill();
        trainedSkill = current;
        Skill wanted = wantedSkill(current);
        if (wanted == null || wanted == current) return false;

        Microbot.status = "Switching to train " + wanted.getName();
        if (MeleeStyles.select(wanted)) {
            trainedSkill = wanted;
            stats.addStyleSwap();
            log.info("[GemCrab] Training {} now (was {})", wanted.getName(), current == null ? "none" : current.getName());
        } else {
            nextStyleCheckAt = now + STYLE_FAIL_BACKOFF_MS;
            log.info("[GemCrab] This weapon has no melee style for {}; trying again in a few minutes", wanted.getName());
        }
        return true;
    }

    private Skill wantedSkill(Skill current) {
        switch (config.styleMode()) {
            case ATTACK:
                return Skill.ATTACK;
            case STRENGTH:
                return Skill.STRENGTH;
            case DEFENCE:
                return Skill.DEFENCE;
            case BALANCED:
                // Strength first on ties: it raises damage, and so xp, the most.
                List<Skill> skills = new ArrayList<>(3);
                if (config.trainStrength()) skills.add(Skill.STRENGTH);
                if (config.trainAttack()) skills.add(Skill.ATTACK);
                if (config.trainDefence()) skills.add(Skill.DEFENCE);
                return MeleeStyles.balancedChoice(skills, current, config.balanceMargin(),
                        Rs2Player::getRealSkillLevel, this::targetLevel);
            default:
                return null;
        }
    }

    private int targetLevel(Skill skill) {
        switch (skill) {
            case ATTACK:
                return config.attackTarget();
            case STRENGTH:
                return config.strengthTarget();
            case DEFENCE:
                return config.defenceTarget();
            default:
                return 99;
        }
    }

    /** Drinks a boosting potion for the combat type in use if one is due. True when this tick was spent on it. */
    private boolean drinkBoosts() {
        switch (combatKind) {
            case RANGED: {
                String potion = config.rangedPotion().itemName;
                return potion != null && drinkBoost(Skill.RANGED, potion);
            }
            case MAGIC: {
                if (invigorateHeart()) return true;
                String potion = config.magicPotion().itemName;
                return potion != null && drinkBoost(Skill.MAGIC, potion);
            }
            default:
                return (config.useSuperStrength() && drinkBoost(Skill.STRENGTH, SUPER_STRENGTH))
                        || (config.useSuperAttack() && drinkBoost(Skill.ATTACK, SUPER_ATTACK));
        }
    }

    /**
     * Drinks once the boost is down to this skill's re-drink level, then rolls a new level for the next dose. The
     * level is kept below what one dose gives, or a small boost (a magic potion's +4) would be drunk back to back.
     */
    private boolean drinkBoost(Skill skill, String potion) {
        int rolled = redrinkAt.computeIfAbsent(skill, s -> rollRedrinkLevel());
        int at = Math.min(rolled, Math.max(0, doseBoost(skill) - 1));
        if (!BoostPotions.drinkIfLow(skill, potion, at)) return false;
        stats.addPotion();
        int next = rollRedrinkLevel();
        redrinkAt.put(skill, next);
        log.debug("[GemCrab] Drank {} at +{} boost; next dose at +{}", potion, at, next);
        return true;
    }

    /** The heart in use for magic, or -1 for none (or when not on magic). */
    private int heartId() {
        return combatKind == WeaponStyles.Kind.MAGIC ? config.magicHeart().itemId : -1;
    }

    /**
     * Invigorates the heart once the Magic boost is down to the re-drink level and the heart is off cooldown. The
     * cooldown comes from the game's heart timer varbit, backed by our own 7-minute timer in case the saturated heart
     * doesn't use it. True when this tick was spent on the heart.
     */
    private boolean invigorateHeart() {
        int heart = heartId();
        if (heart < 0 || Rs2Bank.isOpen() || !Rs2Inventory.hasItem(heart)) return false;
        long now = System.currentTimeMillis();
        if (now < heartRetryAt || Microbot.getVarbitValue(VarbitID.IMBUED_HEART_TIMER) > 0) return false;
        int rolled = redrinkAt.computeIfAbsent(Skill.MAGIC, s -> rollRedrinkLevel());
        int at = Math.min(rolled, Math.max(0, heartBoost(heart) - 1));
        if (BoostPotions.boost(Skill.MAGIC) > at) return false;

        int before = Rs2Player.getBoostedSkillLevel(Skill.MAGIC);
        Microbot.status = "Invigorating the " + config.magicHeart();
        if (Rs2Inventory.interact(heart, "Invigorate")
                && sleepUntil(() -> Rs2Player.getBoostedSkillLevel(Skill.MAGIC) > before, 2400)) {
            stats.addPotion();
            heartRetryAt = now + HEART_COOLDOWN_MS;
            int next = rollRedrinkLevel();
            redrinkAt.put(Skill.MAGIC, next);
            log.debug("[GemCrab] Invigorated the {} at +{} boost; next boost at +{}", config.magicHeart(), at, next);
        } else {
            // Still cooling down (or the click missed): leave it a while and let the potion cover the gap.
            heartRetryAt = now + HEART_RETRY_MS;
        }
        return true;
    }

    private static final long HEART_COOLDOWN_MS = TimeUnit.MINUTES.toMillis(7);
    private static final long HEART_RETRY_MS = TimeUnit.SECONDS.toMillis(30);

    /** Imbued heart: 1 + 10% of Magic. Saturated heart: 4 + 10%. */
    private static int heartBoost(int heart) {
        int level = Rs2Player.getRealSkillLevel(Skill.MAGIC);
        return (heart == ItemID.SATURATED_HEART ? 4 : 1) + level / 10;
    }

    /** With the bank open, takes the heart out if it's in use and not carried. True when a withdrawal was made. */
    private boolean withdrawHeart() {
        int heart = heartId();
        if (heart < 0 || Rs2Inventory.hasItem(heart)) return false;
        if (!Rs2Bank.hasItem(heart)) {
            if (!heartMissingLogged) {
                heartMissingLogged = true;
                log.info("[GemCrab] No {} in the bank; using potions only", config.magicHeart());
            }
            return false;
        }
        Microbot.status = "Withdrawing the " + config.magicHeart();
        return Rs2Bank.withdrawOne(heart) && sleepUntil(() -> Rs2Inventory.hasItem(heart), 2400);
    }

    /** A random level between the configured min and max boost, inclusive (either order). */
    private int rollRedrinkLevel() {
        int lo = Math.min(config.redrinkMinBoost(), config.redrinkMaxBoost());
        int hi = Math.max(config.redrinkMinBoost(), config.redrinkMaxBoost());
        return ThreadLocalRandom.current().nextInt(lo, hi + 1);
    }

    /** What one dose adds at the current level: super attack/strength 5+15%, ranging 4+10%, magic potions +4. */
    private static int doseBoost(Skill skill) {
        int level = Rs2Player.getRealSkillLevel(skill);
        switch (skill) {
            case RANGED:
                return 4 + level / 10;
            case MAGIC:
                return 4;
            default:
                return 5 + level * 15 / 100;
        }
    }

    static final String SUPER_STRENGTH = "Super strength";
    static final String SUPER_ATTACK = "Super attack";

    /** The potions for the combat type in use: taken from the bank and kept out of deposits. */
    private List<String> enabledPotions() {
        List<String> potions = new ArrayList<>(2);
        switch (combatKind) {
            case RANGED:
                if (config.rangedPotion().itemName != null) potions.add(config.rangedPotion().itemName);
                break;
            case MAGIC:
                if (config.magicPotion().itemName != null) potions.add(config.magicPotion().itemName);
                break;
            default:
                if (config.useSuperStrength()) potions.add(SUPER_STRENGTH);
                if (config.useSuperAttack()) potions.add(SUPER_ATTACK);
                break;
        }
        return potions;
    }

    // --- Guards & helpers ---

    /** A live crab next to us that isn't the one that just burrowed. */
    private boolean crabReady() {
        if (liveCrab() == null) return false;
        long burrow = burrowedAt;
        return crabSpawnedAt > burrow || System.currentTimeMillis() - burrow > BURROW_SETTLE_MS;
    }

    private Rs2NpcModel liveCrab() {
        return Microbot.getRs2NpcCache().query()
                .withId(CRAB_ID)
                .where(npc -> !npc.isDead())
                .nearestOnClientThread(SITE_RADIUS);
    }

    private boolean shellNearby() {
        return Microbot.getRs2NpcCache().query().withId(SHELL_ID).nearestOnClientThread(SITE_RADIUS) != null;
    }

    private Rs2TileObjectModel findCave() {
        Rs2TileObjectModel cave = Microbot.getRs2TileObjectCache().query()
                .withId(CAVE_ID)
                .nearestOnClientThread(SITE_RADIUS + 10);
        if (cave != null) return cave;
        return Microbot.getRs2TileObjectCache().query()
                .withName(CAVE_NAME)
                .withAction(CAVE_ACTION)
                .nearestOnClientThread(SITE_RADIUS + 10);
    }

    private boolean isAttackingCrab() {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Player me = Microbot.getClient().getLocalPlayer();
            Actor target = me == null ? null : me.getInteracting();
            return target != null && CRAB_NAME.equals(target.getName());
        }).orElse(false);
    }

    private static boolean movedFrom(WorldPoint from, int distance) {
        WorldPoint here = Rs2Player.getWorldLocation();
        return here != null && (here.getPlane() != from.getPlane() || here.distanceTo(from) >= distance);
    }

    /** The mine whose crab spot is within {@code maxDistance} of us, closest first; null if none is. */
    private static Site nearestSite(int maxDistance) {
        WorldPoint here = Rs2Player.getWorldLocation();
        if (here == null) return null;
        Site best = null;
        for (Site site : Site.values()) {
            int d = here.distanceTo(site.crab);
            if (d <= maxDistance && (best == null || d < here.distanceTo(best.crab))) best = site;
        }
        return best;
    }

    private static boolean nearAnySite() {
        return nearestSite(SITE_RADIUS + 15) != null;
    }

    private boolean hasPickaxe() {
        return Rs2Inventory.items(item -> item.getName() != null
                        && item.getName().toLowerCase(Locale.ROOT).endsWith("pickaxe")).findAny().isPresent()
                || Rs2Equipment.isWearing("pickaxe", false);
    }

    private boolean wantsToMine() {
        return config.mineShell() && hasPickaxe() && !Rs2Inventory.isFull();
    }

    private boolean carriesGems() {
        return Rs2Inventory.items(item -> GEMS.containsValue(item.getId())).findAny().isPresent();
    }

    private boolean needsBank() {
        boolean lowWithoutFood = !hasFood() && Rs2Player.getHealthPercentage() < config.bankAtPercent();
        boolean fullOfGems = config.mineShell() && Rs2Inventory.isFull() && carriesGems();
        return lowWithoutFood || fullOfGems;
    }

    /** Everything goes in the bank except food, the pickaxe, our potions, staminas, runes and ammo. */
    private boolean isBankable(Rs2ItemModel item) {
        if (item.isFood() || StaminaPotions.isStamina(item.getId())) return false;
        if (item.getId() == ItemID.IMBUED_HEART || item.getId() == ItemID.SATURATED_HEART) return false;
        String name = item.getName() == null ? "" : item.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith("pickaxe") || isRunesOrAmmo(name)) return false;
        for (String potion : enabledPotions()) {
            if (BoostPotions.isPotion(item, potion)) return false;
        }
        return true;
    }

    private static final String[] AMMO_WORDS = {
            "arrow", "bolts", "dart", "knife", "javelin", "chinchompa", "thrownaxe", "throwing axe"
    };

    private static boolean isRunesOrAmmo(String name) {
        if (name.endsWith(" rune") || name.contains("rune pouch")) return true;
        for (String word : AMMO_WORDS) {
            if (name.contains(word)) return true;
        }
        return false;
    }

    private boolean hasFood() {
        return !Rs2Inventory.getInventoryFood().isEmpty();
    }

    private int foodCount() {
        return Rs2Inventory.getInventoryFood().size();
    }

    private boolean eatIfNeeded() {
        if (!hasFood() || Rs2Player.getHealthPercentage() > config.eatAtPercent()) return false;
        if (Rs2Bank.isOpen()) {
            Rs2Bank.closeBank();
            return true;
        }
        Microbot.status = "Eating";
        int before = foodCount();
        if (Rs2Player.useFood()) {
            sleepUntil(() -> foodCount() < before, 1_800);
        }
        return true;
    }

    private void antibanAfterAction() {
        if (!config.antiban()) return;
        Rs2Antiban.actionCooldown();
        Rs2Antiban.takeMicroBreakByChance();
    }

    private void markIntentionalWait() {
        lastIntentionalWaitAt = System.currentTimeMillis();
    }

    // --- Watchdog ---

    /** Logs out and stops when a state makes no progress (xp, movement, inventory or a chosen wait) for 2 minutes. */
    private boolean runWatchdog() {
        State state = getCurrentState();
        if (state == null) return false;
        long now = System.currentTimeMillis();
        String signature = progressSignature();
        boolean progressed = !signature.equals(progressSignature)
                || now - lastIntentionalWaitAt < INTENTIONAL_WAIT_GRACE_MS;
        progressSignature = signature;
        if (state != watchedState || progressed) {
            watchedState = state;
            lastProgressAt = now;
            return false;
        }
        if (now - lastProgressAt < STALL_RESTART_MS) return false;

        String reason = "no progress in " + state + " for " + (now - lastProgressAt) / 1000 + "s";
        log.warn("[GemCrab] Watchdog: {}; logging out and stopping", reason);
        stats.addRecovery();
        StuckGuard.trip(reason);
        return true;
    }

    private String progressSignature() {
        long xp = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            long total = 0;
            for (Skill skill : GemCrabStats.TRACKED_SKILLS) {
                total += Microbot.getClient().getSkillExperience(skill);
            }
            return total;
        }).orElse(0L);
        int inventory = Rs2Inventory.items().mapToInt(Rs2ItemModel::getQuantity).sum();
        return xp + "|" + Rs2Player.getWorldLocation() + "|" + inventory;
    }

    // --- Lifecycle ---

    public boolean run(GemCrabConfig config) {
        this.config = config;
        burrowedAt = 0;
        burrowPending = false;
        crabSpawnedAt = 0;
        nextStyleCheckAt = 0;
        redrinkAt.clear();
        outOfSupplies = null;
        combatKind = WeaponStyles.Kind.MELEE;
        styleLabel = "";
        watchedState = null;
        progressSignature = "";
        stats.reset();

        if (config.antiban()) {
            Rs2Antiban.resetAntibanSettings();
            Rs2Antiban.antibanSetupTemplates.applyCombatSetup();
            MouseIdleHabits.enable();
        }

        StuckGuard.attach("Shadowfax Gem Crab", GemCrabPlugin.class);

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (StuckGuard.isTripped()) return;
                if (!Microbot.isLoggedIn()) return;
                stats.captureBaselineIfNeeded();
                String outOf = outOfSupplies;
                if (outOf != null) {
                    log.warn("[GemCrab] Out of ammo or runes (\"{}\"); stopping", outOf);
                    Microbot.status = "Out of ammo or runes; stopped";
                    Microbot.stopPlugin(GemCrabPlugin.class);
                    return;
                }
                if (runWatchdog()) return;
                if (eatIfNeeded()) return;
                if (Rs2AntibanSettings.actionCooldownActive) {
                    // The cooldown only counts down out of combat, which is rare here; wait it out briefly when
                    // idle, and cancel it in a fight or once it has run a few seconds.
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
                State state = getCurrentState();
                // Only on the way to or from the bank; fights and crawls don't use run energy.
                if (config.useStaminas() && (state == State.TRAVEL || state == State.BANK)
                        && StaminaPotions.drinkIfNeeded(config.staminaAtEnergy())) {
                    return;
                }
                step();
            } catch (Throwable ex) {
                log.error("[GemCrab] Unexpected error in scheduled loop", ex);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);

        return true;
    }

    @Override
    public void shutdown() {
        StuckGuard.detach(GemCrabPlugin.class);
        super.shutdown();
        Rs2Antiban.resetAntibanSettings();
    }
}
