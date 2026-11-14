package net.runelite.client.plugins.microbot.shadowfaxboltenchanter;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;

@PluginDescriptor(
        name = PluginDescriptor.Default + "Shadowfax Bolt Enchanter",
        description = "Casts Enchant Crossbow Bolt on the gem-tipped bolts in your inventory; stops when they're gone",
        tags = {"magic", "enchant", "bolts", "crossbow", "afk", "microbot"},
        version = BoltEnchanterPlugin.VERSION,
        enabledByDefault = false
)
@Slf4j
public class BoltEnchanterPlugin extends Plugin {

    static final String VERSION = "1.0.0";

    @Inject
    private BoltEnchanterScript script;

    @Inject
    private BoltEnchanterConfig config;

    @Inject
    private BoltEnchanterOverlay overlay;

    @Inject
    private OverlayManager overlayManager;

    @Provides
    BoltEnchanterConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(BoltEnchanterConfig.class);
    }

    @Override
    protected void startUp() {
        overlayManager.add(overlay);
        script.run(config);
    }

    @Override
    protected void shutDown() {
        script.shutdown();
        overlayManager.remove(overlay);
    }
}
