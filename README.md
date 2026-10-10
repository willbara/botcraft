# MCFriends

AI companions for Minecraft **1.20.1 (Fabric)** that join the server as real players. A companion can follow you or live on its own: it chops whole trees, mines (caves first, whole veins), crafts, smelts, hunts and cooks, fights monsters, sleeps when you sleep, helps the other companions, remembers what it has seen, and chats with you and with the others.

- **Server-side only.** It works on dedicated servers and in singleplayer, and clients don't need the mod.
- **Self-contained.** There are no API keys, language models or native libraries. The only dependency is Fabric API.
- **One optional network call.** Looking up the skin of a registered Minecraft account goes to Mojang, the same way player heads do. Turn it off with `allowSkinLookup`.

This document describes version **0.6.2**.

---

## Contents

1. [Installation](#installation)
2. [Commands](#commands)
3. [Talking to companions](#talking-to-companions)
4. [What companions can do](#what-companions-can-do)
5. [Limitations](#limitations)
6. [Configuration](#configuration)
7. [Architecture](#architecture)
8. [Development](#development)

---

## Installation

1. Build with `./gradlew build`. The jar is `build/libs/mcfriends-<version>.jar`.
2. Put it in `mods/` next to Fabric API 0.92.x for 1.20.1.
3. In singleplayer, create the world with **Allow Cheats: ON**, because `/companion` needs permission level 2.

To deploy to a test instance, run `./gradlew deployToTest`. It builds the jar, deletes any older `mcfriends*.jar` or `companions*.jar` from `MC_DIR/mods`, and copies the new jar in. `MC_DIR` comes from the `MC_DIR` environment variable, or else from `mc_dir` in `gradle.properties`.

### Pinned versions (`gradle.properties`)

| Component | Version | Notes |
|---|---|---|
| Minecraft | 1.20.1 | |
| Yarn | 1.20.1+build.10 | latest Yarn for 1.20.1 |
| Fabric Loader | 0.19.5 | the mod requires 0.15.0 or later (it uses the bundled MixinExtras) |
| Fabric API | 0.92.12+1.20.1 | latest for 1.20.1 |
| Fabric Loom | 1.18.3 (`net.fabricmc.fabric-loom-remap`) | needs Gradle 9.7+ running on Java 25 |
| Gradle | 9.7.1 (wrapper) | |
| Java | JDK 17 toolchain (`--release 17`) | auto-provisioned through the Foojay resolver |

---

## Commands

All commands need permission level 2 by default (`commandPermissionLevel`).

| Command | Effect |
|---|---|
| `/companion spawn <name> [skin]` | Spawns a companion in front of you, with you as its owner. Re-using the name of a dismissed companion restores its inventory. |
| `/companion skin <name> <skin>` | Changes the skin. The companion leaves and rejoins in place. |
| `/companion info <name>` | Debug view: personality, orders, job queue, current drive scores, memory. |
| `/companion remove <name>` | Dismisses the companion and forgets it. Its playerdata file is kept. |
| `/companion list` | Name, owner, position, health, food and current activity of every companion. |
| `/companion stop <name>` | Stays put and drops any job. |
| `/companion task <name> <text>` | Anything you could say in chat, for example `get 10 logs then craft a stone pickaxe`. |

### Skins

A skin argument is resolved in this order:

1. **Built-in default skins:** `alex`, `ari`, `efe`, `kai`, `makena`, `noor`, `steve`, `sunny`, `zuri`, optionally with `_slim` or `_wide`. This is fully offline. The client picks a default skin from the UUID hash, so the mod chooses a UUID that lands on the requested skin.
2. **An online player's name:** their signed skin is copied. Also offline.
3. **Any registered Minecraft account,** for example `/companion spawn herobrine herobrine`. The skin is fetched from Mojang off the server thread and stored with the companion, so restarts don't need the network. The companion keeps its own UUID, never the account's.

With no skin argument, the skin is whatever the UUID hash gives. With `autoSkinFromName: true`, the companion's own name is tried as an account first.

---

## Talking to companions

### Addressing

A companion only responds when you **say its name** ("Bob, follow me") or address all of yours ("everyone stop", "all of you, mine coal"). Chat that doesn't name a companion is ignored, so an order for one never interrupts another.

- Names tolerate one typo in a direct address ("herobrin, follow me"), for names of four or more letters.
- Only the owner can give orders. Anyone can ask questions.

### Combining orders

- **Chain orders** with "then", "and then", "after that", commas, or "and" followed by a verb: "Bob, get logs, make planks, then make a crafting table".
- **"After you X, Y"** runs X first: "after you get logs, make a crafting table".
- **Add to the current plan** instead of replacing it by starting with "also …" or "when you're done …".
- **Purpose clauses keep the goal:** "make a furnace to smelt iron" means "smelt iron", because the planner works out the furnace itself.

### Orders

| Say | What happens |
|---|---|
| follow me / let's go | Follow you (the standing order). |
| stop / stay / wait here | Stay put and drop all jobs. |
| come here / come back | Walk to where you are. |
| defend me / protect me / guard the base | Follow you and attack mobs that target you. |
| never mind / cancel / forget it / drop everything | Drop the job queue and return to the standing order. |
| get / gather / chop / grab 10 logs | Fell whole trees until it has that many logs. |
| mine 8 coal / mine for iron / find diamonds / look for coal | Mine the ore (or stone), making the right pickaxe first if needed. |
| craft a wooden pickaxe / make 4 torches / make yourself a sword | Craft it, including intermediate items, a crafting table, missing materials and smelting. |
| smelt 5 iron / cook the beef / cook some food | Smelt or cook in a furnace (a free one, or one it places). |
| hunt some cows / kill 2 pigs / get some food / get leather | Hunt fair game. "Food" also means cooking it. |
| bring me 10 logs / I need some iron / I want an iron pickaxe | Get it if necessary, then walk over and hand it to you. |
| I'm hungry | Bring you food. |
| we need coal / we're out of food | Go and get it ("food" means hunt and cook). |
| wood / some wood please / iron pickaxe please | Naming a thing is a request for it. |
| give me the logs / give me everything | Hand it over now, walking to you first if you're far away. |
| go to steve / follow alex / steve, give alex your logs / bring alex 5 coal | The same orders, aimed at another companion or player. |
| this is home / set home here | Remember this place as home. |
| go home / come home | Go home. |
| go to sleep / go to bed | Find a free bed and sleep (only at night). |
| do your own thing / go explore / you're free | Free mode: live and progress on its own around home. Any other order ends it. |

Item names are resolved against the game's own item list:
- wood words mean chopping;
- ores and stone mean mining;
- leather, feathers, wool and raw meat mean hunting the right animal;
- anything with a crafting recipe means crafting (for example, "get me sticks" crafts sticks).

### Questions and small talk

| Say | Answer |
|---|---|
| where are you? | Coordinates, distance and direction from you, and the biome. |
| what are you doing? / what's your task? / what's the plan? / what you doing | Current activity, queued jobs, health and food. |
| what do you have? / show me your inventory | Inventory summary. |
| what have you found? / seen any diamonds? | Ores and trees it remembers seeing, with coordinates. |
| how many logs have you chopped? / stats | Lifetime stats: logs, ores, crafts, kills, deaths. |
| what can you do? / help | A short list of abilities. |
| hi / thanks / bye / tell me a joke / who are you / what time is it | Small talk. It remembers people it has met. |
| what's your favourite food? / do you like creepers? | Each companion has stable tastes and opinions. |
| good job / you suck / sorry / i love you | It reacts in character. |

The parser also copes with:
- **typos in command words:** "folow", "mien", "crfat";
- **chat shorthand:** "u", "ur", "pls", "gimme", "wyd";
- **filler words:** "for me", "please", "yourself";
- **anything else:** a keyword fallback catches phrasings like "could you start following me around".

---

## What companions can do

### Being a player

- **Real player entities.** Each companion joins through the vanilla login path, so it has a name tag, a tab-list entry, join and leave messages, survival physics, hunger, fall damage, armour, stats and advancements. Its inventory, health and position are saved in normal playerdata.
- **Human-like control.** Looking turns smoothly with slight aim jitter, reactions have a short delay, and movement uses the same inputs a client sends (walk, sprint, jump, sneak).
- **No player slots used.** A world full of companions never turns a real player away with "server is full".
- **Personality.** Chattiness, bravery, curiosity and helpfulness are random but stable per name. They shape how often a companion speaks, whether it fights or flees, how much it wanders, and how readily it works.

### Moving around

- **Pathfinding (Baritone-style).** A* is weighted in game-tick costs and spread over ticks within a time budget. The moves are:
  - walk, diagonal step, jump up one block, drop down;
  - dig through and dig down (natural terrain only);
  - pillar up and bridge gaps with throwaway blocks;
  - climb ladders and vines;
  - parkour across 1–2 block gaps;
  - swim;
  - open doors and fence gates.
- **Smooth walking.** On open ground it walks straight at the furthest reachable step instead of zig-zagging from node to node.
- **Avoiding hazards.**
  - **Lava** and fire are strongly avoided.
  - **Water:** swimming costs extra, being fully underwater costs much more, and currents (rivers, waterfalls) cost more still. Paths go around water whenever a dry route exists.
- **Getting unstuck.**
  - **Stuck while walking:** it wiggles, then re-plans around the spot that stopped it.
  - **No normal path:** escape mode digs through natural terrain and pillars.
  - **Underground with the goal above:** it digs straight up like a player, breaking the block overhead, jumping and placing a block underneath. It stops before fluids, sand and gravel, and anything that isn't natural terrain.
  - **Short or failed searches:** a path that ends just short of the goal re-plans, and a search that fails almost at once is retried after a moment.
- **Breathing.** When a companion's air drops below half, it stops everything and swims to the nearest air. It uses its picture of its surroundings first, then searches up to about 24 blocks through the water.

### Awareness

- **Surroundings.** Each companion keeps a rolling picture of everything within 8 blocks: ore of every kind, water, lava, and where to come up for air. It reads one horizontal layer per tick, so the whole cube refreshes about once a second at a small, steady cost.
- **Seeing like a player.** A block counts as reachable when the eyes can see any part of it within reach, not only its centre.
- **Animals in the distance.** A hunter looks up to about 96 blocks away (48 up or down) for a herd, and remembers where herds were.
- **Memory.** Places (home, bed, furnace, last death), ore, tree, cave and herd sightings, lifetime stats, biomes visited and people met are saved per companion.

### Gathering wood

- **Whole trees.** Once a companion starts a tree it finishes it. For high logs it pillars up on dirt it digs nearby, then digs the pillar back down.
- **Tree choice.** It prefers trees about the size it needs. All candidates go into one path goal; A* reaches the cheapest to get to, and the companion locks onto it.
- **Searching.** With no trees in range it goes to a remembered wood. If it's underground it climbs to the surface first; otherwise it walks about 48 blocks in one direction (away from where others are working) and looks again, up to 5 trips.

### Mining

When asked for an ore, a companion looks in this order:

1. More of the ore **right where it is** (from its surroundings), including the rest of a vein it's working on.
2. Ore it can **already see**, exposed in caves, ravines or cliffs, including spots it or a friend remembers.
3. Buried ore within about 6 blocks, which is quicker to dig to.
4. A **cave**: it goes in and looks again. For deep ores (diamonds, redstone, gold, lapis) it searches further and prefers caves near the ore's depth.
5. If there's no good cave, it **digs a staircase** down to the ore's depth, then **strip-mines** forward, looking again every 16 blocks. At the right depth it digs straight to any buried ore it senses nearby.

While mining:
- **Whole veins.** It mines every block of a vein, including blocks joined only at the corners, and steps over to reach the rest. It finishes the vein even past the requested count.
- **Ore on the way.** Any of the target ore it passes within reach while walking, exploring or heading for a cave gets mined.
- **Wet and dangerous ore.** Ore next to water (behind a waterfall, in a flooded cave) is chosen only when there's no dry ore. Ore next to lava is left alone.
- **Time limit.** It keeps at it for up to 15 minutes, or 25 for deep ores.

### Crafting, smelting and tools

- **Recipe planner.** Recipes come from the server's own crafting and furnace data. Missing materials become gather, mine, hunt or smelt jobs, so "craft an iron pickaxe" works starting from nothing.
- **Crafting tables.** If it needs a table and has none, the table's planks are counted in the plan, so it gathers enough wood in one go. It uses a table nearby when it can see and reach one, places its own otherwise, and picks it back up afterwards.
- **Furnaces.** It uses a free furnace (never one holding your items) or places its own, which it picks back up afterwards if it has a pickaxe. It needs fuel and the input; missing ones become jobs.
- **Placing blocks.** Tables and furnaces only go on solid ground (never on leaves). If there's no room, it walks somewhere more open.
- **Tools first.** Before chopping, mining or hunting it checks for the right tool (axe, pickaxe, sword).
  - **Easy upgrades:** if it can make or upgrade one from what it carries, up to iron, it does so first, smelting carried raw iron if it has fuel and a furnace (or the cobblestone for one).
  - **Diamonds are never spent on tools.** Diamonds are usually what you asked for.
  - **Materials for bigger plans are left alone,** so iron meant for a pickaxe doesn't become an axe.
  - **No pickaxe too weak for the ore** it's after.
  - **A failed tool step doesn't sink the job;** it gets on with the job anyway.
- **Weapons.** It never hunts bare-handed: with nothing better than a fist, it makes a wooden sword first. It always attacks with its best weapon or tool.

### Hunting and food

- **Fair game only:** adult, unnamed, unleashed animals that aren't penned in, and never the last two of a species nearby. A starving companion (hunger 3 drumsticks or less) with nothing to eat may take one of a pair, but never the last one.
- **Noticing animals.** While doing anything else, a companion notices groups of three or more food animals within 48 blocks and remembers where the herd is (the middle of the densest bunch). Remembered spots it can see that turn out empty are forgotten. Up to 12 herds are remembered.
- **Chasing.** Up close it runs straight at the animal, but never off a ledge; it paths around instead.
- **Searching.** It looks within 32 blocks, then for herds up to 96 blocks away (48 up or down), then at remembered herd spots. Only then does it walk off in a direction, up to 5 trips. Search trips stay inside the world border.
- **Weapons.** It never hunts bare-handed: with nothing better than a fist, it makes a wooden sword first.
- **Keeping food:**
  - **Free mode** keeps a reserve: with fewer than 6 food items it hunts and cooks when it knows where animals are, before getting hungry.
  - **Follow mode** cooks raw meat it carries, or hunts nearby, when it gets hungry with nothing to eat.
  - **Cooking:** raw meat counts as food in a pinch, but it gets cooked, since a steak fills far more than raw beef.
- **Eating.** A hungry companion eats its best food, asks a friend, or hunts.

### Picking up after itself

- **Drop tracking.** Every block a companion breaks and every animal or mob it kills marks a drop spot.
- **Collecting.** Before its next job, and every so often when idle, it collects anything of its own still lying around: logs that fell out of reach, ore that dropped into a hole, its broken crafting table, meat.
- **Other people's items.** Items someone threw (your drops, another companion's delivery) are never collected on purpose.
- **Unreachable items** are let go of, and the log says what it picked up and what it missed.

### Combat and safety

- **Fighting.** With at least 4 melee damage it fights any hostile mob, creepers included, backing off while a creeper hisses.
- **Fleeing.** Unarmed, it runs (never off a cliff). At low health it retreats from monsters that are close. Hurt with no food, it can't heal (natural healing needs a nearly full hunger bar), so it asks a friend for food or hunts.
- **Armour.** It wears the best armour it has.
- **Protecting others.** Companions defend each other: a mob going after a nearby companion counts as a threat. With "defend me", it also attacks mobs targeting you.

### Death, rejoining and getting lost

- **Respawning.** A companion that dies comes back after `respawnDelayTicks`. It returns at its bed, else home, else next to you if you're standing somewhere safe, else world spawn, always on dry, solid ground. It never reappears looking dead.
- **Getting its things back.** After respawning it goes back for what it dropped, and keeps looking for as long as the items last (5 minutes from the death), unless it died in lava, fire, the void or by drowning, or that spot has already killed it twice in 10 minutes. A death also counts as a failed goal, so free mode puts that goal aside for a while.
- **Unsafe saves.** A companion saved somewhere deadly (under water, in lava, inside a block) is moved to the nearest safe spot when the world loads.
- **Not getting lost.**
  - **Nobody to follow:** in follow mode with you offline, spectating or in another dimension, a companion that's finished or whose job failed heads home if it's more than 24 blocks away.
  - **Can't find a way back to you:** if it keeps failing to reach you (for example deep in a cave), it teleports to you. It also does this whenever it's more than `teleportToOwnerDistance` away.

### Watchdog

Every task has its own time limit, but a task can keep itself busy without getting anywhere (re-planning to the same unreachable spot, retrying the same placement). If a companion is on a job and for 3 minutes it neither moves more than a few blocks, nor breaks anything, nor gains any items (eating doesn't count), the job is abandoned with "I got stuck" and the usual failure handling takes over. Sleeping, sheltering for the night and waiting on a friend are exempt.

### Working together

- **Spreading out.** Each companion claims the tree, ore, cave or animal it's working on. When choosing, spots near someone else's claim, or near any player or companion standing there, are avoided while there's another choice.
- **Sharing.** When a job needs something worth a trip (coal, ores or ingots, gems, wool, a pickaxe, food), a companion asks the nearest *idle or free-roaming* friend who can spare it ("Hey Ann, could you spare 3 coal?"). The friend brings it over and goes back to what it was doing.
  - Cheap things (cobblestone, logs, dirt, planks, sticks) it always gets itself.
  - At most one request every 3 minutes, and the same item at most every 10 minutes.
  - A helper helps one companion at a time, then gets a 2-minute breather, and is never pulled off one of your orders.
  - Only spares are given, and tools only if the friend has two. Handed-over items can only be picked up by the recipient.
  - If nothing arrives, the companion gets the item itself.
- **Social life.** Companions greet each other and banter when idle: weather, work, favourites, jokes, complaints. They also share where they found ores, which the listener remembers. They praise each other's kills, mourn deaths and come to each other's help.

### Free mode (autonomy)

"Do your own thing" turns on a player-like routine around home:

- **Night:** sleep in a bed. Without one, an armed companion guards home and an unarmed one digs in (three blocks down, roofed) until morning. Errands still running at dusk (hunting, chopping, exploring) are dropped so it heads in instead of being caught outside.
- **Food:** cook raw meat, ask a friend, or hunt, and keep a reserve of about 6 food when animals are known nearby.
- **Progression:** wooden and stone tools, then smelting carried raw iron, an iron pickaxe, an iron sword, iron armour piece by piece, then a bed.
- **Whatever suits where it is:** chopping with an axe in a forest; mining coal, iron, then diamonds (with an iron pickaxe) in a nearby cave; turning spare coal into torches.
- **Otherwise:** it revisits a remembered cave or wanders.

It says what it's doing, and a goal that fails is put aside for a while. **Any other order ends free mode** (back to following you), so its plans never compete with yours. In follow mode it only does light tool upkeep near you. `initiative` controls how readily it sets its own goals.

### Sleep

It goes to bed when you do, so the night can be skipped.

### Speech

- **Lines** come from `dialogue.json`, a large pool that avoids repeating itself.
- **Voices.** Each companion has its own voice (casual or proper, calm or excited, filler words, emoticons, a nickname for you), so the same line sounds different from different companions.
- **Unprompted remarks** on nightfall and morning, weather, new biomes, low health, creepers, you getting hurt or dying, diamonds, each step of a plan, plan failures (with the reason), kills, fleeing, missing beds, its own initiative, and idle musings. How often depends on chattiness.
- **Customising.** Create `config/mcfriends-dialogue.json` in the same format as [`dialogue.json`](src/main/resources/mcfriends/dialogue.json); any key you define replaces that key's default lines. Placeholders include `{player}`, `{nick}`, `{name}`, `{friend}`, `{mob}`, `{item}`, `{count}`, `{plan}`, `{result}`, `{reason}`, `{biome}`, `{time}` and `{fav}`. Restart to reload.

---

## Limitations

### Players and the server

- **They count as players** (but don't use player slots):
  - They appear in the tab list, the player count and `usercache.json`, and they earn stats and advancements.
  - The night only skips if every companion near you finds a free bed, or with a lower `playersSleepingPercentage`.
- **Leave/join messages.** Dying and changing skins both make a companion leave and rejoin, so chat shows a leave/join pair.
- **Chat messages are system messages** formatted as `<Name> text`, because a fake player can't sign chat.
- **Spawn protection** on dedicated servers stops non-op companions from breaking blocks near spawn.
- **`/kick`.** A kicked companion stays registered and comes back on restart or with `/companion spawn`.
- **Chunk loading.** Each companion keeps the chunks around itself loaded, like a player, so they keep working with nobody online on a dedicated server (soak runs check this). Many companions spread far apart load correspondingly more of the world. A singleplayer world pauses when you leave it, so unattended play needs a dedicated server.
- **Skins.** A registered-account skin needs network access once per name. Without it, the companion falls back to a default skin.

### Griefing safety (`griefing: false`)

Vanilla doesn't record who placed a block, so the rules are conservative:
- wood only comes from natural trees;
- normal pathing only breaks natural leaves;
- mining jobs and escape mode only dig natural terrain;
- containers and block entities are never broken, whatever the setting.

Pillars, bridges and staircases built while travelling are left in the world. So are a crafting table it couldn't get back to, and a furnace when it had no pickaxe to break it with.

### Navigation

- **Not supported:** boats, elytra, water buckets, ender pearls, and parkour beyond 2-block gaps.
- **Search size.** One search covers about 96 blocks (`maxPathNodes`). Longer trips use partial paths and re-plans.
- **Very large height differences** are slow. Escape mode and digging straight up get there, but climbing from deep caves to a mountain top can take minutes.
- **Digging out needs the right tools and blocks.** It needs a pickaxe for stone and at least one throwaway block. A companion with an empty inventory deep underground can be stuck, just as a player would be, until the teleport-to-owner fallback applies.
- **Sealed underwater caves.** If no air is reachable within about 24 blocks of swimming, a companion can still drown. Water is avoided when planning, but not forbidden.
- **No portals.** Companions don't use Nether or End portals on their own. They follow you between dimensions by teleporting.

### Tasks

- **Crafting** covers crafting-table and furnace recipes only: no smithing, brewing, enchanting, stonecutting, blast furnaces or smokers.
- **No farming.** It was removed for now and will come back reworked.
- **No chests.** Companions don't store or fetch items in chests, so their inventories fill up.
- **No torches in caves.** Companions don't light up caves (free mode crafts torches but doesn't place them).
- **Hunting** never takes the last two animals of a species nearby (the last one when starving), so a lone cow is always left alone. There's no breeding yet, so a heavily hunted area runs out of animals. Animals beyond about 96 blocks, or more than 48 blocks up or down, aren't noticed until the companion gets closer. Fish, crops and other food sources aren't used.
- **Mining** reads ore positions from the world, like an "x-ray" of the 24-block scan area. A player would have to dig to find buried ore.
- **Tool upgrades** stop at iron.

### Combat

- **Melee only:** no bows, crossbows or shields.
- **No fighting when weak:** below 4 melee damage a companion flees instead of fighting, even from weak mobs.

### Free mode

- **Fixed goals.** The goal list is fixed. There's no long-term planning, base building, trading or exploring for structures.
- **Unarmed nights** mean digging in, not fighting.

### Understanding chat

- **Pattern-based.** Understanding uses patterns, not a language model, so unusual phrasing gets "Sorry, what was that?". Common phrasings, typos and shorthand are covered, and the dialogue file makes the wording easy to extend.
- **Hidden orders.** Orders hidden inside long sentences or questions ("would it be possible for you to maybe…") may be missed.

---

## Configuration

`config/mcfriends.json` is created on first launch. Missing keys are filled in with defaults.

| Key | Default | Meaning |
|---|---|---|
| `maxCompanions` | 0 | Server-wide limit (0 = unlimited). |
| `aiTickInterval` | 5 | Drives are re-scored every N ticks (body control runs every tick). |
| `tickBudgetMs` | 2.0 | Total AI time per server tick, shared by all companions. |
| `pathNodesPerTick` / `maxPathNodes` | 600 / 20000 | A* time slicing and search limit. |
| `chatEnabled` | true | Replies and unprompted remarks. |
| `griefing` | **false** | When off, navigation only breaks natural leaves and only natural trees are chopped. Containers and block entities are never broken either way. |
| `allowBridging` | true | May place dirt or cobblestone to cross gaps. |
| `followDistance` | 3.5 | Comfortable distance from the owner. |
| `teleportToOwnerDistance` | 48 | Teleport to the owner when farther than this, in another dimension, or unable to path back. 0 disables. |
| `treeSearchRadius` | 32 | Blocks. |
| `respawnDelayTicks` | 100 | Delay before a dead companion rejoins. |
| `forceSurvival` | true | Companions join in survival mode. |
| `allowSkinLookup` | true | Allow looking up registered accounts' skins from Mojang. |
| `autoSkinFromName` | false | With no skin argument, try the companion's own name as an account. |
| `initiative` | 0.5 | How readily idle companions set their own goals (0 = never). In free mode it gates the whole Life planner. |
| `sleepWithOwner` | true | Go to bed when the owner sleeps. |
| `commandPermissionLevel` | 2 | Permission level for `/companion`. |
| `verboseAiLogging` | false | Log AI decisions at INFO instead of DEBUG, so they show in `latest.log`. |

---

## Architecture

```
com.mcfriends
├─ MCFriends            entrypoint: config, lifecycle/tick/chat/command/event registration
├─ CompanionManager     all companions: spawn/remove, restore on start, death -> rejoin, autosave
├─ Companion            one companion: data + player + subsystems; per-tick pipeline
├─ entity/      CompanionPlayer (ServerPlayerEntity subclass), FakeClientConnection (drops packets),
│               FakePlayers (vanilla join/leave, saved-data repair), SafeSpot, SkinUtil,
│               CompanionData/Store (JSON registry)
├─ action/      ActionController (look easing, aim jitter, reaction delay, movement input,
│               survival mining, use/place, attack, eat, drop/give), Inventories (tool/weapon/food choice)
├─ nav/         Terrain (block classes, tick costs, hazards), PathFinder (time-sliced A*, Baritone-style
│               moves), Navigator (execution, string pulling, escape mode, digging out, stuck
│               recovery), BreakPolicy, Goal
├─ perception/  Perception (threats, owner, time, inventory summary), Surroundings (rolling 8-block
│               picture), BlockScanner, CaveFinder
├─ brain/       Brain (utility selection + interrupt stack), Drives, Objective, Personality,
│               JobQueue + Jobs (persisted job specs, prerequisites, optional prep), Life (free mode),
│               Sharing, Claims (spreading out), Leftovers (own drops), Watchdog (stalled jobs)
│  └─ task/     Follow, Stay, Idle, GoTo, GatherLogs, Mine, Craft, Smelt, Hunt, Place, Deliver,
│               Await, Sleep, Shelter, Eat, Fight, Flee/Retreat, Breathe, Collect, ToolPrep,
│               SearchTrips, Pickup, Spots
├─ knowledge/   OreKind, ItemNames, CraftPlanner, Smelting, Animals
├─ memory/      Memory (places, sightings, stats, people; per-companion JSON)
├─ chat/        IntentParser (clauses, intents, typos), ChatRouter (intents -> jobs/replies),
│               SmallTalk, Templates (+ dialogue.json), Voice, Speech, EventWatcher, Social, CommandTasks
├─ command/     /companion
├─ ops/         Soak (unattended soak runs), SoakStats (counters for the report)
├─ mixin/       PlayerManagerMixin (companions don't count toward the player limit)
└─ gametest/    headless GameTests
```

**Fake player.** `CompanionPlayer` joins through the vanilla `PlayerManager#onPlayerConnect`. Its connection has no channel and drops outgoing packets. Because the network handler is never ticked, the player calls `playerTick()` itself, which runs real server-side physics, and updates its chunk position itself. `fall()` is routed through `handleFall` so fall damage applies, and movement exhaustion is applied from server-side motion. The only mixin, a MixinExtras `@WrapOperation` in `PlayerManager#checkCanJoin`, makes the "server is full" check count real players only.

**Each tick:** perception (including one layer of surroundings) → job queue → brain (drives every N ticks, current task every tick) → navigator → actions. Actions turn intentions into the same inputs a client produces: movement, jumping, sprinting, sneaking, rotation, and `ServerPlayerInteractionManager` calls.

**Brain.** Drives score objectives from 0 to 1:
- breath → BREATHE
- safety → FIGHT or FLEE
- health → RETREAT
- hunger → EAT
- owner → FOLLOW
- night → FOLLOW or STAY
- work → WORK
- idle → IDLE or STAY

Interrupts (breathe, fight, flee, retreat, eat) are pushed on a stack and pause the current activity, which resumes when they finish. Switching base activities needs a hysteresis margin, and every task has a timeout. Personality biases the scores.

**Jobs.** Orders become a queue of short job specs such as `gather logs 16`, `mine coal 8`, `craft stone_pickaxe 1`, `hunt cow 3` or `deliver logs 16 to steve`. The queue is persisted, so a plan survives restarts.
- **Prerequisites:** a job that's missing something queues the prerequisite jobs in front of itself, with a loop guard.
- **Optional prep:** tool-prep jobs are optional (`?` prefix) and can fail without sinking the plan.
- **Failures:** if any other job fails, the rest of the plan is dropped and the companion says why, then heads back if it's lost.
- **Collecting:** before the next job starts, leftover drops are collected.

**Events.** Fabric's block-break and entity-death events record a companion's own drops.

**Persistence.**
- `<world>/mcfriends/companions.json`: name, UUID, owner, skin (including fetched textures), personality, mode, defend flag, the current job and the queue.
- `<world>/mcfriends/memory/<name>.json`: memory.
- Vanilla playerdata: everything else.

---

## Development

- `./gradlew build` compiles and runs the JUnit tests (intent parser, templates, skin UUIDs).
- `./gradlew runGametest` runs a headless server with 47 GameTests. It resets its world on every run. The tests cover:
  - movement and pathfinding: shafts, ladders, parkour, diving, digging out, surfacing for air;
  - gathering, mining (including whole veins) and crafting (including table budgeting);
  - smelting, and hunting (near, far, sword first, searching, noticing herds, starving, keeping a food stock);
  - combat, free mode, shelter, safe respawns, gear recovery after death, the watchdog, and heading home when lost;
  - chat, banter, sharing, spreading out, tool prep and picking up drops;
  - player slots.
- `./gradlew runSoak -PsoakMinutes=30 -PsoakCompanions=6` runs an unattended soak test. It starts a dedicated server in `run/soak` with a fresh world (seed `-PsoakSeed`, default 12345, Normal difficulty), and spawns the companions in free mode at world spawn with no player online. When time is up it writes `run/soak/soak-report.md` and stops. The report covers:
  - deaths per companion-hour, with causes;
  - stalls, finished and failed jobs (with reasons), and interrupts;
  - what each companion gathered and hunted, how far it walked, its hunger at the end, and the best pickaxe, sword and armour it reached;
  - AI time per tick, and how many chunks stayed loaded.
- `./gradlew runServer` starts a dev dedicated server in `run/server`. Type commands into the console.
- To see AI decisions, set `verboseAiLogging: true` and grep `latest.log` for `[<name>]`.

See [docs/TESTING.md](docs/TESTING.md) for the manual checklist.
