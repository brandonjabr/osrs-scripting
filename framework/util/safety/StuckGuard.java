package net.runelite.client.plugins.microbot.util.safety;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.player.Rs2Player.TerminalLogoutState;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.util.Text;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.runelite.client.plugins.microbot.util.Global.sleep;

/**
 * Stops a script that's stuck: no more actions, log out (with AutoLogin held off), then stop the plugin.
 * <p>
 * A script attaches in {@code run()} and detaches in {@code shutdown()}, and its loop returns early while
 * {@link #isTripped()}. The guard trips on any of:
 * <ul>
 *   <li><b>Repeated actions with no progress</b>: the same menu click over and over, or many clicks, while nothing
 *       in the game moves forward (xp, inventory, equipment, bank, a tile not stood on recently, or dialogue).</li>
 *   <li><b>Microbot saying it's stuck</b>: the walker's log lines for stall recalculations, giving up on a route
 *       ("tail_max"), stuck sidesteps, or repeated "unreachable" warnings, piling up with no progress between.</li>
 *   <li><b>The script's own watchdog</b>, through {@link #trip(String)}.</li>
 * </ul>
 */
@Slf4j
public final class StuckGuard {

    // Same click this many times in a row, with no progress for at least the window: stuck.
    private static final int SAME_CLICK_LIMIT = 6;
    private static final long SAME_CLICK_WINDOW_MS = 45_000;
    // Or this many clicks of any kind with no progress for this long.
    private static final int ANY_CLICK_LIMIT = 15;
    private static final long ANY_CLICK_WINDOW_MS = 90_000;
    // Walker distress signals since the last progress.
    private static final int WALKER_STALL_LIMIT = 3;
    private static final int WALKER_GAVE_UP_LIMIT = 2;
    private static final int UNREACHABLE_LIMIT = 6;
    // Tiles stood on recently don't count as progress, so pacing back and forth isn't mistaken for moving on.
    private static final int RECENT_TILES = 40;
    private static final long LOGOUT_TRY_MS = 90_000;

    private static volatile Session session;
    private static volatile boolean appenderInstalled;

    private StuckGuard() {
    }

    /** Starts watching for the script behind {@code pluginClass}; replaces any earlier session. */
    public static synchronized void attach(String scriptName, Class<? extends Plugin> pluginClass) {
        detachCurrent();
        installAppender();
        Session s = new Session(scriptName, pluginClass);
        Microbot.getEventBus().register(s);
        session = s;
        log.info("[StuckGuard] Watching {} for being stuck", scriptName);
    }

    /** Stops watching (from the script's shutdown). Does nothing if another script attached since. */
    public static synchronized void detach(Class<? extends Plugin> pluginClass) {
        Session s = session;
        if (s != null && s.pluginClass == pluginClass) detachCurrent();
    }

    private static void detachCurrent() {
        Session s = session;
        if (s == null) return;
        session = null;
        try {
            Microbot.getEventBus().unregister(s);
        } catch (RuntimeException ignored) {
            // Not registered.
        }
    }

    /** True once the guard has tripped: the script must stop taking actions. */
    public static boolean isTripped() {
        Session s = session;
        return s != null && s.tripped.get();
    }

    /** Trips the guard from the script itself (its watchdog found no progress). */
    public static void trip(String reason) {
        Session s = session;
        if (s != null) s.trip(reason);
    }

    // --- Log watching ---

