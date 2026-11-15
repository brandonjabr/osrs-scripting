package net.runelite.client.plugins.microbot.shadowfaxquesting;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GraphicsObject;
import net.runelite.api.NPCComposition;
import net.runelite.api.Projectile;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.questhelper.questinfo.QuestHelperQuest;
import net.runelite.client.plugins.microbot.util.questing.DialogueAnswer;
import net.runelite.client.plugins.microbot.util.questing.QuestContext;
import net.runelite.client.plugins.microbot.util.questing.QuestDefinition;
import net.runelite.client.plugins.microbot.util.questing.StepOverride;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.shopping.ItemNeed;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * The quests Shadowfax Questing knows, in the order it does them: quick novice quests first so quest points build up
 * for Black Knights' Fortress (12 QP) and Below Ice Mountain (16 QP), then the members quests.
 * <p>
 * Quest Helper already supplies each quest's steps, items and requirements; entries here only add what makes a bot
 * faster or safer: items to buy instead of gather, helper items to leave out, food and a combat level gate for quests
 * with fights, and step overrides where the generic executor needs help.
 * <p>
 * Not included: Shield of Arrav, which needs a second player from the other gang.
 */
public final class QuestCatalog {

    /** One catalog row: the quest and the config toggle that includes it. */
    public static final class Entry {
        public final QuestDefinition definition;
        private final Predicate<QuestingConfig> enabled;

        Entry(QuestDefinition definition, Predicate<QuestingConfig> enabled) {
            this.definition = definition;
            this.enabled = enabled;
        }

        public boolean isEnabled(QuestingConfig config) {
            return enabled.test(config);
        }
    }

    private QuestCatalog() {
    }

