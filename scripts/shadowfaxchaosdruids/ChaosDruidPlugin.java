package net.runelite.client.plugins.microbot.shadowfaxchaosdruids;

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
        name = PluginDescriptor.Default + "Shadowfax Chaos Druids",
        description = "Kills chaos druids in Taverley Dungeon, loots valuable herbs and banks at Falador west",
        tags = {"chaos", "druid", "herbs", "taverley", "falador", "combat", "money", "microbot"},
        version = ChaosDruidPlugin.VERSION,
        enabledByDefault = false
)
@Slf4j
public class ChaosDruidPlugin extends Plugin {

    static final String VERSION = "1.0.0";

    @Inject
    private ChaosDruidScript script;

    @Inject
    private ChaosDruidConfig config;

    @Inject
    private ChaosDruidStats stats;

    @Inject
    private ChaosDruidOverlay overlay;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private Client client;

    @Provides
    ChaosDruidConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(ChaosDruidConfig.class);
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
        if (!(actor instanceof NPC) || !ChaosDruidScript.CHAOS_DRUID.equals(actor.getName())) {
            return;
        }
        Player me = client.getLocalPlayer();
        if (me != null && (actor.getInteracting() == me || me.getInteracting() == actor)) {
            stats.addKill();
        }
    }
}
