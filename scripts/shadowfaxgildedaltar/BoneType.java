package net.runelite.client.plugins.microbot.shadowfaxgildedaltar;

import net.runelite.api.gameval.ItemID;

/** Bones worth offering on a gilded altar, with their base Prayer xp per bone (the altar gives 350% of this). */
public enum BoneType {
    BONES("Bones", ItemID.BONES, 4.5),
    BIG_BONES("Big bones", ItemID.BIG_BONES, 15),
    BABYDRAGON_BONES("Babydragon bones", ItemID.BABYDRAGON_BONES, 30),
    WYRM_BONES("Wyrm bones", ItemID.WYRM_BONES, 50),
    WYVERN_BONES("Wyvern bones", ItemID.WYVERN_BONES, 72),
    DRAGON_BONES("Dragon bones", ItemID.DRAGON_BONES, 72),
    DRAKE_BONES("Drake bones", ItemID.DRAKE_BONES, 80),
    LAVA_DRAGON_BONES("Lava dragon bones", ItemID.LAVA_DRAGON_BONES, 85),
    HYDRA_BONES("Hydra bones", ItemID.HYDRA_BONES, 110),
    SUPERIOR_DRAGON_BONES("Superior dragon bones", ItemID.DRAGON_BONES_SUPERIOR, 150);

    final String displayName;
    final int itemId;
    final double baseXp;

    BoneType(String displayName, int itemId, double baseXp) {
        this.displayName = displayName;
        this.itemId = itemId;
        this.baseXp = baseXp;
    }

    /** Xp per bone on a gilded altar with both burners lit. */
    double altarXp() {
        return baseXp * 3.5;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