    public static final List<Entry> ENTRIES = List.of(
            entry(QuestingConfig::cooksAssistant, QuestDefinition.builder(QuestHelperQuest.COOKS_ASSISTANT)
                    .label("Cook's Assistant")
                    .notes("Egg, flour and milk are bought instead of gathered.")
                    .build()),
            entry(QuestingConfig::sheepShearer, QuestDefinition.builder(QuestHelperQuest.SHEEP_SHEARER)
                    .label("Sheep Shearer")
                    .gatherInGame()
                    .override(ShearSheep.OVERRIDE)
                    .notes("Picks up the shears, shears 20 sheep and spins the wool at Lumbridge Castle; nothing is bought.")
                    .build()),
            entry(QuestingConfig::romeoAndJuliet, QuestDefinition.builder(QuestHelperQuest.ROMEO__JULIET)
                    .label("Romeo & Juliet")
                    .build()),
            entry(QuestingConfig::restlessGhost, QuestDefinition.builder(QuestHelperQuest.THE_RESTLESS_GHOST)
                    .label("The Restless Ghost")
                    .bringFood()
                    .notes("A level 13 skeleton appears when the skull is taken.")
                    .build()),
            entry(QuestingConfig::runeMysteries, QuestDefinition.builder(QuestHelperQuest.RUNE_MYSTERIES)
                    .label("Rune Mysteries")
                    .build()),
            entry(QuestingConfig::impCatcher, QuestDefinition.builder(QuestHelperQuest.IMP_CATCHER)
                    .label("Imp Catcher")
                    .notes("All four beads are bought instead of hunting imps.")
                    .build()),
            entry(QuestingConfig::doricsQuest, QuestDefinition.builder(QuestHelperQuest.DORICS_QUEST)
                    .label("Doric's Quest")
                    .build()),
            entry(QuestingConfig::witchsPotion, QuestDefinition.builder(QuestHelperQuest.WITCHS_POTION)
                    .label("Witch's Potion")
                    // Burnt meat barely trades on the GE; cooked meat does, and burning it is one click.
                    .substitute(ItemID.BURNT_MEAT, ItemNeed.of(ItemID.COOKED_MEAT, "Cooked meat", 1))
                    .override(BurnMeat.OVERRIDE)
                    .notes("Buys cooked meat and burns it at Hetty's; the rat's tail comes from killing a rat.")
                    .build()),
            entry(QuestingConfig::goblinDiplomacy, QuestDefinition.builder(QuestHelperQuest.GOBLIN_DIPLOMACY)
                    .label("Goblin Diplomacy")
                    .notes("Buys three goblin mail and both dyes, so no crate searching.")
                    .build()),
            entry(QuestingConfig::ernestTheChicken, QuestDefinition.builder(QuestHelperQuest.ERNEST_THE_CHICKEN)
                    .label("Ernest the Chicken")
                    .bringFood()
                    .extra(ItemNeed.of(ItemID.SPADE, "Spade", 1))
                    .notes("A level 22 skeleton and dead trees can attack in the manor. A bought spade skips fetching one; "
                            + "the lever puzzle uses Quest Helper's solution.")
                    .build()),
            entry(QuestingConfig::xMarksTheSpot, QuestDefinition.builder(QuestHelperQuest.X_MARKS_THE_SPOT)
                    .label("X Marks the Spot")
                    .build()),
            entry(QuestingConfig::misthalinMystery, QuestDefinition.builder(QuestHelperQuest.MISTHALIN_MYSTERY)
                    .label("Misthalin Mystery")
                    .override(MirrorPuzzle.OVERRIDE)
                    .notes("Piano and gem puzzles use Quest Helper's solutions. The mirror puzzle watches for the "
                            + "killer's shadow on a wardrobe and pushes the mirror to face it.")
                    .build()),
            entry(QuestingConfig::idesOfMilk, QuestDefinition.builder(QuestHelperQuest.THE_IDES_OF_MILK)
                    .label("The Ides of Milk")
                    .fight(15)
                    .notes("Has a fight against a bull; the wiki recommends combat level 15.")
                    .build()),
            entry(QuestingConfig::piratesTreasure, QuestDefinition.builder(QuestHelperQuest.PIRATES_TREASURE)
                    .label("Pirate's Treasure")
                    .extra(ItemNeed.of(ItemID.WHITE_APRON, "White apron", 1))
                    .notes("Bananas and a white apron are bought; the rum still has to be smuggled off Karamja.")
                    .build()),
            entry(QuestingConfig::corsairCurse, QuestDefinition.builder(QuestHelperQuest.THE_CORSAIR_CURSE)
                    .label("The Corsair Curse")
                    .fight(20)
                    .extra(ItemNeed.of(ItemID.SPADE, "Spade", 1))
                    .extra(ItemNeed.of(ItemID.TINDERBOX, "Tinderbox", 1))
                    .notes("Ends with a fight against Ithoi the Navigator (level 35). A bought spade and tinderbox skip "
                            + "picking them up on the island.")
                    .build()),
            entry(QuestingConfig::vampyreSlayer, QuestDefinition.builder(QuestHelperQuest.VAMPYRE_SLAYER)
                    .label("Vampyre Slayer")
                    .fight(20)
                    .extra(ItemNeed.of(ItemID.GARLIC, "Garlic", 1))
                    .notes("Fight against Count Draynor (level 34); stake and hammer stay in the inventory.")
                    .build()),
            entry(QuestingConfig::princeAliRescue, QuestDefinition.builder(QuestHelperQuest.PRINCE_ALI_RESCUE)
                    .label("Prince Ali Rescue")
                    .bringFood()
                    // A spare rope in case Lady Keli frees herself before the cell door is unlocked.
                    .extra(ItemNeed.of(ItemID.ROPE, "Rope", 1))
                    // Al Kharid toll gate both ways, twice (Osman, then Hassan), and a spare key from Leela.
                    .coins(100)
                    .override(PrinceAliSupport.RETIE_KELI)
                    .override(PrinceAliSupport.DYE_WIG)
                    .notes("Buys everything: wool, yellow dye, paste ingredients, soft clay, bronze bar, pink skirt, "
                            + "three beers and two ropes. Ned makes the wig, Aggie the paste, Keli's key print goes "
                            + "to Osman for the key. Jail guards (level 26) can attack, so food is brought; if Lady "
                            + "Keli frees herself before the door opens, she's tied up again with the spare rope.")
                    .build()),
            entry(QuestingConfig::demonSlayer, QuestDefinition.builder(QuestHelperQuest.DEMON_SLAYER)
                    .label("Demon Slayer")
                    .fight(15)
                    // Skips the bucket hunt above Varrock Castle's kitchen and filling it at the sink.
                    .extra(ItemNeed.of(ItemID.BUCKET_WATER, "Bucket of water", 1))
                    .override(DemonSlayer.FIGHT_BACK)
                    .answer(DemonSlayer.INCANTATION)
                    .notes("Buys the 25 bones and a bucket of water. Delrith (level 27) is fought with Silverlight at "
                            + "the stone circle south of Varrock, where dark wizards (levels 7 and 20) attack first; "
                            + "the banishing words are read from the quest's varbits and said in order.")
                    .build()),
            entry(QuestingConfig::knightsSword, QuestDefinition.builder(QuestHelperQuest.THE_KNIGHTS_SWORD)
                    .label("The Knight's Sword")
                    .fight(20)
                    .notes("Needs 10 Mining. Blurite is mined in the Asgarnian Ice Dungeon, where ice warriors attack.")
                    .build()),
            entry(QuestingConfig::blackKnightsFortress, QuestDefinition.builder(QuestHelperQuest.BLACK_KNIGHTS_FORTRESS)
                    .label("Black Knights' Fortress")
                    .bringFood()
                    .notes("Needs 12 quest points. The iron chainbody and bronze med helm are worn as a disguise; "
                            + "level 33 Black Knights may still need evading.")
                    .build()),
            entry(QuestingConfig::belowIceMountain, QuestDefinition.builder(QuestHelperQuest.BELOW_ICE_MOUNTAIN)
                    .label("Below Ice Mountain")
                    .fight(15)
                    // Quest Helper only accepts Asgarnian ale for Burntof; buying one skips the bar trip.
                    .extra(ItemNeed.of(ItemID.ASGARNIAN_ALE, "Asgarnian ale", 1))
                    .notes("Needs 16 quest points. Ends with a fight against the Ancient Guardian (level 25).")
                    .build()),
            entry(QuestingConfig::dragonSlayer, QuestDefinition.builder(QuestHelperQuest.DRAGON_SLAYER_I)
                    .label("Dragon Slayer I")
                    .fight(DragonSlayerSupport.MIN_COMBAT)
                    .minFood(DragonSlayerSupport.MAZE_FOOD)
                    // Wormbrain's map piece is bought: 10,000 coins (Quest Helper lists it as "coins or Telegrab").
                    .coins(10_000)
                    .kitPlanner(DragonSlayerSupport::planGear)
                    .override(DragonSlayerSupport.ELVARG_FIGHT)
                    .override(DragonSlayerSupport.PRAYER_OFF)
                    .override(DragonSlayerSupport.WEAR_SHIELD)
                    .override(DragonSlayerSupport.WEAR_GEAR)
                    .override(DragonSlayerSupport.COMBINE_MAP)
                    .override(DragonSlayerSupport.BRING_COINS)
                    .override(DragonSlayerSupport.GET_SHIELD_EARLY)
                    .override(DragonSlayerSupport.PREPARE_FOR_MAZE)
                    .override(DragonSlayerSupport.PREPARE_FOR_ELVARG)
                    .answer(DragonSlayerSupport.DUKE_ANSWERS)
                    .notes("Needs 32 quest points and combat 45 (the wiki's recommendation). Buys the Thalzar door "
                            + "items, planks, nails and a hammer, pays Wormbrain 10,000 and Klarense 2,000. Melee gear "
                            + "is planned by level (owned first, the rest bought within the budget) and the anti-dragon "
                            + "shield is fetched early and always worn. Elvarg is fought with 20 food, Protect from "
                            + "Magic at 37 Prayer and prayer potions on members worlds; no antipoison is needed.")
                    .build()),
            // Members quests, in the OSRS Wiki's optimal quest order.
            entry(QuestingConfig::gertrudesCat, QuestDefinition.builder(QuestHelperQuest.GERTRUDES_CAT)
                    .label("Gertrude's Cat")
                    .members()
                    .override(GertrudesCatSupport.SEARCH_CRATES)
                    .notes("Members. Buys the milk, sardine and doogle leaves, and searches the Lumberyard crates in "
                            + "turn (nearest the mewing first) until the kitten turns up.")
                    .build()),
            entry(QuestingConfig::dwarfCannon, QuestDefinition.builder(QuestHelperQuest.DWARF_CANNON)
                    .label("Dwarf Cannon")
                    .members()
                    .notes("Members. Repairs the fence railings, finds Lollk and fixes the cannon, all from Quest "
                            + "Helper's steps (the cannon repair is its highlighted tool puzzle).")
                    .build()),
            entry(QuestingConfig::waterfallQuest, QuestDefinition.builder(QuestHelperQuest.WATERFALL_QUEST)
                    .label("Waterfall Quest")
                    .members()
                    .fight(25)
                    .override(WaterfallPillars.OVERRIDE)
                    .notes("Members. Fire giants (86) and a moss guardian (84) are run past without gear, so the wiki "
                            + "recommends combat 25. Places the runes on all six pillars and the amulet on the statue itself.")
                    .build()),
            entry(QuestingConfig::treeGnomeVillage, QuestDefinition.builder(QuestHelperQuest.TREE_GNOME_VILLAGE)
                    .label("Tree Gnome Village")
                    .members()
                    .fight(55)
                    .minFood(15)
                    .notes("Members. Through the hedge maze, logs for the gnome fortress and the ballista "
                            + "coordinates, then the Khazard Warlord (level 112) for the last orb; the wiki suggests "
                            + "safespotting him, so the gate is combat 55 with 15 food.")
                    .build()),
            entry(QuestingConfig::witchsHouse, QuestDefinition.builder(QuestHelperQuest.WITCHS_HOUSE)
                    .label("Witch's House")
                    .members()
                    .fight(35)
                    .minFood(10)
                    .notes("Members. The key under the doormat, the cheese for the mouse hole and the magnet, then "
                            + "the witch's experiment in four forms (levels 19, 30, 42 and 53) one after another.")
                    .build()),
            entry(QuestingConfig::touristTrap, QuestDefinition.builder(QuestHelperQuest.THE_TOURIST_TRAP)
                    .label("The Tourist Trap")
                    .members()
                    .fight(40)
                    .minFood(10)
                    // The desert drains health without water.
                    .extra(ItemNeed.of(ItemID.WATER_SKIN4, "Waterskin(4)", 3))
                    .notes("Members. Needs 10 Fletching and 20 Smithing. Takes three waterskins for the desert heat, "
                            + "fights the Mercenary Captain (level 47) for the key, and gets Ana out of the mine in a "
                            + "barrel and cart.")
                    .build()),
            entry(QuestingConfig::druidicRitual, QuestDefinition.builder(QuestHelperQuest.DRUIDIC_RITUAL)
                    .label("Druidic Ritual")
                    .members()
                    .notes("Members. Buys the four raw meats and dips them in the Cauldron of Thunder under Taverley. "
                            + "Unlocks Herblore.")
                    .build()),
            entry(QuestingConfig::recruitmentDrive, QuestDefinition.builder(QuestHelperQuest.RECRUITMENT_DRIVE)
                    .label("Recruitment Drive")
                    .members()
                    .override(RecruitmentDriveSupport.EMPTY_HANDED)
                    .notes("Members. Needs Black Knights' Fortress and Druidic Ritual. Banks everything, worn and "
                            + "carried, before Sir Tiffy (he takes nothing in), then the seven tests with Quest "
                            + "Helper's puzzle solutions; Sir Leye (level 20) is fought bare-handed.")
                    .build()),
            entry(QuestingConfig::fightArena, QuestDefinition.builder(QuestHelperQuest.FIGHT_ARENA)
                    .label("Fight Arena")
                    .members()
                    .fight(50)
                    .notes("Members. Fights a Khazard scorpion (44), Khazard ogre (63) and the Bouncer (137); all can be "
                            + "safespotted, and General Khazard (142) is optional. The wiki recommends combat 50.")
                    .build()),
            entry(QuestingConfig::plagueCity, QuestDefinition.builder(QuestHelperQuest.PLAGUE_CITY)
                    .label("Plague City")
                    .members()
                    .notes("Members. Buys the buckets of water, rope, chocolate dust, milk and snape grass; softens "
                            + "the mud with four buckets, pulls off the sewer grill and makes the hangover cure for "
                            + "Elena's rescue.")
                    .build()),
            entry(QuestingConfig::monksFriend, QuestDefinition.builder(QuestHelperQuest.MONKS_FRIEND)
                    .label("Monk's Friend")
                    .members()
                    .notes("Members. Finds the child's blanket in the thieves' den, then fetches water for Cedric "
                            + "and helps him mend his cart before the party.")
                    .build()),
            entry(QuestingConfig::hazeelCult, QuestDefinition.builder(QuestHelperQuest.HAZEEL_CULT)
                    .label("Hazeel Cult")
                    .members()
                    .fight(20)
                    // Always Ceril's side.
                    .answer((ctx, options) -> options.stream()
                            .filter(o -> o.startsWith("I won't help you")).findFirst().orElse(null))
                    .notes("Members. Always sides with Ceril Carnillean, so the cult's leader Alomone (level 13) is "
                            + "the only fight.")
                    .build()),
            entry(QuestingConfig::biohazard, QuestDefinition.builder(QuestHelperQuest.BIOHAZARD)
                    .label("Biohazard")
                    .members()
                    .fight(20)
                    .notes("Members. Needs Plague City. Distracts the mourners with the pigeons, gets the doctor's "
                            + "gown, and takes the plague sample to Varrock; one mourner (level 13) is fought for the key.")
                    .build()),
            entry(QuestingConfig::towerOfLife, QuestDefinition.builder(QuestHelperQuest.TOWER_OF_LIFE)
                    .label("Tower of Life")
                    .members()
                    .notes("Members. Needs 10 Construction. Builds the tower's machinery in the builders' outfit and "
                            + "solves the pipe puzzle with Quest Helper's solver.")
                    .build()),
            entry(QuestingConfig::tribalTotem, QuestDefinition.builder(QuestHelperQuest.TRIBAL_TOTEM)
                    .label("Tribal Totem")
                    .members()
                    .notes("Members. Needs 21 Thieving. Swaps the crate label at the Ardougne docks, enters the "
                            + "combination on Horacio's door (Quest Helper's puzzle) and disarms the trap on the stairs.")
                    .build()),
            entry(QuestingConfig::deathPlateau, QuestDefinition.builder(QuestHelperQuest.DEATH_PLATEAU)
                    .label("Death Plateau")
                    .members()
                    .notes("Members. Wins the IOU from Harold, places the stone balls on the stone mechanism and "
                            + "gets the secret path to Death Plateau from Tenzing.")
                    .build()),
            entry(QuestingConfig::merlinsCrystal, QuestDefinition.builder(QuestHelperQuest.MERLINS_CRYSTAL)
                    .label("Merlin's Crystal")
                    .members()
                    .fight(40)
                    .minFood(10)
                    .override(MerlinsCrystalSupport.DROP_BAT_BONES)
                    .notes("Members. Hides in Arhein's crate to reach Keep Le Faye and fights Sir Mordred (level 39). "
                            + "Gets Excalibur, the black candle and bat bones, drops the bones in the star to summon "
                            + "Thrantax, and says the incantation.")
                    .build()),
            entry(QuestingConfig::holyGrail, QuestDefinition.builder(QuestHelperQuest.HOLY_GRAIL)
                    .label("Holy Grail")
                    .members()
                    .fight(55)
                    .minFood(15)
                    .override(HolyGrailSupport.PREPARE_FOR_ENTRANA)
                    .override(HolyGrailSupport.BOARD_BOAT)
                    .override(HolyGrailSupport.WIELD_EXCALIBUR)
                    .notes("Members. Needs Merlin's Crystal and 20 Attack. Banks all combat gear for the trip to the "
                            + "High Priest on Entrana and boards with Take-boat. The Black Knight Titan (level 120) "
                            + "is fought with Excalibur wielded, since only it can land the killing blow.")
                    .build()),
            entry(QuestingConfig::murderMystery, QuestDefinition.builder(QuestHelperQuest.MURDER_MYSTERY)
                    .label("Murder Mystery")
                    .members()
                    .notes("Members. Collects the evidence at the Sinclair Mansion, matches the fingerprints and the "
                            + "poison, and names the murderer Quest Helper works out.")
                    .build()),
            entry(QuestingConfig::grandTree, QuestDefinition.builder(QuestHelperQuest.THE_GRAND_TREE)
                    .label("The Grand Tree")
                    .members()
                    .fight(70)
                    .minFood(20)
                    .notes("Members. Investigates Glough, sails to Karamja for the translation book and the twigs, "
                            + "and fights the Black Demon (level 172) at the end; the gate is combat 70 with 20 food.")
                    .build()),
            entry(QuestingConfig::ragAndBoneManI, QuestDefinition.builder(QuestHelperQuest.RAG_AND_BONE_MAN_I)
                    .label("Rag and Bone Man I")
                    .members()
                    .fight(20)
                    .notes("Members. Kills a goblin, bear, ram, unicorn, giant bat and big frog for their bones, then "
                            + "boils them in a pot of vinegar.")
                    .build()),
            entry(QuestingConfig::priestInPeril, QuestDefinition.builder(QuestHelperQuest.PRIEST_IN_PERIL)
                    .label("Priest in Peril")
                    .members()
                    .fight(25)
                    // Drezel's 50 essence is fetched in loads when he asks, not carried through the fights.
                    .skip(ItemID.BLANKRUNE)
                    .skip(ItemID.BLANKRUNE_HIGH)
                    .override(PriestInPerilSupport.SWAP_KEY)
                    .override(PriestInPerilSupport.FETCH_ESSENCE)
                    .notes("Members. Fights the Temple Guardian (level 30, immune to Magic) and a Monk of Zamorak "
                            + "(level 30) for the golden key, which is swapped for the iron key on the southern "
                            + "monument under the temple. Drezel's 50 unnoted essence comes in two bank trips "
                            + "(pure essence, bought if the bank has none).")
                    .build()),
            entry(QuestingConfig::natureSpirit, QuestDefinition.builder(QuestHelperQuest.NATURE_SPIRIT)
                    .label("Nature Spirit")
                    .members()
                    .fight(20)
                    // Bloom drains Prayer; one potion covers the fungi for the pouch.
                    .extra(ItemNeed.of(ItemID._4DOSEPRAYERRESTORE, "Prayer potion(4)", 1))
                    .override(NatureSpiritSupport.PICK_UP_BOWL)
                    .override(NatureSpiritSupport.PICK_UP_MIRROR)
                    .override(NatureSpiritSupport.GROW_MUSHROOM)
                    .override(NatureSpiritSupport.STAND_ON_ORANGE)
                    .override(NatureSpiritSupport.GHASTS)
                    .notes("Members. Needs Priest in Peril and The Restless Ghost (the ghostspeak amulet). Buys the "
                            + "silver sickle and a prayer potion. Grows the mushroom with the druidic spell beside a "
                            + "rotting log, stands on the orange stone for the ritual, then blooms the blessed sickle "
                            + "for fungi, fills the druid pouch and uses it on three ghasts (level 30) to kill them.")
                    .build()),
            entry(QuestingConfig::ghostsAhoy, QuestDefinition.builder(QuestHelperQuest.GHOSTS_AHOY)
                    .label("Ghosts Ahoy")
                    .members()
                    .fight(20)
                    // Untradeable: tokens are earned at the Ectofuntus as steps need them, slime comes from the pool.
                    .skip(ItemID.ECTOTOKEN)
                    .skip(ItemID.BUCKET_ECTOPLASM)
                    .skip(ItemID.RING_OF_CHAROS_UNLOCKED)
                    // Owned ecto-tokens first; bones, pots and buckets only for the offerings still short.
                    .kitPlanner(GhostsAhoySupport::planTokens)
                    // The flag colours are random: three of each primary dye covers any mix.
                    .extra(ItemNeed.of(ItemID.REDDYE, "Red dye", 3))
                    .extra(ItemNeed.of(ItemID.BLUEDYE, "Blue dye", 3))
                    .extra(ItemNeed.of(ItemID.YELLOWDYE, "Yellow dye", 3))
                    .extra(ItemNeed.of(ItemID.NEEDLE, "Needle", 1))
                    .extra(ItemNeed.of(ItemID.THREAD, "Thread", 5))
                    .extra(ItemNeed.of(ItemID.KNIFE, "Knife", 1))
                    // Catherby tablet + charter ship to Port Phasmatys, there and back after a bank trip.
                    .extra(ItemNeed.of(GhostsAhoySupport.CATHERBY_TABLET, "Catherby teleport",
                            GhostsAhoySupport.TRIPS))
                    .coins(400 + GhostsAhoySupport.CHARTER_FARE * GhostsAhoySupport.TRIPS)
                    .override(GhostsAhoySupport.TRAVEL)
                    .override(GhostsAhoySupport.ARRIVAL)
                    .override(GhostsAhoySupport.WEAR)
                    .override(GhostsAhoySupport.RUNEDRAW)
                    .override(GhostsAhoySupport.TOKENS)
                    .override(GhostsAhoySupport.SLIME_FOR_SHEET)
                    .override(GhostsAhoySupport.DYE_FLAG)
                    .override(GhostsAhoySupport.ROCKS)
                    .notes("Members. Needs Priest in Peril, The Restless Ghost, 25 Agility and 20 Cooking. Buys "
                            + "nettle tea, milk, silk, sewing kit, dyes, spade and oak longbow; owned ecto-tokens come along, "
                            + "with bones, pots and buckets only for any shortfall, earned at the Ectofuntus when a step needs more than are "
                            + "carried (barrier 2, Dragontooth boat 25). Travels by Catherby tablet and the charter ship to Port "
                            + "Phasmatys (2 tablets, 3,500 coins a trip). Rune-Draw always draws until Robin pays up, "
                            + "the bedsheet is dyed with slime from the pool, the flag dyes are mixed as needed, and "
                            + "the rocks to the third chest are jumped one by one. The giant lobster is level 32.")
                    .build()),
            entry(QuestingConfig::makingHistory, QuestDefinition.builder(QuestHelperQuest.MAKING_HISTORY)
                    .label("Making History")
                    .members()
                    .notes("Members. Needs Priest in Peril and The Restless Ghost. Digs up the chest in the Ardougne "
                            + "field, takes the letter to the Outpost and the scroll to Droalak's ghost, then reports "
                            + "to the Port Phasmatys historian.")
                    .build()),
            entry(QuestingConfig::animalMagnetism, QuestDefinition.builder(QuestHelperQuest.ANIMAL_MAGNETISM)
                    .label("Animal Magnetism")
                    .members()
                    // Ecto-tokens can't be traded: they're earned at the Ectofuntus from these, 4 offerings x 5.
                    .skip(ItemID.ECTOTOKEN)
                    .extra(ItemNeed.of(ItemID.BONES, "Bones", AnimalMagnetismSupport.WORSHIPS))
                    .extra(ItemNeed.of(ItemID.POT_EMPTY, "Pot", AnimalMagnetismSupport.WORSHIPS))
                    .extra(ItemNeed.of(ItemID.BUCKET_EMPTY, "Bucket", AnimalMagnetismSupport.WORSHIPS))
                    // Worn for the whole quest: Malcolm, the Old crone and the ghost disciples are ghosts.
                    .skip(ItemID.AMULET_OF_GHOSTSPEAK)
                    .extra(new ItemNeed(ItemID.AMULET_OF_GHOSTSPEAK, "Ghostspeak amulet", 1, true))
                    .override(AnimalMagnetismSupport.ECTO_TOKENS)
                    .override(AnimalMagnetismSupport.BAR_MAGNET)
                    .override(AnimalMagnetismSupport.TRANSLATE_NOTES)
                    .override(AnimalMagnetismSupport.BUILD_CONTAINER)
                    .answer(AnimalMagnetismSupport.DISCIPLE_MENU)
                    .notes("Members. Needs The Restless Ghost (for the ghostspeak amulet), Ernest the Chicken, Priest in "
                            + "Peril, 18 Slayer, 19 Crafting, 30 Ranged and 35 Woodcutting. Buys the iron bars, mithril "
                            + "axe, holy symbol, hammer, hard leather, polished buttons, and 4 bones, pots and buckets; "
                            + "the 20 ecto-tokens for Malcolm's chickens are earned at the Ectofuntus (grind, fill "
                            + "slime, worship x4, claim from a ghost disciple). The magnet is hammered facing north, "
                            + "and the research notes use the wiki's switch solution.")
                    .build()),
            entry(QuestingConfig::lostCity, QuestDefinition.builder(QuestHelperQuest.LOST_CITY)
                    .label("Lost City")
                    .members()
                    .fight(30)
                    .minFood(LostCitySupport.FOOD)
                    .override(LostCitySupport.DUNGEON)
                    .override(LostCitySupport.RETALIATE_BACK_ON)
                    .override(LostCitySupport.PREPARE_FOR_ENTRANA)
                    .override(LostCitySupport.BOARD_BOAT)
                    .override(LostCitySupport.ON_ENTRANA)
                    .override(LostCitySupport.MAKE_STAFF)
                    .override(LostCitySupport.WIELD_STAFF)
                    .answer(LostCitySupport.MONK)
                    .notes("Members. Needs 31 Crafting and 36 Woodcutting. Chops the tree by the Draynor swamp camp for "
                            + "Shamus, then banks all worn gear and sails to Entrana with only runes, food, a knife and "
                            + "a Lumbridge teleport tablet. Fights with hand-cast spells (the best strike, bolt or blast "
                            + "for your Magic, runes bought for 160 casts), kills zombies for a bronze axe, and fights "
                            + "the Tree Spirit (level 101) from behind a fungus. Teleports out with the branch, carves "
                            + "the dramen staff and wields it into the shed for Zanaris.")
                    .build()),
            entry(QuestingConfig::fairytaleI, QuestDefinition.builder(QuestHelperQuest.FAIRYTALE_I__GROWING_PAINS)
                    .label("Fairytale I - Growing Pains")
                    .members()
                    .fight(50)
                    .minFood(15)
                    // Dug up during the quest (untradeable).
                    .skip(ItemID.FAIRY_SKULL)
                    .override(FairytaleSupport.WIELD)
                    .override(FairytaleSupport.FARMERS_STEP)
                    .override(FairytaleSupport.MORTIFER_ITEMS_STEP)
                    .notes("Members. Needs Lost City (the dramen staff, from the bank) and Nature Spirit. Buys "
                            + "secateurs and a spade, asks five farmers about the crops, digs up the Draynor skull, "
                            + "and buys Malignius Mortifer's three random items (read from the quest's varbit). The "
                            + "Tanglefoot (level 111) is fought with the magic secateurs wielded; the wiki recommends "
                            + "combat 50.")
                    .build())
    );

