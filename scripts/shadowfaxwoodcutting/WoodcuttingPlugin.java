package net.runelite.client.plugins.microbot.shadowfaxwoodcutting;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;

@PluginDescriptor(
        name = PluginDescriptor.Default + "Shadowfax Woodcutting",
        description = "Chops any tree at the best known spot, or the best tree for your level on Automatic. Banks or "
                + "shift-drops the logs, picks up nests, and upgrades the axe from the bank or the Grand Exchange",
        tags = {"woodcutting", "wc", "logs", "trees", "axe", "skilling", "microbot"},
        version = WoodcuttingPlugin.VERSION,
        enabledByDefault = false
)
@Slf4j
public class WoodcuttingPlugin extends Plugin {

    static final String VERSION = "1.0.0";

    @Inject
    private WoodcuttingScript script;

    @Inject
    private WoodcuttingConfig config;

    @Inject
    private WoodcuttingOverlay overlay;

    @Inject
    private OverlayManager overlayManager;

    @Provides
    WoodcuttingConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(WoodcuttingConfig.class);
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