    private static void installAppender() {
        if (appenderInstalled) return;
        try {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
                @Override
                protected void append(ILoggingEvent event) {
                    Session s = session;
                    if (s != null && !event.getLoggerName().equals(StuckGuard.class.getName())) {
                        s.onLog(event.getFormattedMessage());
                    }
                }
            };
            appender.setName("ShadowfaxStuckGuard");
            appender.setContext(context);
            appender.start();
            context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
            appenderInstalled = true;
        } catch (RuntimeException e) {
            log.warn("[StuckGuard] Couldn't watch the log; only clicks and the script watchdog will trip it", e);
            appenderInstalled = true;
        }
    }

    // --- One watched script ---

    public static final class Session {
        private final String scriptName;
        private final Class<? extends Plugin> pluginClass;
        private final AtomicBoolean tripped = new AtomicBoolean();

        private final LinkedHashSet<WorldPoint> recentTiles = new LinkedHashSet<>();
        private long lastXp = -1;
        private int lastInventory;
        private int lastEquipment;
        private int lastBank;
        private String lastDialogue = "";
        private volatile long lastProgressAt = System.currentTimeMillis();

        private int clicksSinceProgress;
        private String lastClick;
        private int sameClickStreak;
        private volatile int walkerStalls;
        private volatile int walkerGaveUp;
        private volatile int unreachable;

        Session(String scriptName, Class<? extends Plugin> pluginClass) {
            this.scriptName = scriptName;
            this.pluginClass = pluginClass;
        }

        @Subscribe
        public void onGameTick(GameTick event) {
            if (tripped.get()) return;
            Client client = Microbot.getClient();
            if (client == null || !Microbot.isLoggedIn()) {
                lastProgressAt = System.currentTimeMillis();
                return;
            }
            if (madeProgress(client)) {
                lastProgressAt = System.currentTimeMillis();
                clicksSinceProgress = 0;
                sameClickStreak = 0;
                lastClick = null;
                walkerStalls = 0;
                walkerGaveUp = 0;
                unreachable = 0;
                return;
            }
            check();
        }

        @Subscribe
        public void onMenuOptionClicked(MenuOptionClicked event) {
            if (tripped.get()) return;
            String key = event.getMenuOption() + ">" + Text.removeTags(event.getMenuTarget()) + "#" + event.getId();
            clicksSinceProgress++;
            if (key.equals(lastClick)) {
                sameClickStreak++;
            } else {
                lastClick = key;
                sameClickStreak = 1;
            }
        }

        void onLog(String message) {
            if (tripped.get() || message == null) return;
            String lower = message.toLowerCase(Locale.ROOT);
            if (lower.contains("stall_recalc") || lower.contains("stuck sidestep")) {
                walkerStalls++;
            } else if (lower.contains("tail_max")) {
                walkerGaveUp++;
            } else if (lower.contains("unreachable")) {
                unreachable++;
            }
        }

        /** Any sign the game moved forward since the last tick. Runs on the client thread. */
        private boolean madeProgress(Client client) {
            boolean progress = false;

            long xp = client.getOverallExperience();
            if (xp != lastXp) {
                progress |= lastXp >= 0;
                lastXp = xp;
            }
            int inventory = hash(client.getItemContainer(InventoryID.INV));
            if (inventory != lastInventory) {
                progress = true;
                lastInventory = inventory;
            }
            int equipment = hash(client.getItemContainer(InventoryID.WORN));
            if (equipment != lastEquipment) {
                progress = true;
                lastEquipment = equipment;
            }
            int bank = hash(client.getItemContainer(InventoryID.BANK));
            if (bank != 0 && bank != lastBank) {
                progress = true;
                lastBank = bank;
            }
            Player me = client.getLocalPlayer();
            WorldPoint tile = me == null ? null : me.getWorldLocation();
            if (tile != null && !recentTiles.contains(tile)) {
                progress = true;
                recentTiles.add(tile);
                if (recentTiles.size() > RECENT_TILES) {
                    recentTiles.remove(recentTiles.iterator().next());
                }
            }
            String dialogue = Rs2Dialogue.isInDialogue() ? Objects.toString(Rs2Dialogue.getDialogueText(), "") : "";
            if (!dialogue.equals(lastDialogue)) {
                progress |= !dialogue.isEmpty();
                lastDialogue = dialogue;
            }
            return progress;
        }

        private void check() {
            long idle = System.currentTimeMillis() - lastProgressAt;
            if (sameClickStreak >= SAME_CLICK_LIMIT && idle >= SAME_CLICK_WINDOW_MS) {
                trip("clicked \"" + lastClick + "\" " + sameClickStreak + " times in a row with no progress for "
                        + idle / 1000 + "s");
            } else if (clicksSinceProgress >= ANY_CLICK_LIMIT && idle >= ANY_CLICK_WINDOW_MS) {
                trip(clicksSinceProgress + " clicks with no progress for " + idle / 1000 + "s");
            } else if (walkerStalls >= WALKER_STALL_LIMIT) {
                trip("the walker stalled " + walkerStalls + " times with no progress");
            } else if (walkerGaveUp >= WALKER_GAVE_UP_LIMIT) {
                trip("the walker gave up on its route " + walkerGaveUp + " times with no progress");
            } else if (unreachable >= UNREACHABLE_LIMIT) {
                trip("\"unreachable\" logged " + unreachable + " times with no progress");
            }
        }

        void trip(String reason) {
            if (!tripped.compareAndSet(false, true)) return;
            log.warn("[StuckGuard] {} looks stuck ({}); logging out and stopping it", scriptName, reason);
            Microbot.status = "Stuck: " + reason + " — logging out";
            Thread thread = new Thread(() -> logOutAndStop(reason), "StuckGuard-" + scriptName);
            thread.setDaemon(true);
            thread.start();
        }

        private void logOutAndStop(String reason) {
            try {
                Rs2Walker.setTarget(null, "StuckGuard: " + reason);
            } catch (RuntimeException e) {
                log.debug("[StuckGuard] Couldn't clear the walker target", e);
            }
            long deadline = System.currentTimeMillis() + LOGOUT_TRY_MS;
            // Retry: the game refuses to log out for 10 seconds after combat.
            while (Microbot.isLoggedIn() && System.currentTimeMillis() < deadline) {
                try {
                    if (Rs2Player.logoutWithoutAutoLogin() == TerminalLogoutState.LOGGED_OUT) break;
                } catch (RuntimeException e) {
                    log.warn("[StuckGuard] Logout attempt failed", e);
                }
                sleep(3_000);
            }
            if (Microbot.isLoggedIn()) {
                log.error("[StuckGuard] Still logged in after {}s of trying; stopping {} anyway",
                        LOGOUT_TRY_MS / 1000, scriptName);
            } else {
                log.info("[StuckGuard] Logged out");
            }
            Microbot.status = "Stopped (stuck): " + reason;
            Microbot.stopPlugin(pluginClass);
        }
    }

    private static int hash(ItemContainer container) {
        if (container == null) return 0;
        Item[] items = container.getItems();
        int[] values = new int[items.length * 2];
        for (int i = 0; i < items.length; i++) {
            values[2 * i] = items[i].getId();
            values[2 * i + 1] = items[i].getQuantity();
        }
        return Arrays.hashCode(values);
    }
}