    private static Entry entry(Predicate<QuestingConfig> enabled, QuestDefinition definition) {
        return new Entry(definition, enabled);
    }

    /**
     * Witch's Potion: Quest Helper lists burnt meat but has no step for making it. Per the OSRS Wiki, using cooked meat
     * on a fire or range burns it, and Hetty's house has a fireplace, so do that next to her.
     */
    /**
     * Shears the nearest sheep that still has wool. Quest Helper's generic step can pick an already-shorn sheep and
     * fall back to "Talk-to", so this only targets sheep whose current form offers "Shear".
     */
    static final class ShearSheep {

        private static final WorldPoint PEN = new WorldPoint(3201, 3268, 0);
        private static final int SEARCH_RADIUS = 15;

        static final StepOverride OVERRIDE = StepOverride.onStepText("shearing sheep", "Shear sheep", ShearSheep::run);

        private ShearSheep() {
        }

        private static boolean run(QuestContext ctx) {
            // Without shears the guide's own step picks them up in Fred's house.
            if (!ctx.has(ItemID.SHEARS)) return false;

            Rs2NpcModel sheep = Microbot.getRs2NpcCache().query()
                    .withName("Sheep")
                    .where(ShearSheep::canShear)
                    .nearestReachableOnClientThread(SEARCH_RADIUS);
            int before = ctx.count(ItemID.WOOL);
            if (sheep == null) {
                Microbot.status = "Looking for a sheep with wool (" + before + " wool)";
                ctx.walkTo(PEN, 3);
                return true;
            }
            Microbot.status = "Shearing sheep (" + before + " wool)";
            if (sheep.click("Shear")) {
                sleepUntil(() -> ctx.count(ItemID.WOOL) > before, 5000);
            }
            return true;
        }

