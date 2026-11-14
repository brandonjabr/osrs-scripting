package net.runelite.client.plugins.microbot.shadowfaxbankorganizer;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(BankOrganizerConfig.GROUP)
public interface BankOrganizerConfig extends Config {

    String GROUP = "shadowfaxbankorganizer";

    @ConfigItem(
            keyName = "info",
            name = "How it works",
            description = "Open a bank (or stand near one) and turn the plugin on. It lists every slot (placeholders "
                    + "included) and plans the layout first; the plan is written to the log row by row. Then it "
                    + "collapses your tabs and builds: Runes & teleports, Weapons & armour, Food & potions, Skilling, "
                    + "Quest items, Misc. Inside a tab, sections come in a fixed order and armour sets, weapon tiers, "
                    + "runes, herbs, ores... sit together, tier by tier. Empty tabs are left out. It stops by "
                    + "itself when done",
            position = 0
    )
    default String info() {
        return "Tabs: Runes & teleports, Weapons & armour, Food & potions, Skilling, Quest items, Misc";
    }

    @ConfigItem(
            keyName = "showOverlay",
            name = "Show overlay",
            description = "Draw the Shadowfax Bank Organizer progress panel on screen",
            position = 1
    )
    default boolean showOverlay() {
        return true;
    }
}
