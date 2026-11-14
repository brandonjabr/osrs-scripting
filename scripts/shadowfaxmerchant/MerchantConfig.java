package net.runelite.client.plugins.microbot.shadowfaxmerchant;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(MerchantConfig.GROUP)
public interface MerchantConfig extends Config {

    String GROUP = "shadowfaxmerchant";
    String STATE_KEY = "savedState";

    enum Mode {
        /** Places and manages offers. */
        TRADE,
        /** Only scans and shows the best opportunities; never touches the GE. */
        SCAN_ONLY
    }

    @ConfigSection(
            name = "Capital & risk",
            description = "How much to trade with and how much risk to take",
            position = 0
    )
    String capitalSection = "capital";

    @ConfigSection(
            name = "Strategy",
            description = "What counts as a good flip or dip",
            position = 1
    )
    String strategySection = "strategy";

    enum WaitMode {
        STAY("Stay at the GE"),
        LOG_OUT("Log out for a while"),
        GEM_CRAB("Kill Gemstone Crabs");

        private final String label;

        WaitMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    @ConfigSection(
            name = "While waiting",
            description = "What to do once every offer is placed and there's nothing to do but wait",
            position = 2
    )
    String waitingSection = "waiting";

    @ConfigSection(
            name = "General",
            description = "Mode, stopping and overlay",
            position = 3
    )
    String generalSection = "general";

    @ConfigItem(
            keyName = "whileWaiting",
            name = "While waiting",
            description = "Once the coins are in offers and nothing has needed doing for a few minutes: stay at the GE, "
                    + "log out and come back later, or go kill Gemstone Crabs (Shadowfax Gem Crab, with its own "
                    + "settings) and come back. Offers keep filling either way",
            position = 0,
            section = waitingSection
    )
    default WaitMode whileWaiting() {
        return WaitMode.STAY;
    }

    @Range(min = 1, max = 30)
    @ConfigItem(
            keyName = "idleMinutesBeforeAway",
            name = "Idle before leaving (min)",
            description = "Roughly how long nothing must need doing before leaving; varied a little each time",
            position = 1,
            section = waitingSection
    )
    default int idleMinutesBeforeAway() {
        return 4;
    }

    @Range(min = 5, max = 240)
    @ConfigItem(
            keyName = "awayMinMinutes",
            name = "Away for at least (min)",
            description = "Shortest time away; each trip picks a random length between this and the max",
            position = 2,
            section = waitingSection
    )
    default int awayMinMinutes() {
        return 20;
    }

    @Range(min = 5, max = 240)
    @ConfigItem(
            keyName = "awayMaxMinutes",
            name = "Away for at most (min)",
            description = "Longest time away",
            position = 3,
            section = waitingSection
    )
    default int awayMaxMinutes() {
        return 40;
    }

    @ConfigItem(
            keyName = "capital",
            name = "Capital (gp)",
            description = "Most coins to have in offers and stock at once. Kept in the inventory; topped up from the "
                    + "bank at the Grand Exchange",
            position = 0,
            section = capitalSection
    )
    default int capital() {
        return 25_000_000;
    }

    @ConfigItem(
            keyName = "maxItemPercent",
            name = "Max % per item",
            description = "Most of the capital one flip may use, so one bad item can't sink the stack",
            position = 1,
            section = capitalSection
    )
    @Range(min = 5, max = 100)
    default int maxItemPercent() {
        return 20;
    }

    @ConfigItem(
            keyName = "stopLossPercent",
            name = "Stop-loss %",
            description = "Sell at market if an item's buyers drop this far under what we paid",
            position = 2,
            section = capitalSection
    )
    @Range(min = 2, max = 50)
    default int stopLossPercent() {
        return 8;
    }

    @ConfigItem(
            keyName = "maxHoldHours",
            name = "Max hold (flips, hours)",
            description = "A flip still unsold after this long is sold at the market price, even at a small loss. "
                    + "Dips get four times as long",
            position = 3,
            section = capitalSection
    )
    @Range(min = 1, max = 48)
    default int maxHoldHours() {
        return 4;
    }

    @ConfigItem(
            keyName = "minProfit",
            name = "Min profit per flip (gp)",
            description = "Skip trades expected to make less than this after tax",
            position = 0,
            section = strategySection
    )
    default int minProfit() {
        return 15_000;
    }

    @ConfigItem(
            keyName = "minMarginTenths",
            name = "Min margin (0.1%)",
            description = "Smallest after-tax margin to flip, in tenths of a percent (4 = 0.4%)",
            position = 1,
            section = strategySection
    )
    @Range(min = 1, max = 100)
    default int minMarginTenths() {
        return 4;
    }

    @ConfigItem(
            keyName = "targetMinutes",
            name = "Target flip time (min)",
            description = "Quantities are sized so a flip should buy and sell within about this long",
            position = 2,
            section = strategySection
    )
    @Range(min = 5, max = 480)
    default int targetMinutes() {
        return 45;
    }

    @ConfigItem(
            keyName = "dips",
            name = "Buy dips",
            description = "Also buy items trading well under their 7-day price once the drop has stopped, and sell "
                    + "them as they recover. Slower and riskier than flips",
            position = 3,
            section = strategySection
    )
    default boolean dips() {
        return true;
    }

    @ConfigItem(
            keyName = "maxDipSlots",
            name = "Max dip slots",
            description = "Most GE slots dips may hold at once; the rest stay free for quick flips",
            position = 4,
            section = strategySection
    )
    @Range(min = 0, max = 8)
    default int maxDipSlots() {
        return 2;
    }

    @ConfigItem(
            keyName = "minDipPercent",
            name = "Min dip %",
            description = "How far under its 7-day average an item must be to count as a dip",
            position = 5,
            section = strategySection
    )
    @Range(min = 3, max = 50)
    default int minDipPercent() {
        return 8;
    }

    @ConfigItem(
            keyName = "exclude",
            name = "Never trade",
            description = "Comma-separated item names to leave alone",
            position = 6,
            section = strategySection
    )
    default String exclude() {
        return "";
    }

    @ConfigItem(
            keyName = "mode",
            name = "Mode",
            description = "Scan only shows what it would trade without touching the GE; switch to Trade once its "
                    + "picks look right",
            position = 0,
            section = generalSection
    )
    default Mode mode() {
        return Mode.SCAN_ONLY;
    }

    @ConfigItem(
            keyName = "windDown",
            name = "Wind down",
            description = "Stop buying; sell what's held, then stop. Turn on to cash out cleanly",
            position = 1,
            section = generalSection
    )
    default boolean windDown() {
        return false;
    }

    @ConfigItem(
            keyName = "showOverlay",
            name = "Show overlay",
            description = "Draw the Shadowfax Merchant panel on screen",
            position = 2,
            section = generalSection
    )
    default boolean showOverlay() {
        return true;
    }

    @ConfigItem(
            keyName = STATE_KEY,
            name = "Saved positions",
            description = "Open positions and buy limits, saved between sessions",
            hidden = true
    )
    default String savedState() {
        return "";
    }
}
