package net.runelite.client.plugins.microbot.shadowfaxwalker;

import net.runelite.client.plugins.microbot.util.safety.StuckGuard;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.statemachine.StateMachineScript;
import net.runelite.client.plugins.microbot.statemachine.Transition;
import net.runelite.client.plugins.microbot.util.misc.AttemptGuard;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.player.StaminaPotions;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.walker.WalkerState;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Walks to a place chosen in the {@link DestinationPicker}.
 * <p>
 * CHOOSE → WALK → ARRIVED → (picker again) → WALK …
 * <ul>
 *   <li><b>CHOOSE</b>: the picker is open; closing it without choosing turns the plugin off.</li>
 *   <li><b>WALK</b>: Microbot's web walker takes it there, teleports and transports included. The hotkey opens the
 *       picker mid-walk; a new choice cancels the current route and heads off to the new place.</li>
 *   <li><b>ARRIVED</b>: asks for the next place, or turns the plugin off when "Ask again on arrival" is off.</li>
 *   <li><b>FINISH</b>: the walk failed three times without the player moving; logged and the plugin stops.</li>
 * </ul>
 */
@Slf4j
@Singleton
public class WalkerScript extends StateMachineScript<WalkerScript.State> {

    enum State { CHOOSE, WALK, ARRIVED, FINISH }

    private static final int MAX_RECENT = 6;
    private static final int MAX_ATTEMPTS = 3;
    private static final long ARRIVED_LINGER_MS = 2_500;
    // After a dose that didn't go down (no effect seen), don't stop the walk for another one this soon.
    private static final long STAMINA_RETRY_MS = 60_000;

    @Inject
    private Client client;

    @Inject
    private ConfigManager configManager;

    private WalkerConfig config;
    private final AttemptGuard guard = new AttemptGuard(MAX_ATTEMPTS, 0);

    // Set by the picker on the Swing thread, consumed by the transition into WALK.
    private volatile Destination pending;
    private volatile DestinationPicker picker;
    // Updated every game tick on the client thread, so the picker never has to ask the client from Swing.
    private volatile WorldPoint playerLocation;

    // Trip, read by the overlay.
    @Getter
    private volatile Destination destination;
    @Getter
    private volatile int startDistance;
    @Getter
    private volatile long walkStartedAt;
    @Getter
    private volatile long arrivedAt;
    @Getter
    private volatile int trips;
    @Getter
    private volatile String failure;
    private boolean finishing;
    private long staminaFailedAt;

    @Override
    protected State initialState() {
        return State.CHOOSE;
    }

    @Override
    protected List<Transition<State>> defineTransitions() {
        return List.of(
                Transition.<State>from(State.CHOOSE)
                        .when(() -> pending != null, "pending != null")
                        .because("Destination chosen")
                        .goTo(State.WALK),

                Transition.<State>from(State.WALK)
                        .when(() -> pending != null, "pending != null")
                        .because("New destination chosen mid-walk")
                        .goTo(State.CHOOSE),
                Transition.<State>from(State.WALK)
                        .when(() -> failure != null, "failure != null")
                        .because("Walk keeps failing")
                        .goTo(State.FINISH),
                Transition.<State>from(State.WALK)
                        .when(this::arrived, "arrived()")
                        .because("At the destination")
                        .goTo(State.ARRIVED),

                Transition.<State>from(State.ARRIVED)
                        .when(() -> pending != null, "pending != null")
                        .because("Next destination chosen")
                        .goTo(State.WALK),
                Transition.<State>from(State.ARRIVED)
                        .when(() -> !config.askAgain() && System.currentTimeMillis() - arrivedAt > ARRIVED_LINGER_MS,
                                "!askAgain && lingered")
                        .because("Arrived; not asking for another place")
                        .goTo(State.FINISH)
        );
    }