        /** The sheep's current (transformed) form has a "Shear" option, i.e. it still has wool. */
        private static boolean canShear(Rs2NpcModel sheep) {
            NPCComposition def = sheep.getNpc().getTransformedComposition();
            if (def == null) def = Microbot.getClient().getNpcDefinition(sheep.getId());
            if (def == null || def.getActions() == null) return false;
            for (String action : def.getActions()) {
                if ("Shear".equalsIgnoreCase(action)) return true;
            }
            return false;
        }
    }

    static final class BurnMeat {

        private static final WorldPoint HETTY = new WorldPoint(2968, 3205, 0);

        static final StepOverride OVERRIDE = new StepOverride("burning the meat",
                ctx -> !ctx.has(ItemID.BURNT_MEAT) && ctx.has(ItemID.COOKED_MEAT),
                BurnMeat::run);

        private BurnMeat() {
        }

        private static boolean run(QuestContext ctx) {
            if (ctx.continueDialogue()) return true;
            if (!ctx.walkTo(HETTY, 6)) return true;

            Rs2TileObjectModel fire = Microbot.getRs2TileObjectCache().query()
                    .withNameContains("fire").within(HETTY, 10).nearestOnClientThread();
            if (fire == null) {
                fire = Microbot.getRs2TileObjectCache().query()
                        .withNameContains("range").within(HETTY, 10).nearestOnClientThread();
            }
            if (fire == null) return false;

            Microbot.status = "Burning the cooked meat";
            if (ctx.useItemOn(ItemID.COOKED_MEAT, fire)) {
                // Some cooking spots open a make-X menu even for one item; space confirms it.
                if (sleepUntil(Rs2Widget::isProductionWidgetOpen, 1800)) {
                    Rs2Keyboard.keyPress(KeyEvent.VK_SPACE);
                }
                sleepUntil(() -> ctx.has(ItemID.BURNT_MEAT), 5000);
            }
            return true;
        }
    }

