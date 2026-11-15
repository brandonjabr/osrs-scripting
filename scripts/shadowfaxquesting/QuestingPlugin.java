package net.runelite.client.plugins.microbot.shadowfaxquesting;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;

@PluginDescriptor(
        name = PluginDescriptor.Default + "Shadowfax Questing",
        description = "Completes 50 quests, from Cook's Assistant to Holy Grail and Fairytale I (members quests in the OSRS Wiki's optimal order), buying quest items on the GE",
        tags = {"quest", "questing", "quests", "f2p", "members", "optimal", "microbot"},
        version = QuestingPlugin.VERSION,
        enabledByDefault = false
)
@Slf4j
public class QuestingPlugin extends Plugin {

    static final String VERSION = "1.1.0";

    @Inject
    private QuestingScript script;

    @Inject
    private QuestingConfig config;

    @Inject
    private QuestingOverlay overlay;

    @Inject
    private OverlayManager overlayManager;

    @Provides
    QuestingConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(QuestingConfig.class);
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
