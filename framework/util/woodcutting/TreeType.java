package net.runelite.client.plugins.microbot.util.woodcutting;

import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

/**
 * Choppable trees, with the in-game object names (several: e.g. "Oak" was renamed "Oak tree", and dead and
 * evergreen trees give regular logs), the log they give and the Woodcutting level they need.
 */
public enum TreeType {
    TREE("Tree", ItemID.LOGS, 1, false, "Tree", "Dead tree", "Evergreen tree", "Evergreen"),
    OAK("Oak", ItemID.OAK_LOGS, 15, false, "Oak tree", "Oak"),
    WILLOW("Willow", ItemID.WILLOW_LOGS, 30, false, "Willow tree", "Willow"),
    TEAK("Teak", ItemID.TEAK_LOGS, 35, true, "Teak tree", "Teak"),
    MAPLE("Maple", ItemID.MAPLE_LOGS, 45, true, "Maple tree"),
    YEW("Yew", ItemID.YEW_LOGS, 60, false, "Yew tree", "Yew"),
    MAGIC("Magic", ItemID.MAGIC_LOGS, 75, true, "Magic tree");

    private final String displayName;
    private final int logId;
    private final int level;
    private final boolean members;
    private final String[] objectNames;

    TreeType(String displayName, int logId, int level, boolean members, String... objectNames) {
        this.displayName = displayName;
        this.logId = logId;
        this.level = level;
        this.members = members;
        this.objectNames = objectNames;
    }

    public String displayName() {
        return displayName;
    }

    public int logId() {
        return logId;
    }

    public int level() {
        return level;
    }

    public boolean members() {
        return members;
    }

    public String[] objectNames() {
        return objectNames.clone();
    }

    public boolean matchesName(String name) {
        if (name == null) return false;
        for (String n : objectNames) {
            if (n.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    public boolean levelMet() {
        return Rs2Player.getRealSkillLevel(Skill.WOODCUTTING) >= level;
    }
}
