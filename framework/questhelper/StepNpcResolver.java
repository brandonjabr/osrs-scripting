package net.runelite.client.plugins.microbot.questhelper;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.NPCComposition;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.questhelper.steps.NpcStep;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Finds the NPCs an {@link NpcStep} is about, even when Quest Helper's own list is empty.
 * <p>
 * An NpcStep only tracks NPCs whose id matches exactly, captured as they spawn. That list stays empty when the
 * port uses a different id for the same character (Priest in Peril's King Roald points at his cutscene copy, 1399,
 * instead of the castle one, 5215), when an NPC transforms into a variant id, or when it spawned in a way the step
 * didn't see. The executor then stood at the step's spot doing nothing. This falls back, in order, to:
 * <ol>
 *   <li>Quest Helper's tracked NPCs;</li>
 *   <li>any loaded NPC whose id is one of the step's ids;</li>
 *   <li>any loaded NPC with the same name as one of the step's ids (or the step's own NPC name), near the step's
 *       spot.</li>
 * </ol>
 * The name fallback is what fixes a wrong id for good, since a character keeps its name across all its ids.
 */
@Slf4j
public final class StepNpcResolver {

    // Name fallback only looks this far from the step's spot (or from the player when the step has none).
    private static final int NAME_SEARCH_RADIUS = 20;
    private static final Set<String> loggedFallbacks = ConcurrentHashMap.newKeySet();

    private StepNpcResolver() {
    }

    /** The step's NPCs, nearest to the player first. Empty only when none of them is loaded. */
    public static List<Rs2NpcModel> resolve(NpcStep step) {
        if (step == null) return List.of();

        List<Rs2NpcModel> tracked = Microbot.getClientThread().runOnClientThreadOptional(() ->
                step.getNpcs().stream().filter(Objects::nonNull).map(Rs2NpcModel::new)
                        .collect(Collectors.toList())).orElse(List.of());
        if (!tracked.isEmpty()) return tracked;

        List<Integer> ids = step.allIds();
        List<Rs2NpcModel> byId = Microbot.getRs2NpcCache().query()
                .where(n -> ids.contains(n.getId()))
                .toListOnClientThread();
        if (!byId.isEmpty()) return nearestFirst(byId);

        Set<String> names = names(step);
        if (names.isEmpty()) return List.of();
        WorldPoint anchor = step.getDefinedPoint() == null ? null : step.getDefinedPoint().getWorldPoint();
        WorldPoint centre = anchor != null ? anchor : Rs2Player.getWorldLocation();
        List<Rs2NpcModel> byName = Microbot.getRs2NpcCache().query()
                .where(n -> {
                    String name = n.getName();
                    return name != null && names.contains(name.toLowerCase(Locale.ROOT));
                })
                .toListOnClientThread()
                .stream()
                .filter(n -> centre == null || n.getWorldLocation() == null
                        || n.getWorldLocation().distanceTo(centre) <= NAME_SEARCH_RADIUS)
                .collect(Collectors.toList());
        if (!byName.isEmpty()) {
            Rs2NpcModel first = nearestFirst(byName).get(0);
            String key = ids + ">" + first.getId();
            if (loggedFallbacks.add(key)) {
                log.info("[QuestHelper] Step \"{}\" tracks NPC id(s) {}, none loaded; using {} (id {}) by name",
                        firstLine(step), ids, first.getName(), first.getId());
            }
        }
        return nearestFirst(byName);
    }

    /** Lower-case names of the step's NPCs: its own NPC name if set, plus each id's definition name. */
    public static Set<String> names(NpcStep step) {
        Set<String> names = new LinkedHashSet<>();
        if (step == null) return names;
        if (step.getNpcName() != null) names.add(step.getNpcName().toLowerCase(Locale.ROOT));
        for (int id : step.allIds()) {
            String name = Microbot.getClientThread().runOnClientThreadOptional(() -> {
                NPCComposition def = Microbot.getClient().getNpcDefinition(id);
                return def == null ? null : def.getName();
            }).orElse(null);
            if (name != null && !name.isEmpty() && !"null".equalsIgnoreCase(name)) {
                names.add(name.toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private static List<Rs2NpcModel> nearestFirst(List<Rs2NpcModel> npcs) {
        WorldPoint me = Rs2Player.getWorldLocation();
        if (me == null) return npcs;
        List<Rs2NpcModel> sorted = new ArrayList<>(npcs);
        sorted.sort(Comparator.comparingInt(n -> n.getWorldLocation() == null ? Integer.MAX_VALUE
                : n.getWorldLocation().distanceTo(me)));
        return sorted;
    }

    private static String firstLine(NpcStep step) {
        List<String> text = step.getText();
        return text == null || text.isEmpty() ? "?" : text.get(0);
    }
}
