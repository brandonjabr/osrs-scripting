package net.runelite.client.plugins.microbot.shadowfaxwalker;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.coords.WorldPoint;

import java.awt.Color;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * A place the walker can be sent to: a name to search for, a category for the picker's filter chips, extra search
 * words (nicknames like "ge" or "seers") and the tile to walk to.
 */
@Getter
public final class Destination {

    @Getter
    @RequiredArgsConstructor
    public enum Category {
        CITY("Cities", new Color(240, 205, 110)),
        BANK("Banks", new Color(120, 200, 110)),
        SKILLING("Skilling", new Color(110, 180, 240)),
        AGILITY("Agility", new Color(90, 210, 200)),
        RUNECRAFT("Altars", new Color(190, 140, 240)),
        GUILD("Guilds", new Color(230, 150, 70)),
        MINIGAME("Minigames", new Color(240, 110, 130)),
        LANDMARK("Landmarks", new Color(200, 200, 210)),
        SLAYER("Slayer", new Color(220, 90, 90)),
        COORDINATES("Coordinates", new Color(150, 156, 170));

        private final String label;
        private final Color color;
    }

    private final String name;
    private final Category category;
    private final WorldPoint point;
    private final List<String> aliases;
    // Lower-cased name plus aliases, matched against what the user types.
    private final String searchText;

    public Destination(String name, Category category, WorldPoint point, String... aliases) {
        this.name = name;
        this.category = category;
        this.point = point;
        this.aliases = Arrays.asList(aliases);
        this.searchText = (name + " " + String.join(" ", aliases)).toLowerCase(Locale.ROOT);
    }

    /** "3164, 3485, 0" */
    public String coordinates() {
        return point.getX() + ", " + point.getY() + ", " + point.getPlane();
    }

    public String aliasText() {
        return aliases.stream().collect(Collectors.joining(", "));
    }

    @Override
    public String toString() {
        return name;
    }
}