    /**
     * Demon Slayer's fight. The stone circle is single-way combat and dark wizards attack first, so one that's
     * attacking us is fought before Delrith (clicking Delrith then only says we're already under attack). When
     * Delrith is beaten an option menu asks for the five banishing words one at a time; their order is random per
     * player and kept in varbits, which Quest Helper reads too but attaches to a parent step the runner doesn't ask.
     */
    @Slf4j
    static final class DemonSlayer {

        private static final String[] WORDS = {"Carlem", "Aber", "Camerinthum", "Purchai", "Gabindo"};
        private static final int[] ORDER_VARBITS = {
                VarbitID.DELRITH_INCANTATION_1, VarbitID.DELRITH_INCANTATION_2, VarbitID.DELRITH_INCANTATION_3,
                VarbitID.DELRITH_INCANTATION_4, VarbitID.DELRITH_INCANTATION_5};
        // A word menu this long after the previous one starts a new attempt (a wrong or interrupted one).
        private static final long NEW_ATTEMPT_MS = 20_000;

        static final StepOverride FIGHT_BACK = new StepOverride("fighting back a dark wizard",
                ctx -> ctx.stepText().contains("Delrith") && attackingWizard() != null,
                DemonSlayer::fightBack);

        static final DialogueAnswer INCANTATION = DemonSlayer::nextWord;

