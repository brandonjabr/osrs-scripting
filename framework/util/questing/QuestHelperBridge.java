package net.runelite.client.plugins.microbot.util.questing;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.QuestState;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.questhelper.QuestHelperPlugin;
import net.runelite.client.plugins.microbot.questhelper.questhelpers.QuestHelper;
import net.runelite.client.plugins.microbot.questhelper.questinfo.QuestHelperQuest;
import net.runelite.client.plugins.microbot.questhelper.steps.QuestStep;

/**
 * Thin, thread-safe access to Microbot's Quest Helper plugin, which owns every quest's step graph, item lists and
 * progress varbits. A questing script drives the steps itself, so while it runs this bridge:
 * <ul>
 *   <li>turns the Quest Helper plugin on if needed,</li>
 *   <li>switches off Quest Helper's own auto-player ({@code questhelper.TurnOn}) so two drivers never fight,</li>
 *   <li>turns on "Show Puzzle Solutions" ({@code questhelper.solvePuzzles}) so puzzle steps expose real answers,</li>
 * </ul>
 * and {@link #restore(ConfigManager)} puts the user's settings back afterwards.
 */
@Slf4j
public final class QuestHelperBridge {

    private static final String GROUP = "questhelper";
    private static final String AUTO_PLAY_KEY = "TurnOn";
    private static final String PUZZLES_KEY = "solvePuzzles";

    private static String savedAutoPlay;
    private static String savedPuzzles;

    private QuestHelperBridge() {
    }

    public static QuestHelperPlugin plugin() {
        return Microbot.getPlugin(QuestHelperPlugin.class);
    }

    /** Prepares Quest Helper for scripted use. Returns false while the plugin is still starting. */
    public static boolean ensureReady(ConfigManager configManager) {
        QuestHelperPlugin plugin = plugin();
        if (plugin == null) return false;
        if (!Microbot.isPluginEnabled(plugin)) {
            log.info("[Questing] Turning on the Quest Helper plugin");
            Microbot.startPlugin(plugin);
            return false;
        }
        if (savedAutoPlay == null) {
            savedAutoPlay = String.valueOf(configManager.getConfiguration(GROUP, AUTO_PLAY_KEY));
            savedPuzzles = String.valueOf(configManager.getConfiguration(GROUP, PUZZLES_KEY));
        }
        configManager.setConfiguration(GROUP, AUTO_PLAY_KEY, false);
        configManager.setConfiguration(GROUP, PUZZLES_KEY, true);
        return true;
    }

    /** Puts back the user's Quest Helper settings changed by {@link #ensureReady(ConfigManager)}. */
    public static void restore(ConfigManager configManager) {
        if (savedAutoPlay == null) return;
        restoreKey(configManager, AUTO_PLAY_KEY, savedAutoPlay);
        restoreKey(configManager, PUZZLES_KEY, savedPuzzles);
        savedAutoPlay = null;
        savedPuzzles = null;
    }

    private static void restoreKey(ConfigManager configManager, String key, String value) {
        if (value == null || "null".equals(value)) {
            configManager.unsetConfiguration(GROUP, key);
        } else {
            configManager.setConfiguration(GROUP, key, value);
        }
    }

    public static QuestHelper selectedHelper() {
        QuestHelperPlugin plugin = plugin();
        return plugin == null ? null : plugin.getSelectedQuest();
    }

    public static boolean isSelected(QuestHelperQuest quest) {
        QuestHelper selected = selectedHelper();
        return selected != null && selected.getQuest() == quest;
    }

    /** Makes {@code quest} the active helper, which starts step tracking. */
    public static boolean select(QuestHelperQuest quest) {
        QuestHelperPlugin plugin = plugin();
        return plugin != null && plugin.startQuestHelper(quest.getName());
    }

    /** Stops the active helper, if any. */
    public static void deselect() {
        QuestHelperPlugin plugin = plugin();
        if (plugin == null || plugin.getSelectedQuest() == null) return;
        Microbot.getClientThread().runOnClientThreadOptional(() -> {
            plugin.getQuestManager().shutDownQuest(true);
            return true;
        });
    }

    /** The step the player should do right now for the selected helper, resolved through conditional steps. */
    public static QuestStep activeStep() {
        QuestHelper selected = selectedHelper();
        if (selected == null || selected.getCurrentStep() == null) return null;
        return Microbot.getClientThread().runOnClientThreadOptional(() -> selected.getCurrentStep().getActiveStep())
                .orElse(null);
    }

    public static QuestState state(QuestHelperQuest quest) {
        return Microbot.getClientThread()
                .runOnClientThreadOptional(() -> quest.getState(Microbot.getClient()))
                .orElse(QuestState.NOT_STARTED);
    }

    public static boolean isFinished(QuestHelperQuest quest) {
        return state(quest) == QuestState.FINISHED;
    }

    /** Builds a helper's requirement objects without starting it, so its item and level lists can be read. */
    public static QuestHelper initializedHelper(QuestHelperQuest quest) {
        QuestHelper helper = quest.getQuestHelper();
        Microbot.getClientThread().runOnClientThreadOptional(() -> {
            helper.initializeRequirements();
            return true;
        });
        return helper;
    }
}
