package net.runelite.client.plugins.microbot.shadowfaxwalker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

@ConfigGroup(WalkerConfig.GROUP)
public interface WalkerConfig extends Config {

    String GROUP = "shadowfaxwalker";
    String RECENT_KEY = "recentDestinations";

    @ConfigSection(
            name = "Walking",
            description = "How close to get and what to do on arrival",
            position = 0
    )
    String walkingSection = "walking";

    @ConfigSection(
            name = "Stamina",
            description = "Drinking stamina potions on the way",
            position = 1
    )
    String staminaSection = "stamina";

    @ConfigSection(
            name = "General",
            description = "Hotkey and overlay",
            position = 2
    )
    String generalSection = "general";

    @ConfigItem(
            keyName = "arrivalDistance",
            name = "Arrival distance",
            description = "Count as arrived within this many tiles of the destination",
            position = 0,
            section = walkingSection
    )
    @Range(min = 0, max = 15)
    default int arrivalDistance() {
        return 3;
    }

    @ConfigItem(
            keyName = "askAgain",
            name = "Ask again on arrival",
            description = "Open the destination picker again after arriving. Off: the plugin turns itself off on arrival",
            position = 1,
            section = walkingSection
    )
    default boolean askAgain() {
        return true;
    }

    @ConfigItem(
            keyName = "useStaminas",
            name = "Use stamina potions",
            description = "Drink stamina potions you carry when run energy gets low on the way. Members worlds only: "
                    + "stamina potions can't be used on free-to-play worlds",
            position = 0,
            section = staminaSection
    )
    default boolean useStaminas() {
        return true;
    }

    @ConfigItem(
            keyName = "staminaAtEnergy",
            name = "Drink at run energy %",
            description = "Drink a stamina potion when run energy drops to this and no stamina effect is active",
            position = 1,
            section = staminaSection
    )
    @Range(min = 5, max = 90)
    default int staminaAtEnergy() {
        return 30;
    }

    @ConfigItem(
            keyName = "pickerHotkey",
            name = "Choose destination hotkey",
            description = "Opens the destination picker at any time, also mid-walk to change course",
            position = 0,
            section = generalSection
    )
    default Keybind pickerHotkey() {
        return new Keybind(KeyEvent.VK_W, InputEvent.ALT_DOWN_MASK);
    }

    @ConfigItem(
            keyName = "showOverlay",
            name = "Show overlay",
            description = "Draw the Shadowfax Walker trip panel on screen",
            position = 1,
            section = generalSection
    )
    default boolean showOverlay() {
        return true;
    }

    @ConfigItem(
            keyName = RECENT_KEY,
            name = "Recent destinations",
            description = "Places walked to recently, newest first",
            hidden = true
    )
    default String recentDestinations() {
        return "";
    }
}