        private DemonSlayer() {
        }

        private static Rs2NpcModel attackingWizard() {
            return Microbot.getRs2NpcCache().query()
                    .withName("Dark wizard")
                    .where(npc -> npc.getInteracting() == Microbot.getClient().getLocalPlayer())
                    .nearestOnClientThread();
        }

        private static boolean fightBack(QuestContext ctx) {
            Rs2NpcModel wizard = attackingWizard();
            if (wizard == null) return false;
            Actor target = Rs2Player.getInteracting();
            if (target != null && "Dark wizard".equalsIgnoreCase(target.getName())) return true;
            Microbot.status = "Fighting back a dark wizard";
            wizard.click("Attack");
            sleepUntil(Rs2Player::isInteracting, 2400);
            return true;
        }

        private static String nextWord(QuestContext ctx, List<String> options) {
            boolean wordMenu = options.stream().anyMatch("Aber"::equalsIgnoreCase)
                    && options.stream().anyMatch("Carlem"::equalsIgnoreCase);
            if (!wordMenu) return null;

            String[] order = incantation();
            if (order == null) {
                log.warn("[Questing] Demon Slayer: the incantation isn't known yet (ask Aris how Wally beat Delrith)");
                return null;
            }
            long now = System.currentTimeMillis();
            int position = ctx.get("incantationPosition", 0);
            long last = ctx.get("incantationAt", 0L);
            if (position >= order.length || now - last > NEW_ATTEMPT_MS) position = 0;
            ctx.set("incantationPosition", position + 1);
            ctx.set("incantationAt", now);
            log.info("[Questing] Demon Slayer: incantation word {}/{}: {}", position + 1, order.length, order[position]);
            return order[position];
        }

        /** The five words in order, or null while the quest hasn't revealed them (both first varbits still 0). */
        private static String[] incantation() {
            int[] values = new int[ORDER_VARBITS.length];
            for (int i = 0; i < values.length; i++) values[i] = Microbot.getVarbitValue(ORDER_VARBITS[i]);
            if (values[0] == 0 && values[1] == 0) return null;
            String[] order = new String[values.length];
            for (int i = 0; i < values.length; i++) {
                if (values[i] < 0 || values[i] >= WORDS.length) return null;
                order[i] = WORDS[values[i]];
            }
            return order;
        }
    }

