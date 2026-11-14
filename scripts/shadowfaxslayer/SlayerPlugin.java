package net.runelite.client.plugins.microbot.shadowfaxslayer;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.HotkeyListener;

import javax.inject.Inject;

@PluginDescriptor(
        name = PluginDescriptor.Default + "Shadowfax Slayer",
        description = "Gets slayer tasks from Turael, Vannaka or Nieve and does them at the easiest-to-reach spots with "
                + "your saved melee, ranged or magic gear, buying slayer equipment from the master as tasks need it",
        tags = {"slayer", "combat", "turael", "vannaka", "nieve", "tasks", "microbot"},
        version = SlayerPlugin.VERSION,
        enabledByDefault = false
)
@Slf4j
public class SlayerPlugin extends Plugin {

    static final String VERSION = "1.0.0";

    @Inject
    private SlayerScript script;

    @Inject
    private SlayerConfig config;

    @Inject
    private SlayerOverlay overlay;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private KeyManager keyManager;

    private final HotkeyListener gearHotkey = new HotkeyListener(() -> config.gearHotkey()) {
        @Override
        public void hotkeyPressed() {
            script.openGearSetup();
        }
    };

    @Provides
    SlayerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(SlayerConfig.class);
    }

    @Override
    protected void startUp() {
        overlayManager.add(overlay);
        keyManager.registerKeyListener(gearHotkey);
        script.run(config);
    }

    @Override
    protected void shutDown() {
        keyManager.unregisterKeyListener(gearHotkey);
        script.shutdown();
        overlayManager.remove(overlay);
    }
}
