package net.runelite.client.plugins.microbot.util.walker;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Chartering a ship from a script, for routes a script wants to take on purpose rather than leave to the walker's
 * planner (e.g. Catherby to Port Phasmatys). Uses the walker's own charter-menu handling.
 */
@Slf4j
public final class Rs2CharterShips {

    private static final int CHARTER_MENU_GROUP = 885;
    private static final int CHARTER_MENU_DESTINATIONS = 4;

    private Rs2CharterShips() {
    }

    /**
     * Talks to the nearest Trader Crewmember with "Charter", picks {@code destination} (as the menu names it, e.g.
     * "Port Phasmatys") and confirms. One attempt; true if the destination was chosen. The voyage itself takes a
     * few seconds after this returns.
     */
    public static boolean charter(String destination) {
        Rs2NpcModel crew = Microbot.getRs2NpcCache().query().withName("Trader Crewmember")
                .nearestReachableOnClientThread(15);
        if (crew == null) {
            log.info("[Charter] No Trader Crewmember in reach from {}", Rs2Player.getWorldLocation());
            return false;
        }
        Microbot.status = "Chartering a ship to " + destination;
        if (!crew.click("Charter")) return false;
        Rs2Player.waitForWalking();
        if (!sleepUntil(() -> Rs2Widget.isWidgetVisible(CHARTER_MENU_GROUP, CHARTER_MENU_DESTINATIONS), 5_000)) {
            log.info("[Charter] The charter menu didn't open");
            return false;
        }
        Widget choice = Rs2WalkerTransports.findCharterDestinationWidget(destination);
        if (!Rs2WalkerTransports.invokeCharterDestinationWidget(choice, destination)) {
            log.info("[Charter] Couldn't pick {} in the charter menu", destination);
            return false;
        }
        Rs2WalkerTransports.confirmCharterTravelIfPrompted();
        return true;
    }
}
