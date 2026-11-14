package net.runelite.client.plugins.microbot.shadowfaxbankorganizer;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;

@PluginDescriptor(
        name = PluginDescriptor.Default + "Shadowfax Bank Organizer",
        description = "Plans and lays out the whole bank tab by tab, sets kept together, placeholders included",
        tags = {"bank", "organize", "organizer", "tabs", "sort", "placeholders", "microbot"},
        version = BankOrganizerPlugin.VERSION,
        enabledByDefault = false
)
@Slf4j
public class BankOrganizerPlugin extends Plugin {

    static final String VERSION = "2.0.0";

    @Inject
    private BankOrganizerScript script;

    @Inject
    private BankOrganizerConfig config;

    @Inject
    private BankOrganizerOverlay overlay;

    @Inject
    private OverlayManager overlayManager;

    @Provides
    BankOrganizerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(BankOrganizerConfig.class);
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
