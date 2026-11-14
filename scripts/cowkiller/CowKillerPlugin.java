package net.runelite.client.plugins.microbot.cowkiller;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.ActorDeath;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;

@PluginDescriptor(
        name = PluginDescriptor.Default + "Cow Killer",
        description = "Kills cows in the Lumbridge pen, cooks beef for food, and upgrades scimitars from Zeke with cowhide money",
        tags = {"cow", "combat", "lumbridge", "scimitar", "cooking", "microbot"},
        version = CowKillerPlugin.VERSION,
        enabledByDefault = false
)
@Slf4j
public class CowKillerPlugin extends Plugin {

    static final String VERSION = "1.1.0";

    @Inject
    private CowKillerScript script;

    @Inject
    private CowKillerConfig config;

    @Inject
    private CowKillerStats stats;

    @Inject
    private CowKillerOverlay overlay;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private Client client;

    @Provides
    CowKillerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(CowKillerConfig.class);
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

    // Fires on the client thread, so reading interaction targets here is safe.
    @Subscribe
    public void onActorDeath(ActorDeath event) {
        Actor actor = event.getActor();
        if (!(actor instanceof NPC) || !"Cow".equals(actor.getName())) {
            return;
        }
        Player me = client.getLocalPlayer();
        if (me != null && (actor.getInteracting() == me || me.getInteracting() == actor)) {
            stats.addCowKill();
        }
    }
}
