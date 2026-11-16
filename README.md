# OSRS Scripting

A portfolio of automation scripts for Old School RuneScape, written as plugins for the open-source
[Microbot](https://github.com/chsami/Microbot) client (a fork of [RuneLite](https://github.com/runelite/runelite)).
There are 15 scripts, all under my developer name **Shadowfax**, plus a shared framework they're built on: about 35,000
lines of Java.

> **Disclaimer: for educational purposes only.**
> This repository exists to show software design work: state machines, planning under uncertainty, recovery from
> failure, and working against a large existing codebase. **I do not condone botting.** Using third-party software
> to automate gameplay breaks Jagex's rules for Old School RuneScape, and accounts that do it can be banned. Nothing
> here is meant to be run on a live account. Old School RuneScape and RuneScape are trademarks of Jagex Ltd. This
> project is not affiliated with, endorsed by or connected to Jagex, RuneLite or Microbot.

---

## Highlights

- **A quest engine that plays 50 quests.** It drives Quest Helper's step graphs directly, and works out what each
  quest needs to carry, buy and wear. Quest-specific hooks cover the parts a generic step can't handle, such as
  puzzles, safespots, smuggling restrictions and random item requests.
- **Long-running, unattended designs.** Every script is an explicit state machine with a visible overlay. Every action
  is guarded so it can't repeat forever with nothing changing, and a shared watchdog logs out and stops a script that
  stops making progress.
- **Real-world data.** The merchant reads the OSRS Wiki's real-time price API, and includes the GE tax rules and stale
  offer handling. Other scripts buy whatever they're short of on the Grand Exchange, within a budget.
- **Planning, not just reacting.** The Slayer script reads the assigned task, then picks a location, gear setup and
  supplies, shops for slayer equipment it doesn't own and skips tasks it can't do. The bank organizer plans the whole
  bank layout before moving a single item.
- **Fixes contributed back to the client.** These are patches to Microbot's walker, NPC handling and mouse movement,
  found while building the scripts (see [Changes to Microbot](#changes-to-microbot)).

## Scripts

| Script | What it does |
|---|---|
| [Shadowfax Questing](#shadowfax-questing) | Completes 50 quests in a row, from Cook's Assistant to Dragon Slayer I, Holy Grail and Fairytale I |
| [Shadowfax Slayer](#shadowfax-slayer) | Gets slayer tasks from Turael, Vannaka or Nieve and completes them |
| [Shadowfax Merchant](#shadowfax-merchant) | Flips items and buys dips on the Grand Exchange using live wiki prices |
| [Shadowfax Warrior's Guild](#shadowfax-warriors-guild) | Earns tokens, then gets every defender up to the dragon defender |
| [Shadowfax Gem Crab](#shadowfax-gem-crab) | Fights the Gemstone Crab with any combat style and follows it between mines |
| [Shadowfax Bank Organizer](#shadowfax-bank-organizer) | Plans and lays out the whole bank, tab by tab |
| [Shadowfax Woodcutting](#shadowfax-woodcutting) | Chops the best tree for the level, upgrading the axe along the way |
| [Shadowfax Hill Giants](#shadowfax-hill-giants) | Kills hill giants for big bones and herbs, restocking food on its own |
| [Shadowfax Chaos Druids](#shadowfax-chaos-druids) | Kills chaos druids for valuable herbs |
| [Shadowfax Ectofuntus](#shadowfax-ectofuntus) | Runs the full Ectofuntus prayer loop, including charter-ship banking |
| [Shadowfax Gilded Altar](#shadowfax-gilded-altar) | Offers bones in other players' houses, with an option to unnote at Phials |
| [Shadowfax Magic Trainer](#shadowfax-magic-trainer) | Splashes curses on the Varrock monk, buying runes as it goes |
| [Shadowfax Bolt Enchanter](#shadowfax-bolt-enchanter) | Enchants crossbow bolts until they're gone |
| [Shadowfax Walker](#shadowfax-walker) | A search-as-you-type travel picker on top of the web walker |
| [Shadowfax Account Starter](#shadowfax-account-starter) | Starts a fresh account on the Lumbridge cows, with scimitar upgrades paid for in cowhides |

### Shadowfax Questing
`scripts/shadowfaxquesting` · 21 files, ~5,500 lines

Runs a selected list of quests back to back. It picks the next quest whose requirements are met, then quests with
what's already carried. It only goes to the bank, or the Grand Exchange, when a step can't make progress without an
item. A resumed quest is synced with the game first, by loading the guide and reading the quest journal, so a restart
carries on where it left off. Members quests run in the order of the OSRS Wiki's optimal quest guide, and a quest
whose skill, quest or combat requirements aren't met yet waits until they are.

**Free-to-play quests (22):** Cook's Assistant, Sheep Shearer, Romeo & Juliet, The Restless Ghost, Rune Mysteries,
Imp Catcher, Doric's Quest, Witch's Potion, Goblin Diplomacy, Ernest the Chicken, X Marks the Spot, Misthalin Mystery,
The Ides of Milk, Pirate's Treasure, The Corsair Curse, Vampyre Slayer, Prince Ali Rescue, Demon Slayer, The Knight's
Sword, Black Knights' Fortress, Below Ice Mountain and Dragon Slayer I.

**Members quests (28):** Gertrude's Cat, Dwarf Cannon, Waterfall Quest, Tree Gnome Village, Witch's House, The
Tourist Trap, Druidic Ritual, Recruitment Drive, Fight Arena, Plague City, Monk's Friend, Hazeel Cult, Biohazard,
Tower of Life, Tribal Totem, Death Plateau, Merlin's Crystal, Holy Grail, Murder Mystery, The Grand Tree, Rag and Bone
Man I, Priest in Peril, Nature Spirit, Ghosts Ahoy, Making History, Animal Magnetism, Lost City and Fairytale I -
Growing Pains.

Quest-specific support classes handle what a generic step can't do. Some examples:
- **Dragon Slayer I:** plans melee gear by Attack and Defence level, and fetches and wears the anti-dragon shield
  early. It packs food for Melzar's Maze and for Elvarg, uses Protect from Magic against Elvarg, and retreats to
  restock rather than fight on with no food.
- **Lost City and Holy Grail:** share an Entrana trip that banks all weapons and armour and boards the boat directly,
  so the walker can't sail straight back. Lost City then casts spells by hand with no staff (autocast needs one),
  kills zombies for a bronze axe, finds a safespot behind a fungus for the Tree Spirit, and teleports out. Holy Grail
  wields Excalibur for the Black Knight Titan, since only it can land the killing blow.
- **Recruitment Drive:** banks everything worn and carried, since Sir Tiffy takes nothing into the tests.
- **Tree Gnome Village:** passages in and out of the Khazard tower over the crumbled wall (its door is locked
  and the walker only knows the door), and down the ladder with the orb.
- **Witch's House:** does both halves of the mouse step (cheese in the hole, magnet on the mouse), then walks the
  garden's hedge paths round the perimeter to the fountain, the shed and back out, holding still whenever the witch
  is close. The route was traced on the walker's collision map.
- **Gertrude's Cat:** picks up doogle leaves behind Gertrude's house to season the sardine, then searches the Lumberyard crates in turn, nearest the mewing first, until the kitten turns up.
- **Fairytale I:** reads Malignius Mortifer's three random items from a varbit and buys them. It runs each farmer
  conversation itself, so the investigation can't stall.
- **Ghosts Ahoy, Animal Magnetism, Priest in Peril, Nature Spirit, Prince Ali Rescue and Merlin's Crystal:** each
  has its own puzzle and item handling.

### Shadowfax Slayer
`scripts/shadowfaxslayer` · 11 files, ~3,700 lines

Gets tasks from Turael, Vannaka or Nieve and completes them.
- **Planning:** reads the task the same way the enchanted gem does, then picks a guide entry: location, gear setup
  (melee, ranged or magic, saved from what you're wearing) and protective gear.
- **Shopping:** works out which slayer equipment is missing, checking the inventory, equipment and bank first so
  nothing owned is bought twice, and buys it from the nearest master.
- **Supplies:** restocks food, potions, stamina potions and light sources. If the bank has none, it buys a candle and
  tinderbox and lights the candle itself. It ties the rope at the Lumbridge Swamp Caves entrance.
- **Fighting:** eats, prays, uses the right finisher on gargoyles and lizards, loots, and checks the gem now and then.
- **Skipping:** Turael-skips tasks it can't do.

### Shadowfax Merchant
`scripts/shadowfaxmerchant` · 10 files, ~2,600 lines

A Grand Exchange trader.
- **Finding trades:** a market scanner ranks flips and dip-buys from the OSRS Wiki's real-time price API, with volume
  and the GE tax (2%, capped at 5m) factored in.
- **Trading:** one GE action per game tick, most urgent first: collect offers, list what's been bought, review stale
  offers (rebid, cut the ask, stop-loss), then put free slots and coins to work.
- **Positions:** saved positions are reconciled after a restart, and only offers the script placed are ever touched.

### Shadowfax Warrior's Guild
`scripts/shadowfaxwarriorsguild` · 7 files, ~2,000 lines

Animates armour on the ground floor for tokens, then farms Kamfreena's cyclopes for each next defender tier. It shows
each new defender to her, wears it, and goes back in. After the rune defender it moves to the basement cyclopes for
the dragon defender. The best defender owned is tracked across the inventory, the equipment and the bank.

### Shadowfax Gem Crab
`scripts/shadowfaxgemcrab` · 5 files, ~2,300 lines

Fights the Gemstone Crab in Varlamore. The crab can't be killed and burrows to another mine on a timer. The script
spots a burrow in three ways, whichever comes first: the game message, the shell appearing, or the crab vanishing.
It then mines the shell and crawls through the cave to wherever the crab resurfaced.
- **Melee:** balances attack styles and uses boosting potions.
- **Ranged:** keeps the chosen ranged style and drinks ranging or bastion potions.
- **Magic:** can set the autocast spell. It drinks magic or battlemage potions, or invigorates an imbued or saturated
  heart, falling back to the potion while the heart's 7-minute cooldown runs.

### Shadowfax Bank Organizer
`scripts/shadowfaxbankorganizer` · 9 files, ~1,500 lines

Classifies every item and placeholder in the bank and plans the finished layout tab by tab and row by row. Gear sets
are kept together. It then works in phases:
1. **Collapse** every tab into the main tab.
2. **Build** each tab by dragging items into place in their final order.
3. **Verify** the result and swap anything out of place.

If the bank already matches the plan, nothing is moved.

### Shadowfax Woodcutting
`scripts/shadowfaxwoodcutting` · 5 files, ~1,400 lines

Chops the chosen tree, or on Automatic the best tree for the player's level and world, at the best known spot from
the wiki's training guides. Members-only spots are skipped on free-to-play worlds.
- **Logs:** banks them, or shift-drops them with a natural drop pattern.
- **Bird nests:** picks them up.
- **Axe:** upgrades it from the bank or the GE as levels allow.

### Shadowfax Hill Giants
`scripts/shadowfaxhillgiants` · 5 files, ~2,000 lines

Kills hill giants in Edgeville Dungeon, entering through the brass key shed. It picks up the key from the dungeon if
none is owned. It buries big bones and loots herbs above a set price. When the bank runs out of food it buys fish on
the GE, selling the cheapest banked herbs if coins run short. If it's out of food at low health, it escapes up the
nearest ladder.

### Shadowfax Chaos Druids
`scripts/shadowfaxchaosdruids` · 5 files, ~1,700 lines

Kills chaos druids in Taverley Dungeon for grimy herbs worth more than a set price, and banks at Falador. It restocks
food the same way as Hill Giants.

### Shadowfax Ectofuntus
`scripts/shadowfaxectofuntus` · 5 files, ~1,100 lines

The full Ectofuntus loop: grind bones, collect slime, worship, and claim ecto-tokens. To bank, it teleports to
Catherby, then charters a ship back to Port Phasmatys. The shared steps live in `framework/util/ectofuntus`, where the
questing script reuses them for Animal Magnetism.

### Shadowfax Gilded Altar
`scripts/shadowfaxgildedaltar` · 6 files, ~1,100 lines

Finds an open house with a gilded altar through the House Advertisement board and offers bones there. A house without
an altar is remembered and avoided, and being kicked out when the host leaves is handled. With a Rimmington house it
can unnote bones at Phials, so the bank is only needed when notes or coins run out.

### Shadowfax Magic Trainer
`scripts/shadowfaxmagictrainer` · 6 files, ~1,200 lines

Splashes the caged Monk of Zamorak in Varrock Palace with the best curse spell for the player's level. It restocks
runes from the bank first, then buys them on the GE within a budget, and stops at a target level.

### Shadowfax Bolt Enchanter
`scripts/shadowfaxboltenchanter` · 5 files, ~600 lines

Casts Enchant Crossbow Bolt through the make menu, recasting whenever the bolt count stops dropping. It stops when the
bolts or runes run out.

### Shadowfax Walker
`scripts/shadowfaxwalker` · 7 files, ~2,100 lines

A destination picker with search-as-you-type over common places, on top of Microbot's web walker. A hotkey reopens
the picker mid-walk to change course.

### Shadowfax Account Starter
`scripts/shadowfaxaccountstarter` · 5 files, ~1,500 lines

Takes a brand-new account through its first combat levels on the cows east of Lumbridge.
- **Food:** gathers raw beef and cooks it in the Lumbridge castle kitchen when health runs low, so it needs no
  supplies to start.
- **Money:** banks the cowhides, and every few Attack levels sells them on the Grand Exchange to buy the next
  scimitar from Zeke in Al Kharid. It knows the GE is locked below 100 total level and keeps collecting hides until
  then.
- **Safety:** a watchdog re-targets a stalled fight and resets a stalled walk, and StuckGuard stops it after 90
  seconds with no progress.

## Shared framework

`framework/` holds the code the scripts share. It's arranged by package, relative to
`net.runelite.client.plugins.microbot`:

| Package | What's in it |
|---|---|
| `util/questing` | The quest engine. `QuestRunner` plays one quest, one short action per tick. `QuestPlanner` works out what to carry and what's unmet. `QuestDefinition`, `StepOverride`, `QuestPassage` and `DialogueAnswer` are the per-quest hooks; a `QuestPassage` teaches the runner a quest's locked doors, crumbled walls, ladders and fixed paths (walked a few tiles at a time, waiting while a named NPC is close). `QuestHelperBridge` and `QuestJournal` connect to Quest Helper. `MeleeGear` plans weapons and armour by level |
| `util/safety` | `StuckGuard`, the watchdog every script attaches to: no progress means log out and stop |
| `util/misc` | `AttemptGuard`, which stops an action from being retried when the game state it should change isn't changing |
| `util/shopping` | `ItemProcurer` buys what's missing on the GE within a budget. `ItemNeed` describes one item a script needs. `ItemPrices` reads cached prices thread-safely |
| `util/market` | The OSRS Wiki real-time price API client, and GE tax rules |
| `util/player` | Stamina and boosting potion handling |
| `util/equipment` | `GearSetup`, a saved worn-equipment setup (plus runes and autocast for magic) |
| `util/combat` | Melee and ranged attack-style handling |
| `util/antiban`, `util/mouse` | Idle mouse habits, and a curved mouse movement model (with a test that draws the paths) |
| `util/woodcutting`, `util/ectofuntus`, `util/poh`, `util/walker`, `util/widget`, `util/inventory` | Tree and axe data, Ectofuntus steps, the House Advertisement board, ship charters, warning screens and shift-dropping |
| `questhelper` | `StepNpcResolver`, which finds a step's NPCs when Quest Helper's ID list is wrong |

### Design conventions

- **Explicit state machines.** Every script extends Microbot's `StateMachineScript`, and each class starts with a
  javadoc diagram of its states (for example `BANK → TOKENS → ENTER → CYCLOPS → SHOW → …`).
- **One action per tick.** Each tick does one thing and returns, so the loop can react to whatever happened next.
- **Never repeat blindly.** Actions are fingerprinted against the game state they should change. A script that stops
  making progress logs what failed, logs out and stops itself.
- **An overlay on every script,** showing its state, progress and rates.
- **The game wiki as the source of truth.** Mechanics, locations and item lists are taken from the OSRS Wiki and noted
  in comments where they're used.

## Changes to Microbot

`patches/microbot-core-changes.patch` holds the changes I made to 11 of Microbot's own files:

- **NPC steps:**
  - finds a step's NPC by name when Quest Helper's ID is wrong
  - clicks a close NPC directly, since the game lets you talk through bars and counters
  - turns the camera toward an NPC that's next to you but off screen
- **Transforming NPCs:** reads the options from the transformed definition.
- **Reachability:** `Rs2Reachable.isReachableFromPlayer`, a reachability check that starts from the player, cached
  per tick.
- **Doors and gates:** accepts "are you sure?" warning screens on gates and dungeon entrances, instead of timing out.
- **Mouse movement:** path-relative curve deviation that doesn't flatten short movements.
- **Ground items:** `isDespawned()` checks whether the item is still on its tile. It used to compare despawn timers,
  which made permanent world spawns (respawning ground spawns such as doogle leaves) look despawned, so no script
  would pick them up.
- **Small fixes:** in transport handling and in the Priest in Peril helper.

## Repository layout

```
scripts/      one folder per script (the plugin packages)
framework/    shared utilities, by package (plus one test under framework/test)
patches/      a diff of my changes to Microbot's own files
```

## Building

This repository isn't a standalone project. The code is plugin source for the Microbot client, and was written
against Microbot commit `cd1a05e4` (release 2.6.55, November 2026). To build it:

1. Clone Microbot and check out `cd1a05e418c3ea17941ec57fb95fa1fb8d5fdc42`.
2. Copy `scripts/*` and `framework/*` (except `framework/test`) into
   `runelite-client/src/main/java/net/runelite/client/plugins/microbot/`. Copy `framework/test` into the matching
   `runelite-client/src/test/java/...` path.
3. Run `git apply patches/microbot-core-changes.patch` from the Microbot root.
4. Build with `./gradlew :client:compileJava`.

Again, this is for study only: please don't run it on a live account.

## License

BSD 2-Clause, the same as Microbot and RuneLite, which this code builds on. See [LICENSE](LICENSE).
