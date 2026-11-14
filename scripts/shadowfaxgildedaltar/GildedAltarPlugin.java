package net.runelite.client.plugins.microbot.shadowfaxgildedaltar;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;

@PluginDescriptor(
        name = PluginDescriptor.Default + "Shadowfax Gilded Altar",
        description = "Offers bones on a gilded altar in another player's house, entered from the House Advertisement board",
        tags = {"prayer", "gilded", "altar", "bones", "poh", "house", "microbot"},
        version = GildedAltarPlugin.VERSION,
        enabledByDefault = false
)
@Slf4j
public class GildedAltarPlugin extends Plugin {

    static final String VERSION = "1.0.0";

    @Inject
    private GildedAltarScript script;

    @Inject
    private GildedAltarConfig config;

    @Inject
    private GildedAltarOverlay overlay;

    @Inject
    private OverlayManager overlayManager;

    @Provides
    GildedAltarConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(GildedAltarConfig.class);
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