    @Override
    protected void onTransition(State from, State to, String reason) {
        super.onTransition(from, to, reason);
        if (to == State.WALK) {
            destination = pending;
            pending = null;
            failure = null;
            guard.reset();
            WorldPoint here = Rs2Player.getWorldLocation();
            startDistance = here == null ? 0 : DestinationPicker.distanceBetween(here, destination.getPoint());
            walkStartedAt = System.currentTimeMillis();
            arrivedAt = 0;
            log.info("[ShadowfaxWalker] Walking to {} ({}) from {}", destination.getName(), destination.coordinates(), here);
        }
        if (to == State.ARRIVED) {
            arrivedAt = System.currentTimeMillis();
            trips++;
            Microbot.status = "Arrived at " + destination.getName();
            log.info("[ShadowfaxWalker] Arrived at {} in {}s", destination.getName(),
                    (arrivedAt - walkStartedAt) / 1000);
        }
    }

    @Override
    protected void onState(State state) {
        switch (state) {
            case CHOOSE:
                Microbot.status = "Choose a destination";
                // Wait one game tick for the player's position, so the picker can show distances.
                if (playerLocation != null) openPicker();
                break;
            case WALK:
                walk();
                break;
            case ARRIVED:
                if (config.askAgain() && System.currentTimeMillis() - arrivedAt > ARRIVED_LINGER_MS) openPicker();
                break;
            case FINISH:
                finish();
                break;
        }
    }

    // --- States ---

    private void walk() {
        if (arrived()) return;
        if (wantsStamina()) {
            if (!StaminaPotions.drinkIfNeeded(config.staminaAtEnergy())) staminaFailedAt = System.currentTimeMillis();
            return;
        }
        WorldPoint here = Rs2Player.getWorldLocation();
        // A walk that ends with the player on the same tile three times in a row isn't going to get there.
        if (!guard.attempt("Walk to " + destination.getName(), String.valueOf(here))) {
            failure = guard.getFailure();
            StuckGuard.trip(failure);
            return;
        }
        Microbot.status = "Walking to " + destination.getName();
        // The walk hands back control when a dose is due, so the potion is drunk here between walker clicks rather
        // than from another thread mid-click; the next tick drinks it and walks on.
        WalkerState result = config.useStaminas()
                ? Rs2Walker.walkWithStateUntil(destination.getPoint(), config.arrivalDistance(), this::wantsStamina)
                : Rs2Walker.walkWithState(destination.getPoint(), config.arrivalDistance());
        if (result == WalkerState.UNREACHABLE) {
            Microbot.status = "No path yet to " + destination.getName() + "; retrying";
            log.warn("[ShadowfaxWalker] Walker found no path to {} from {}", destination.getPoint(), here);
        }
    }

    private void finish() {
        if (finishing) return;
        finishing = true;
        if (failure != null) {
            String what = "couldn't reach " + destination.getName() + " (" + destination.coordinates() + "): " + failure;
            log.error("[ShadowfaxWalker] Stopping: {}", what);
            Microbot.log("Shadowfax Walker stopped: " + what);
            Microbot.status = "Stuck: " + what;
        } else {
            Microbot.status = "Arrived at " + destination.getName();
        }
        Rs2Walker.clearWalkingRoute("shadowfax-walker:finish");
        Microbot.stopPlugin(WalkerPlugin.class);
    }

    // --- Picker ---

    private void openPicker() {
        openPicker(false);
    }

    /**
     * Opens the picker unless it's already open; {@code toFront} (the hotkey) also raises an open one. Safe from any
     * thread: the states call this every tick while waiting, so it must stay a no-op once the window is up.
     */
    void openPicker(boolean toFront) {
        if (finishing) return;
        SwingUtilities.invokeLater(() -> {
            if (finishing || pending != null) return;
            if (picker != null && picker.isDisplayable()) {
                if (toFront) {
                    picker.toFront();
                    picker.requestFocus();
                }
                return;
            }
            Window owner = client.getCanvas() != null ? SwingUtilities.getWindowAncestor(client.getCanvas()) : null;
            picker = new DestinationPicker(owner, playerLocation, recent(), this::chosen, this::pickerClosed);
            picker.setVisible(true);
        });
    }

