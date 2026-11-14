package net.runelite.client.plugins.microbot.shadowfaxhillgiants;

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
        name = PluginDescriptor.Default + "Shadowfax Hill Giants",
        description = "Kills hill giants in Edgeville Dungeon via the brass key shed, buries big bones and banks at Varrock west",
        tags = {"hill", "giant", "big bones", "prayer", "brass key", "edgeville", "varrock", "combat", "microbot"},
        version = HillGiantPlugin.VERSION,
        enabledByDefault = false
)
@Slf4j
public class HillGiantPlugin extends Plugin {

    static final String VERSION = "1.0.0";

    @Inject
    private HillGiantScript script;

    @Inject
    private HillGiantConfig config;

    @Inject
    private HillGiantStats stats;

    @Inject
    private HillGiantOverlay overlay;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private Client client;

    @Provides
    HillGiantConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(HillGiantConfig.class);
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
        if (!(actor instanceof NPC) || !HillGiantScript.HILL_GIANT.equals(actor.getName())) {
            return;
        }
        Player me = client.getLocalPlayer();
        if (me != null && (actor.getInteracting() == me || me.getInteracting() == actor)) {
            stats.addKill();
        }
    }
}