    /**
     * Misthalin Mystery's showdown: the killer hides in one of the room's wardrobes and throws knives; a brief black
     * shadow around a wardrobe shows which. Per the OSRS Wiki, the mirror has to be pushed into that wardrobe's row or
     * column and then pushed at least once towards it, so it faces the wardrobe and reflects the knife back. A few
     * reflections end the puzzle with a cutscene. Quest Helper only describes this in text.
     * <p>
     * The mirror is pushed like a block: we stand on the tile behind it and it moves one tile away from us, facing that
     * way. The killer's wardrobe is read from, in order: an open wardrobe, a graphic on or next to a wardrobe (the
     * shadow), or where the last knife came from. Graphic and projectile IDs seen here are logged once each, since
     * this was written without seeing the room in-game.
     */
    @Slf4j
    static final class MirrorPuzzle {

        private static final int[] MIRRORS = {NpcID.MISTMYST_MIRROR_MOVABLE, NpcID.MISTMYST_MIRROR};
        private static final int WARDROBE = ObjectID.MISTMYST_BOSS_WARDROBE;
        private static final int WARDROBE_OPEN = ObjectID.MISTMYST_BOSS_WARDROBE_OPEN;
        private static final int ROOM_RADIUS = 14;
        // A graphic or knife start this close to a wardrobe counts as coming from it.
        private static final int SOURCE_RANGE = 2;
        // Failed pushes in a row before switching between pushing towards the wardrobe and backing off.
        private static final int MAX_FAILED_PUSHES = 3;
        private static final int PUSH_WAIT_MS = 2_400;

        static final StepOverride OVERRIDE = StepOverride.onStepText(
                "reflecting the knives", "move the mirror", MirrorPuzzle::run);

        private MirrorPuzzle() {
        }

        private static boolean run(QuestContext ctx) {
            if (ctx.continueDialogue()) return true;

            Rs2NpcModel mirror = Microbot.getRs2NpcCache().query().withIds(MIRRORS).nearestOnClientThread(ROOM_RADIUS);
            List<Rs2TileObjectModel> wardrobes = new ArrayList<>(ctx.objects(WARDROBE));
            wardrobes.addAll(ctx.objects(WARDROBE_OPEN));
            WorldPoint here = Rs2Player.getWorldLocation();
            wardrobes.removeIf(w -> here == null || w.getWorldLocation().distanceTo(here) > ROOM_RADIUS);
            if (mirror == null || wardrobes.isEmpty()) {
                log.info("[Questing] Mirror puzzle: mirror found {}, {} wardrobes nearby", mirror != null, wardrobes.size());
                return false;
            }

            WorldArea seen = findKiller(ctx, wardrobes);
            if (seen != null) ctx.set("mirrorTarget", seen);
            WorldArea target = ctx.get("mirrorTarget", (WorldArea) null);
            if (target == null) {
                Microbot.status = "Watching the wardrobes for the killer's shadow";
                return true;
            }

            // The wall the wardrobe is on decides the knife's line: straight across the room from it.
            double cx = 0, cy = 0;
            for (Rs2TileObjectModel w : wardrobes) {
                WorldArea a = w.getWorldArea();
                cx += a.getX() + (a.getWidth() - 1) / 2.0;
                cy += a.getY() + (a.getHeight() - 1) / 2.0;
            }
            cx /= wardrobes.size();
            cy /= wardrobes.size();
            double tx = target.getX() + (target.getWidth() - 1) / 2.0;
            double ty = target.getY() + (target.getHeight() - 1) / 2.0;
            boolean sideWall = Math.abs(tx - cx) >= Math.abs(ty - cy);

            WorldPoint m = mirror.getWorldLocation();
            int[] push;
            String why;
            if (sideWall && (m.getY() < target.getY() || m.getY() >= target.getY() + target.getHeight())) {
                int rowY = clamp(m.getY(), target.getY(), target.getY() + target.getHeight() - 1);
                push = new int[]{0, Integer.signum(rowY - m.getY())};
                why = "into the wardrobe's row";
            } else if (!sideWall && (m.getX() < target.getX() || m.getX() >= target.getX() + target.getWidth())) {
                int colX = clamp(m.getX(), target.getX(), target.getX() + target.getWidth() - 1);
                push = new int[]{Integer.signum(colX - m.getX()), 0};
                why = "into the wardrobe's column";
            } else {
                int[] toward = sideWall ? new int[]{(int) Math.signum(tx - m.getX()), 0}
                        : new int[]{0, (int) Math.signum(ty - m.getY())};
                if (facing(ctx, m, toward)) {
                    Microbot.status = "Mirror faces the killer's wardrobe";
                    return true;
                }
                // Right up against the wardrobe it can't move towards it: back it off a tile first.
                boolean backOff = ctx.get("mirrorBackOff", false);
                push = backOff ? new int[]{-toward[0], -toward[1]} : toward;
                why = backOff ? "away from the wardrobe to make room" : "to face the wardrobe";
            }
            return pushMirror(ctx, mirror, m, push, why);
        }

        /** The wardrobe the killer is in right now, or null if nothing shows it this tick. */
        private static WorldArea findKiller(QuestContext ctx, List<Rs2TileObjectModel> wardrobes) {
            for (Rs2TileObjectModel w : wardrobes) {
                if (w.getId() == WARDROBE_OPEN) return w.getWorldArea();
            }
            Set<String> logged = ctx.get("mirrorLoggedIds", new HashSet<String>());
            ctx.set("mirrorLoggedIds", logged);
            List<WorldArea> areas = new ArrayList<>();
            for (Rs2TileObjectModel w : wardrobes) areas.add(w.getWorldArea());

            return Microbot.getClientThread().runOnClientThreadOptional(() -> {
                Client client = Microbot.getClient();
                for (GraphicsObject g : client.getTopLevelWorldView().getGraphicsObjects()) {
                    WorldPoint at = WorldPoint.fromLocal(client, g.getLocation());
                    WorldArea near = nearest(areas, at);
                    if (logged.add("graphic " + g.getId())) {
                        log.info("[Questing] Mirror puzzle: graphic {} at {}{}", g.getId(), at,
                                near != null ? " (by a wardrobe)" : "");
                    }
                    if (near != null) return near;
                }
                WorldArea fromKnife = null;
                for (Projectile p : client.getProjectiles()) {
                    WorldPoint from = p.getSourcePoint();
                    WorldArea near = from == null ? null : nearest(areas, from);
                    if (logged.add("projectile " + p.getId())) {
                        log.info("[Questing] Mirror puzzle: projectile {} from {}{}", p.getId(), from,
                                near != null ? " (a wardrobe)" : "");
                    }
                    if (near != null) fromKnife = near;
                }
                return fromKnife;
            }).orElse(null);
        }