    private void chosen(Destination d) {
        picker = null;
        remember(d);
        pending = d;
        if (getCurrentState() == State.WALK) {
            // Breaks the walker out of the current route so the script can head for the new place.
            Rs2Walker.clearWalkingRoute("shadowfax-walker:new-destination");
        }
    }

    /** Closed without choosing: carry on with a walk in progress, otherwise there is nothing left to do. */
    private void pickerClosed() {
        picker = null;
        if (getCurrentState() == State.WALK) return;
        finishing = true;
        Microbot.status = "No destination chosen";
        Microbot.stopPlugin(WalkerPlugin.class);
    }

    // --- Recent places ---

    private List<String> recent() {
        String saved = config.recentDestinations();
        if (saved == null || saved.isEmpty()) return new ArrayList<>();
        return DestinationPicker.cleanRecent(Arrays.asList(saved.split("\\|")), MAX_RECENT);
    }

    private void remember(Destination d) {
        List<String> names = new ArrayList<>();
        names.add(d.getName());
        names.addAll(recent());
        configManager.setConfiguration(WalkerConfig.GROUP, WalkerConfig.RECENT_KEY,
                String.join("|", DestinationPicker.cleanRecent(names, MAX_RECENT)));
    }

    // --- Helpers ---

    /** Run energy at or below the threshold, no stamina effect running, and a potion to drink. Read-only. */
    private boolean wantsStamina() {
        return config.useStaminas()
                && System.currentTimeMillis() - staminaFailedAt > STAMINA_RETRY_MS
                && StaminaPotions.carried() > 0
                && StaminaPotions.usable()
                && !Rs2Player.hasStaminaActive()
                && Rs2Player.getRunEnergy() <= config.staminaAtEnergy();
    }

    private boolean arrived() {
        Destination d = destination;
        WorldPoint here = playerLocation;
        if (d == null || here == null || here.getPlane() != d.getPoint().getPlane()) return false;
        return DestinationPicker.distanceBetween(here, d.getPoint()) <= Math.max(1, config.arrivalDistance());
    }

    /** Called by the plugin on each game tick (client thread). */
    void onGameTick() {
        Player local = client.getLocalPlayer();
        if (local == null) return;
        playerLocation = WorldPoint.fromLocalInstance(client, local.getLocalLocation());
    }

    WorldPoint playerLocation() {
        return playerLocation;
    }

    // --- Lifecycle ---

    public boolean run(WalkerConfig config) {
        this.config = config;
        pending = null;
        destination = null;
        failure = null;
        finishing = false;
        trips = 0;
        arrivedAt = 0;
        walkStartedAt = 0;
        guard.reset();
        staminaFailedAt = 0;
        playerLocation = null;

        // Logged in, the CHOOSE state opens the picker on the first tick, once the player's position is known for
        // distances. Logged out, ask straight away; the walk itself waits for login.
        if (!Microbot.isLoggedIn()) openPicker();

        StuckGuard.attach("Shadowfax Walker", WalkerPlugin.class);

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (StuckGuard.isTripped()) return;
                if (!Microbot.isLoggedIn()) return;
                step();
            } catch (Throwable ex) {
                // Throwable, not Exception: an uncaught Error would cancel the scheduled task without a log line.
                log.error("[ShadowfaxWalker] Unexpected error in scheduled loop", ex);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);
        return true;
    }

    @Override
    public void shutdown() {
        StuckGuard.detach(WalkerPlugin.class);
        finishing = true;
        DestinationPicker open = picker;
        picker = null;
        if (open != null) SwingUtilities.invokeLater(open::close);
        if (destination != null && arrivedAt == 0) {
            Rs2Walker.clearWalkingRoute("shadowfax-walker:shutdown");
        }
        super.shutdown();
    }
}
