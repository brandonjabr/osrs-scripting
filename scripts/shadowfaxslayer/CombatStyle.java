package net.runelite.client.plugins.microbot.shadowfaxslayer;

/** The three gear setups the script remembers. */
public enum CombatStyle {
    MELEE("Melee", "gearMelee"),
    RANGED("Ranged", "gearRanged"),
    MAGIC("Magic", "gearMagic");

    private final String label;
    private final String configKey;

    CombatStyle(String label, String configKey) {
        this.label = label;
        this.configKey = configKey;
    }

    public String label() {
        return label;
    }

    /** Where the setup is stored in the plugin's config group. */
    public String configKey() {
        return configKey;
    }

    @Override
    public String toString() {
        return label;
    }
}
