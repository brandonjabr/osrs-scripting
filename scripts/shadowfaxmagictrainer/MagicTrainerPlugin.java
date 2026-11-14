package net.runelite.client.plugins.microbot.shadowfaxmagictrainer;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;

@PluginDescriptor(
        name = PluginDescriptor.Default + "Shadowfax Magic Trainer",
        description = "Splashes the caged Monk of Zamorak in Varrock Palace with the best curse spell available, buying runes on the GE",
        tags = {"magic", "splash", "splashing", "curse", "varrock", "monk", "afk", "microbot"},
        version = MagicTrainerPlugin.VERSION,
        enabledByDefault = false
)
@Slf4j
public class MagicTrainerPlugin extends Plugin {

    static final String VERSION = "1.0.0";

    @Inject
    private MagicTrainerScript script;

    @Inject
    private MagicTrainerConfig config;

    @Inject
    private MagicTrainerOverlay overlay;

    @Inject
    private OverlayManager overlayManager;

    @Provides
    MagicTrainerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(MagicTrainerConfig.class);
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