        private static WorldArea nearest(List<WorldArea> areas, WorldPoint point) {
            WorldArea best = null;
            int bestDistance = SOURCE_RANGE + 1;
            for (WorldArea a : areas) {
                int d = a.distanceTo(point);
                if (d < bestDistance) {
                    best = a;
                    bestDistance = d;
                }
            }
            return best;
        }

        /** Whether our last push left the mirror here, facing this way. */
        private static boolean facing(QuestContext ctx, WorldPoint mirrorAt, int[] direction) {
            WorldPoint pushedTo = ctx.get("mirrorAt", (WorldPoint) null);
            int[] pushed = ctx.get("mirrorFacing", (int[]) null);
            return mirrorAt.equals(pushedTo) && pushed != null
                    && pushed[0] == direction[0] && pushed[1] == direction[1];
        }

        /** Stands behind the mirror and pushes it one tile in this direction. */
        private static boolean pushMirror(QuestContext ctx, Rs2NpcModel mirror, WorldPoint m, int[] dir, String why) {
            WorldPoint behind = new WorldPoint(m.getX() - dir[0], m.getY() - dir[1], m.getPlane());
            if (!behind.equals(Rs2Player.getWorldLocation())) {
                Microbot.status = "Getting behind the mirror";
                Rs2Walker.walkFastCanvas(behind);
                sleepUntil(() -> behind.equals(Rs2Player.getWorldLocation()), 3000);
                return true;
            }

            Microbot.status = "Pushing the mirror " + why;
            String action = pushAction(mirror);
            boolean moved = mirror.click(action)
                    && sleepUntil(() -> !m.equals(mirror.getWorldLocation()), PUSH_WAIT_MS);
            if (moved) {
                ctx.set("mirrorAt", mirror.getWorldLocation());
                ctx.set("mirrorFacing", dir);
                ctx.set("mirrorFailedPushes", 0);
                // Any successful push ends a back-off: the next one goes towards the wardrobe again.
                ctx.set("mirrorBackOff", false);
                return true;
            }

            int failed = ctx.get("mirrorFailedPushes", 0) + 1;
            ctx.set("mirrorFailedPushes", failed);
            log.info("[Questing] Mirror puzzle: \"{}\" didn't move the mirror at {} {} (try {})", action, m, why, failed);
            if (failed >= MAX_FAILED_PUSHES) {
                ctx.set("mirrorFailedPushes", 0);
                ctx.set("mirrorBackOff", !ctx.get("mirrorBackOff", false));
            }
            return true;
        }

        /** The mirror's push option ("Push" unless the game calls it something else). */
        private static String pushAction(Rs2NpcModel mirror) {
            NPCComposition def = Microbot.getClientThread().runOnClientThreadOptional(() -> {
                NPCComposition c = mirror.getNpc().getTransformedComposition();
                return c != null ? c : Microbot.getClient().getNpcDefinition(mirror.getId());
            }).orElse(null);
            if (def != null && def.getActions() != null) {
                for (String action : def.getActions()) {
                    if (action != null && action.toLowerCase().contains("push")) return action;
                }
            }
            return "Push";
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    /**
     * Waterfall Quest's last room: one air, water and earth rune on each of the six pillars, then Glarial's amulet on
     * her statue. Quest Helper shows this as a single text step, so the generic executor can't do it.
     */
    static final class WaterfallPillars {

        private static final int PILLAR = ObjectID.STONEPILLAR_SMALL_WATERFALL_QUEST;
        private static final int STATUE = ObjectID.STATUE_QUEEN_WATERFALL_QUEST;
        private static final int AMULET = ItemID.GLARIALS_AMULET_WATERFALL_QUEST;
        private static final int[] RUNES = {ItemID.AIRRUNE, ItemID.WATERRUNE, ItemID.EARTHRUNE};
        // A rune that doesn't get used this many times (already placed earlier) counts as placed.
        private static final int MAX_TRIES_PER_RUNE = 2;

        static final StepOverride OVERRIDE = StepOverride.onStepText(
                "runes on the pillars", "rune on each of the 6 pillars", WaterfallPillars::run);

        private WaterfallPillars() {
        }

        private static boolean run(QuestContext ctx) {
            if (ctx.continueDialogue()) return true;

            Set<String> placed = ctx.get("pillarRunesPlaced", new HashSet<String>());
            ctx.set("pillarRunesPlaced", placed);

            List<Rs2TileObjectModel> pillars = new ArrayList<>(ctx.objects(PILLAR));
            if (pillars.isEmpty()) return false;
            pillars.sort(Comparator.comparingInt((Rs2TileObjectModel p) -> p.getWorldLocation().getX())
                    .thenComparingInt(p -> p.getWorldLocation().getY()));

            for (Rs2TileObjectModel pillar : pillars) {
                WorldPoint at = pillar.getWorldLocation();
                for (int rune : RUNES) {
                    String key = at + "#" + rune;
                    if (placed.contains(key)) continue;
                    Microbot.status = "Placing runes on pillar " + (pillars.indexOf(pillar) + 1) + " of " + pillars.size();
                    int before = ctx.count(rune);
                    boolean used = ctx.useItemOn(rune, pillar) && sleepUntil(() -> ctx.count(rune) < before, 6000);
                    int tries = ctx.get(key + "#tries", 0) + 1;
                    ctx.set(key + "#tries", tries);
                    if (used || tries >= MAX_TRIES_PER_RUNE) placed.add(key);
                    return true;
                }
            }

            // All pillars done: Glarial's amulet goes on the statue, which needs it in the inventory.
            Microbot.status = "Placing Glarial's amulet on the statue";
            if (!ctx.unequip(AMULET)) return true;
            ctx.useItemOn(AMULET, ctx.object(STATUE));
            sleepUntil(() -> !ctx.has(AMULET), 6000);
            return true;
        }
    }
}
