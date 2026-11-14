package net.runelite.client.plugins.microbot.shadowfaxwalker;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.HotkeyListener;

import javax.inject.Inject;

@PluginDescriptor(
        name = PluginDescriptor.Default + "Shadowfax Walker",
        description = "Asks where to go, with search-as-you-type over common places, and walks there",
        tags = {"walker", "walk", "travel", "teleport", "web walker", "microbot"},
        version = WalkerPlugin.VERSION,
        enabledByDefault = false
)
@Slf4j
public class WalkerPlugin extends Plugin {

    static final String VERSION = "1.0.0";

    @Inject
    private WalkerScript script;

    @Inject
    private WalkerConfig config;

    @Inject
    private WalkerOverlay overlay;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private KeyManager keyManager;

    private final HotkeyListener pickerHotkey = new HotkeyListener(() -> config.pickerHotkey()) {
        @Override
        public void hotkeyPressed() {
            script.openPicker(true);
        }
    };

    @Provides
    WalkerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(WalkerConfig.class);
    }

    @Override
    protected void startUp() {
        overlayManager.add(overlay);
        keyManager.registerKeyListener(pickerHotkey);
        script.run(config);
    }

    @Override
    protected void shutDown() {
        keyManager.unregisterKeyListener(pickerHotkey);
        script.shutdown();
        overlayManager.remove(overlay);
    }

    @Subscribe
    public void onGameTick(GameTick tick) {
        script.onGameTick();
    }
}
