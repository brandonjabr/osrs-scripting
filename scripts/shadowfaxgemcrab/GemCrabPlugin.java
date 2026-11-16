package net.runelite.client.plugins.microbot.shadowfaxgemcrab;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.NPC;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.NpcSpawned;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;

import javax.inject.Inject;

@PluginDescriptor(
        name = PluginDescriptor.Default + "Shadowfax Gem Crab",
        description = "Fights the Gemstone Crab with melee, ranged or magic, mines its shell and follows it through the "
                + "caves; boosting potions, imbued and saturated hearts, balanced melee styles, ranged styles and autocast",
        tags = {"gem", "gemstone", "crab", "varlamore", "tlati", "combat", "afk", "ranged", "magic", "imbued heart", "microbot"},
        version = GemCrabPlugin.VERSION,
        enabledByDefault = false
)
@Slf4j
public class GemCrabPlugin extends Plugin {

    static final String VERSION = "1.2.0";

    @Inject
    private GemCrabScript script;

    @Inject
    private GemCrabConfig config;

    @Inject
    private GemCrabOverlay overlay;

    @Inject
    private OverlayManager overlayManager;

    @Provides
    GemCrabConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(GemCrabConfig.class);
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

    // Game messages only, so a player typing the burrow message in chat can't fake one.
    @Subscribe
    public void onChatMessage(ChatMessage event) {
        ChatMessageType type = event.getType();
        if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM && type != ChatMessageType.MESBOX) {
            return;
        }
        script.onGameMessage(Text.removeTags(event.getMessage()));
    }

    @Subscribe
    public void onNpcSpawned(NpcSpawned event) {
        NPC npc = event.getNpc();
        if (npc.getId() == GemCrabScript.CRAB_ID) {
            script.onCrabSpawned();
        } else if (npc.getId() == GemCrabScript.SHELL_ID) {
            script.onShellSpawned(npc.getWorldLocation());
        }
    }
}
