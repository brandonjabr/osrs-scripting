package net.runelite.client.plugins.microbot.shadowfaxslayer;

import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.microbot.util.equipment.GearSetup;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.EnumMap;
import java.util.Map;

/** The three remembered setups, kept in the plugin's config group so they survive restarts. */
@Singleton
public class GearStore {

    private final ConfigManager configManager;
    private final Map<CombatStyle, GearSetup> cache = new EnumMap<>(CombatStyle.class);

    @Inject
    GearStore(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public synchronized GearSetup get(CombatStyle style) {
        return cache.computeIfAbsent(style,
                s -> GearSetup.parse(configManager.getConfiguration(SlayerConfig.GROUP, s.configKey())));
    }

    public synchronized boolean has(CombatStyle style) {
        return !get(style).isEmpty();
    }

    public synchronized boolean hasAny() {
        for (CombatStyle s : CombatStyle.values()) {
            if (has(s)) return true;
        }
        return false;
    }

    /** Records what's worn now as the setup for {@code style}; magic also keeps the runes carried and autocast. */
    public synchronized GearSetup captureNow(CombatStyle style) {
        boolean magic = style == CombatStyle.MAGIC;
        GearSetup setup = GearSetup.captureWorn(magic ? GearSetup::isRuneSupply : null, magic);
        cache.put(style, setup);
        configManager.setConfiguration(SlayerConfig.GROUP, style.configKey(), setup.serialize());
        return setup;
    }

    public synchronized void clear(CombatStyle style) {
        cache.put(style, GearSetup.empty());
        configManager.unsetConfiguration(SlayerConfig.GROUP, style.configKey());
    }

    /** Re-reads from the config on next use (the setup window may have changed them). */
    public synchronized void invalidate() {
        cache.clear();
    }
}
