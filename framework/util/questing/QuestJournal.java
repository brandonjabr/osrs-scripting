package net.runelite.client.plugins.microbot.util.questing;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.questhelper.questinfo.QuestHelperQuest;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.misc.Rs2UiHelper;
import net.runelite.client.plugins.microbot.util.tabs.Rs2Tab;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.awt.event.KeyEvent;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Opens a quest's journal in game, so Quest Helper re-syncs the conditions it reads from the journal text (items
 * already used, people already spoken to, "if you already have, open the quest journal" steps). Quest state itself
 * comes from varbits and needs no journal, but the journal is what a player would check after a break too.
 */
@Slf4j
public final class QuestJournal {

    private QuestJournal() {
    }

    public static boolean isOpen() {
        return Rs2Widget.isWidgetVisible(InterfaceID.Questjournal.TEXTLAYER);
    }

    /**
     * Opens the quest's journal from the quest list, lets Quest Helper read it, and closes it again. Best effort:
     * false if the list or the quest's entry couldn't be found or the journal didn't open.
     */
    public static boolean read(QuestHelperQuest quest) {
        if (!isOpen()) {
            Rs2Tab.switchToQuestTab();
            if (!sleepUntil(() -> Rs2Widget.isWidgetVisible(InterfaceID.Questlist.LIST), 2_000)) {
                // The quest tab can be on its character summary or diary page: the quest list button is labelled.
                Rs2Widget.clickWidget("Quest List", false);
                if (!sleepUntil(() -> Rs2Widget.isWidgetVisible(InterfaceID.Questlist.LIST), 2_000)) {
                    log.info("[Questing] Couldn't open the quest list to read the {} journal", quest.getName());
                    return false;
                }
            }
            Widget entry = Microbot.getClientThread().runOnClientThreadOptional(() -> find(quest.getName()))
                    .orElse(null);
            if (entry == null) {
                log.info("[Questing] {} isn't in the quest list; skipping the journal", quest.getName());
                return false;
            }
            Microbot.status = "Reading the " + quest.getName() + " journal";
            Rs2Widget.clickWidget(entry);
            if (!sleepUntil(QuestJournal::isOpen, 3_000)) {
                log.info("[Questing] The {} journal didn't open", quest.getName());
                return false;
            }
        }
        // Quest Helper reads the journal text on the following ticks.
        sleep(1_200, 1_800);
        close();
        return true;
    }

    public static void close() {
        if (!isOpen()) return;
        if (!Rs2Widget.clickWidget(InterfaceID.Questjournal.CLOSE)) Rs2Keyboard.keyPress(KeyEvent.VK_ESCAPE);
        sleepUntil(() -> !isOpen(), 1_800);
        Rs2Tab.switchToInventoryTab();
    }

    private static Widget find(String questName) {
        Widget list = Microbot.getClient().getWidget(InterfaceID.Questlist.LIST);
        if (list == null) return null;
        Widget[] children = list.getDynamicChildren();
        if (children == null) return null;
        for (Widget child : children) {
            String text = child == null ? null : Rs2UiHelper.stripColTags(child.getText());
            if (text != null && text.trim().equalsIgnoreCase(questName)) return child;
        }
        return null;
    }
}
