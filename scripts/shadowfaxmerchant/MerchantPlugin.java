package net.runelite.client.plugins.microbot.shadowfaxmerchant;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;

@PluginDescriptor(
        name = PluginDescriptor.Default + "Shadowfax Merchant",
        description = "Flips and buys dips on the Grand Exchange using live wiki prices",
        tags = {"merch", "merching", "flip", "flipping", "grand exchange", "ge", "money", "microbot"},
        version = MerchantPlugin.VERSION,
        enabledByDefault = false
)
@Slf4j
public class MerchantPlugin extends Plugin {

    static final String VERSION = "1.0.0";

    @Inject
    private MerchantScript script;

    @Inject
    private MerchantConfig config;

    @Inject
    private MerchantOverlay overlay;

    @Inject
    private OverlayManager overlayManager;

    @Provides
    MerchantConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(MerchantConfig.class);
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
