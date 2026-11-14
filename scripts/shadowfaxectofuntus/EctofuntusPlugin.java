package net.runelite.client.plugins.microbot.shadowfaxectofuntus;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;

@PluginDescriptor(
        name = PluginDescriptor.Default + "Shadowfax Ectofuntus",
        description = "Grinds bones, fills buckets with slime, worships at the Ectofuntus and claims the ecto-tokens, "
                + "then banks for more and repeats. Start by the Ectofuntus wearing a ghostspeak amulet",
        tags = {"prayer", "ectofuntus", "ecto", "bones", "tokens", "phasmatys", "microbot"},
        version = EctofuntusPlugin.VERSION,
        enabledByDefault = false
)
@Slf4j
public class EctofuntusPlugin extends Plugin {

    static final String VERSION = "1.1.0";

    @Inject
    private EctofuntusScript script;

    @Inject
    private EctofuntusConfig config;

    @Inject
    private EctofuntusOverlay overlay;

    @Inject
    private OverlayManager overlayManager;

    @Provides
    EctofuntusConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(EctofuntusConfig.class);
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
