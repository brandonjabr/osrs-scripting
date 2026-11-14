package net.runelite.client.plugins.microbot.shadowfaxaccountstarter;

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
        name = PluginDescriptor.Default + "Shadowfax Account Starter",
        description = "Starts a fresh account: trains melee on the Lumbridge cows, cooks their beef for food, and "
                + "upgrades scimitars from Zeke with cowhide money",
        tags = {"account", "starter", "cow", "combat", "lumbridge", "scimitar", "cooking", "microbot"},
        version = AccountStarterPlugin.VERSION,
        enabledByDefault = false
)
@Slf4j
public class AccountStarterPlugin extends Plugin {

    static final String VERSION = "1.2.0";

    @Inject
    private AccountStarterScript script;

    @Inject
    private AccountStarterConfig config;

    @Inject
    private AccountStarterStats stats;

    @Inject
    private AccountStarterOverlay overlay;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private Client client;

    @Provides
    AccountStarterConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(AccountStarterConfig.class);
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
