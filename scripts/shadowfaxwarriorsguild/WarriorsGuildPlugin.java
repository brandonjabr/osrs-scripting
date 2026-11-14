package net.runelite.client.plugins.microbot.shadowfaxwarriorsguild;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.NPC;
import net.runelite.api.events.ActorDeath;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;

@PluginDescriptor(
        name = PluginDescriptor.Default + "Shadowfax Warrior's Guild",
        description = "Animates armour for tokens, then farms the cyclopes for every defender up to dragon, "
                + "showing each one to Kamfreena and moving to the basement after rune",
        tags = {"warriors", "guild", "defender", "cyclops", "tokens", "animated", "armour", "dragon", "microbot"},
        version = WarriorsGuildPlugin.VERSION,
        enabledByDefault = false
)
@Slf4j
public class WarriorsGuildPlugin extends Plugin {

    static final String VERSION = "1.0.0";

    @Inject
    private WarriorsGuildScript script;

    @Inject
    private WarriorsGuildConfig config;

    @Inject
    private WarriorsGuildOverlay overlay;

    @Inject
    private OverlayManager overlayManager;

    @Provides
    WarriorsGuildConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(WarriorsGuildConfig.class);
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

    @Subscribe
    public void onActorDeath(ActorDeath event) {
        if (event.getActor() instanceof NPC) {
            script.onNpcDeath((NPC) event.getActor());
        }
    }
}
