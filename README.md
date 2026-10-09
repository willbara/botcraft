# MCFriends

AI companions for Minecraft **1.20.1 (Fabric)** that join the server as real players. They follow you or live on their own, chop down whole trees, mine (caves first), craft, smelt, hunt, cook, help each other out, fight monsters, dig in for the night, sleep when you sleep, remember things, and chat with you and with each other. There are no API keys, LLMs or native libraries, and the only dependency is Fabric API. Everything runs server-side, so it works on dedicated servers and in singleplayer, and clients don't need the mod.

The one network call is optional: looking up the skin of a registered Minecraft account by name, which goes to Mojang the same way player heads do. It can be turned off with `allowSkinLookup`.

## Versions (pinned in `gradle.properties`)

Checked on 2026-10-09 against `meta.fabricmc.net` / `maven.fabricmc.net`:

| Component | Version | Notes |
|---|---|---|
| Minecraft | 1.20.1 | |
| Yarn | 1.20.1+build.10 | latest Yarn for 1.20.1 |
| Fabric Loader | 0.19.5 | latest; mod requires >= 0.14.21 |
| Fabric API | 0.92.12+1.20.1 | latest for 1.20.1 |
| Fabric Loom | 1.18.3 (`net.fabricmc.fabric-loom-remap`) | needs Gradle 9.7+ running on Java 25 |
| Gradle | 9.7.1 (wrapper) | |
| Java | compiles with a JDK 17 toolchain (`--release 17`) | auto-provisioned through the Foojay resolver |

## Install

1. Build: `./gradlew build`. The jar is `build/libs/mcfriends-<version>.jar`.
2. Put it in `mods/` next to Fabric API 0.92.x for 1.20.1.
3. Singleplayer: create the world with **Allow Cheats: ON**, because `/companion` needs permission level 2.

To deploy to a test instance, run `./gradlew deployToTest`. It builds, deletes any older `mcfriends*.jar` / `companions*.jar` from `MC_DIR/mods`, and copies the new jar in. `MC_DIR` comes from the `MC_DIR` environment variable, or else from `mc_dir` in `gradle.properties`.

## Commands (permission level 2 by default)

| Command | Effect |
|---|---|
| `/companion spawn <name> [skin]` | Spawns a companion in front of you, with you as its owner. Re-using the name of a dismissed companion restores its inventory. |
| `/companion skin <name> <skin>` | Changes the skin. The companion leaves and rejoins in place. |
| `/companion info <name>` | Debug view: personality, orders, job queue, current drive scores, memory. |
| `/companion remove <name>` | Dismisses the companion and forgets it. Its playerdata file is kept. |
| `/companion list` | Shows name, owner, position, health, food and current activity. |
| `/companion stop <name>` | Stays put and drops any job. |
| `/companion task <name> <text>` | Anything you could say in chat, e.g. `get 10 logs then craft a stone pickaxe` |

**Skins** are resolved in this order:
1. **Built-in default skins**: `alex`, `ari`, `efe`, `kai`, `makena`, `noor`, `steve`, `sunny`, `zuri`, optionally with `_slim` or `_wide`. This is fully offline: the client picks a default skin from the UUID hash, so the mod chooses a UUID that lands on the requested skin.
2. **An online player's name**: their signed skin is copied. Offline.
3. **Any registered Minecraft account**: for example `/companion spawn herobrine herobrine`. The skin is looked up from Mojang off the server thread, just like player heads. The signed result is stored with the companion, so restarts don't need the network. Companions keep their own UUID, never the account's.

If `skin` is omitted, the skin is whatever the UUID hash gives. With `autoSkinFromName: true`, the companion's own name is tried as an account first.

## Talking to companions

Companions only respond when you **say their name** ("Bob, follow me"), or address all of yours ("everyone stop", "all of you, follow me"). Chat that doesn't name them is ignored, so an order for one companion never interrupts another. Only the owner can give orders; anyone can ask questions.

**Chain orders** with "then", "and then", "after that", or commas: "Bob, go get logs and then mine for coal". Start with "also …" to add to the current plan instead of replacing it.

| Say | What happens |
|---|---|
| follow me / let's go | follow you (the standing order) |
| stop / stay / wait | stay put, drop all jobs |
| come here / get over here | walk to where you are |
| defend me / protect me / help me fight | follow, and also attack mobs targeting you |
| never mind / cancel / forget it | drop the job queue, back to the standing order |
| get / gather / chop 10 logs, chop some trees | fell trees (whole trees) until it has that many logs |
| mine 8 coal / mine for iron / dig some stone | mine ore or cobblestone, making the needed pickaxe first if necessary |
| craft a wooden pickaxe / make 4 torches / craft an iron pickaxe | craft it, including intermediate items, a crafting table, missing materials, and smelting |
| smelt 5 iron / cook the beef / cook some food | smelt or cook in a furnace (finds a free one or places its own) |
| hunt some cows / kill 2 pigs / get us some food | hunt (fair game only), and with "get food" also cook it |
| do your own thing / go explore / you're free | free mode: lives and progresses on its own around home (any other order ends it) |
| go to steve / follow alex / steve, give alex your logs / bring alex 5 coal | the same orders, aimed at another companion or player |
| bring me 10 logs / bring me 5 iron | get them, then walk over and hand them to you |
| give me the logs / give me everything | hand over now, or walk to you first if you're far |
| this is home / set home here, go home | remember a place and go back to it |
| go to sleep | find a free bed and sleep (only at night) |
| where are you? | coordinates, distance and direction from you, biome |
| what do you have? | inventory summary |
| what have you found? / seen any diamonds? | ores and trees it remembers seeing, with coordinates |
| how many logs have you chopped? / stats | lifetime stats: logs, ores, crafts, kills, deaths |
| status / what are you doing? | current activity, queued jobs, health, food |
| what can you do? / help | short list of abilities |
| hi / thanks | small talk; it remembers people it has met |
| tell me a joke / who are you / what time is it / bye | small talk |
| what's your favourite food? / do you like creepers? | each companion has stable tastes and opinions |
| good job / you suck / sorry / i love you | it reacts in character |

Names tolerate one typo in a direct address ("herobrin, follow me"), for names of 4+ letters. Command words tolerate typos too ("folow", "mien", "crfat"), and so does common chat shorthand ("u", "ur", "pls", "gimme"). Purpose clauses keep the goal: "make a furnace to smelt iron" means smelt iron, because the planner works out the furnace itself. If no pattern fits, a keyword fallback catches phrasings like "could you start following me around".

Item names are resolved against the game. Wood words mean chopping, ores and stone mean mining, and anything with a crafting recipe means crafting. For example, "get me sticks" crafts sticks.

**Unprompted remarks:** joining or coming back, nightfall and morning, low health, a creeper nearby, starving with no food, each step of a plan done, a plan failing (and why), arriving, fights and kills, fleeing unarmed, no bed to sleep in, its own initiative ("I'm going to craft a wooden sword, I don't want to be caught without a weapon"), and welcoming the owner back. How often depends on chattiness.

## Behaviour summary

- **Pathfinding** (Baritone-style). A* in game-tick costs over these moves:
  - walk, diagonal, jump up, drop down;
  - dig through, dig down, **pillar up** on throwaway blocks, bridge gaps;
  - climb ladders and vines, **parkour** across 1–2 block gaps, swim, open doors.

  If no normal path exists, for example it has dug itself into a pit, **escape mode** lets it dig natural terrain and pillar up to get out. While walking, it cuts straight across open ground instead of zig-zagging node to node.
- **Combat**: armed with at least 4 melee damage, it fights any hostile mob, creepers included; it backs off while a creeper hisses. Unarmed, it runs, and never off a cliff. At low health it retreats. It wears its best armour. Companions **defend each other**: a mob after a nearby companion counts as a threat.
- **Choosing targets** (like Baritone): all candidate ores or trees go into one composite path goal. A* reaches the cheapest one to actually get to, and the companion locks onto it until it's done, so it never circles between candidates.
- **Trees**: once started, a tree is finished, pillaring up on dug dirt for the high logs and digging the pillar back down. It prefers trees about the size it needs, and two companions never fight over one tree.
- **Mining**, in this order:
  1. ore it can already see (exposed in caves, ravines or cliffs), including spots it or a friend remembers;
  2. buried ore within about 6 blocks, which is quicker to dig to;
  3. otherwise it **finds a cave**, goes in and looks again. For deep ores (diamonds, redstone, gold, lapis) it searches wider and prefers caves near the ore's depth, even if they're further away;
  4. if there's no good cave, it **staircases down** to the ore's depth, then **strip-mines** forward, looking again every 16 blocks;
  5. once at the right depth, it digs straight to any buried ore it senses nearby.

  It keeps at it for up to 25 minutes on deep ores before giving up.
  Natural terrain only, never next to fluids, never through walls.
- **Crafting and smelting**: a recipe planner over the server's own recipes. Missing materials become gather, mine, hunt or smelt jobs, so "craft an iron pickaxe" works from nothing. It uses a free furnace (never one holding your items) or places its own.
- **Hunting**: adult, unnamed, un-leashed, not-penned animals only, and never the last pair of a species nearby. Raw meat gets cooked.
- **Picking up after itself**: every block a companion breaks and every animal or mob it kills marks a drop spot. Before starting its next job (and now and then when idle) it collects anything of its own still lying around: logs that fell out of reach, ore that dropped into a hole, its broken crafting table, meat. Items someone else threw (your drops, a friend's delivery) are never taken. Anything it can't reach is let go of, and the log says what it picked up and what it missed.
- **Not getting lost**: in follow mode with nobody to follow (you're offline, in spectator, or in another dimension), a companion that's done or whose job failed heads home if it's more than 24 blocks away. If it can't find a path back to you (for example deep in a cave), it uses the teleport-to-owner fallback after a few failed tries.
- **Getting out of caves**: when the way out is above and the normal search fails underground, it digs straight up like a player: it breaks the block overhead, jumps, and places a block underneath. It stops before fluids, falling blocks or anything that isn't natural terrain.
- **Looking further**: chopping and hunting don't give up when nothing is in range. They go to a remembered spot, climb to the surface if underground, or walk about 40–48 blocks in one direction and look again (up to 5 trips).
- **Spreading out**: each companion claims the tree, ore, cave or animal it's working on. When choosing, spots near someone else's claim, or near any player or companion standing there, are avoided while there's another choice, so they don't get in each other's (or your) way. Trees near others count as further away rather than off-limits, so a busy wood never means "no trees".
- **Tools first**: before chopping, mining or hunting, it checks for the right tool (axe, pickaxe, sword). If it can make or upgrade one from what it carries, up to iron, it does so first, smelting carried raw iron if it has fuel and a furnace (or the cobblestone for one). It never spends diamonds on tools; those are usually what you asked for. It doesn't prep tools for a step of a bigger plan (so iron meant for a pickaxe doesn't become an axe), and it doesn't make a pickaxe too weak for the ore it's after. Mining without any usable pickaxe still asks a friend for one, then gathers the materials. If a tool step fails, it gets on with the job anyway.
- **Helping each other**: when a job is missing something worth a trip (coal, ores or ingots, gems, wool, a pickaxe, food), a companion asks the nearest *idle or free-roaming* friend who can spare it ("Hey Ann, could you spare 3 coal?"). The friend brings it over and goes back to what they were doing.
  - Cheap things (cobblestone, logs, dirt, planks, sticks) it always gets itself.
  - At most one request every 3 minutes, and the same item at most every 10 minutes.
  - A helper helps one companion at a time, then gets a 2-minute breather, and is never pulled off one of your orders.
  - Handed-over items can only be picked up by the recipient.
  - If nothing arrives, the companion quietly gets it itself.

  Only spares are given, and tools only if the friend has two. Companions never jump on farmland (yours stays safe).
- **Sleep**: it goes to bed when you do (so the night can skip). In free mode with no bed, an armed companion guards home, and an unarmed one **digs in**: three blocks down, roofed, until morning.
- **Free mode / Life planner**: a player-like routine around home:
  - night: bed or shelter;
  - food: cook raw meat, ask a friend, or hunt;
  - progression: the wooden and stone tool set, then smelting carried raw iron, an iron pickaxe, an iron sword, iron armour piece by piece, then a bed;
  - **whatever suits where it is**: chop with an axe in a forest; mine coal, iron, then diamonds (with an iron pickaxe) in a nearby cave; turn spare coal into torches;
  - otherwise it revisits a remembered cave or wanders.

  It says what it's doing; a goal that fails is put aside for a while. **Any order ends free mode** (back to following you), so its plans never compete with yours. In follow mode it only does light tool upkeep near you. Tune with `initiative`.
- **Memory** (`<world>/mcfriends/memory/<name>.json`): places (home, bed, furnace, last death), ore/tree/diamond sightings, lifetime stats, biomes visited, people met.
- **Speech**:
  - Lines come from `dialogue.json`, a large pool with no repeats.
  - Each companion has its own voice (casual or proper, calm or excited, filler words, emoticons, a nickname for you), so the same line sounds different from different companions.
  - It remarks unprompted on nightfall, morning, rain and thunder, new biomes, low health, creepers, you getting hurt or dying, diamonds, plan progress, kills, and idle musings.
- **Between companions**: they greet each other, banter when idle (weather, work, favourites, jokes, complaints, and sharing where they found ores, which the listener remembers), praise each other's kills, mourn deaths, and come to each other's help.

### Customising dialogue

Create `config/mcfriends-dialogue.json` in the same format as [`dialogue.json`](src/main/resources/mcfriends/dialogue.json). Any key you define replaces that key's default lines. Placeholders include `{player}`, `{nick}`, `{name}`, `{friend}`, `{mob}`, `{item}`, `{count}`, `{plan}`, `{result}`, `{reason}`, `{biome}`, `{time}` and `{fav}`. Restart to reload.

## Config: `config/mcfriends.json`

Created on first launch. Missing keys are filled in with defaults.

| Key | Default | Meaning |
|---|---|---|
| `maxCompanions` | 0 | server-wide limit (0 = unlimited) |
| `aiTickInterval` | 5 | drives are re-scored every N ticks (body control runs every tick) |
| `tickBudgetMs` | 2.0 | total AI time per server tick, shared by all companions |
| `pathNodesPerTick` / `maxPathNodes` | 600 / 20000 | A* time slicing and search limit |
| `chatEnabled` | true | replies and unprompted remarks |
| `griefing` | **false** | when off, navigation only breaks natural leaves and only natural trees are chopped. Containers and block entities are never broken, whatever this is set to. |
| `allowBridging` | true | may place dirt or cobblestone to cross 1-block gaps |
| `followDistance` | 3.5 | comfortable distance from the owner |
| `teleportToOwnerDistance` | 48 | teleports to the owner when farther than this or in another dimension; 0 disables |
| `treeSearchRadius` | 32 | blocks |
| `respawnDelayTicks` | 100 | delay before a dead companion rejoins |
| `forceSurvival` | true | companions join in survival mode |
| `allowSkinLookup` | true | allow looking up registered accounts' skins from Mojang |
| `autoSkinFromName` | false | with no skin argument, try the companion's own name as an account |
| `initiative` | 0.5 | how readily idle companions set their own goals (0 = never); in free mode it gates the whole Life planner |
| `sleepWithOwner` | true | go to bed when the owner sleeps |
| `commandPermissionLevel` | 2 | |
| `verboseAiLogging` | false | logs AI decisions at INFO instead of DEBUG, so they show in `latest.log` |

## Architecture

```
com.mcfriends
├─ MCFriends            entrypoint: config, lifecycle/tick/chat/command registration
├─ CompanionManager     all companions: spawn/remove, restore on start, death -> rejoin, autosave
├─ Companion            one companion: data + player + subsystems; per-tick pipeline
├─ entity/   CompanionPlayer (ServerPlayerEntity subclass), FakeClientConnection (drops packets),
│            FakePlayers (vanilla join/leave), SkinUtil, CompanionData/Store (JSON registry)
├─ action/   ActionController (look easing, aim jitter, reaction delay, movement input,
│            survival mining, use/place, attack, eat, drop/give), Inventories (tool/weapon/food choice)
├─ nav/      Terrain (block classes, tick costs), PathFinder (time-sliced A*, Baritone-style
│            moves), Navigator (execution, string pulling, escape mode, stuck recovery),
│            BreakPolicy, Goal
├─ perception/ Perception (threats, owner, time, light, inventory summary), BlockScanner, CaveFinder
├─ brain/    Brain (utility selection + interrupt stack), Drives, Objective, Personality,
│            JobQueue + Jobs (persisted job specs, prerequisites, sourceSpec), Life (free mode),
│            Sharing (asking friends), task/ (Follow, Stay, Idle, GoTo, GatherLogs, Mine,
│            Craft, Smelt, Hunt, Place, Deliver, Await, Sleep, Shelter, Eat, Fight,
│            Flee/Retreat, Pickup + Spots helpers)
├─ knowledge/ OreKind, ItemNames, CraftPlanner, Smelting, Animals
├─ memory/   Memory (places, sightings, stats, people; per-companion JSON)
├─ chat/     IntentParser (clauses, intents, typos), ChatRouter (intents -> jobs/replies),
│            SmallTalk, Templates (+ dialogue.json), Voice, Speech, EventWatcher, Social, CommandTasks
├─ command/  /companion
└─ gametest/ headless GameTests
```

**Fake player.** `CompanionPlayer` joins through the vanilla `PlayerManager#onPlayerConnect`, so it gets a name tag, a tab-list entry, join and leave messages, and vanilla playerdata persistence (position, inventory, health, XP). Its connection has no channel and drops outgoing packets. Because the network handler is never ticked, the player calls `playerTick()` itself, which runs real server-side physics. `fall()` is routed through `handleFall` so fall damage applies. Movement exhaustion is applied from server-side motion. No mixins are used.

**Each tick:** perception → brain (drives every N ticks, current task every tick) → navigator → actions. Actions turn intentions into the same inputs a client produces: `forwardSpeed`, `sidewaysSpeed`, jumping, sprinting, sneaking, rotation, and `ServerPlayerInteractionManager` calls.

**Brain.** Drives score objectives from 0 to 1:
- safety → FIGHT or FLEE
- health → RETREAT
- hunger → EAT
- owner → FOLLOW
- night → FOLLOW or STAY
- work → WORK
- idle → IDLE or STAY

Interrupt objectives (fight, flee, retreat, eat) are pushed on top of the stack and pause the current activity, which resumes when they finish. Switching base activities needs a hysteresis margin. Every task has a timeout. Personality biases the scores: bravery shifts fight versus flee, helpfulness raises following and working, curiosity drives idle strolls, and chattiness sets how often a companion speaks.

**Jobs.** Orders become a queue of short job specs, such as `gather logs 16`, `mine coal 8`, `craft stone_pickaxe 1` or `deliver logs 16`. The queue is persisted, so a plan survives restarts. A job that's missing something (a pickaxe, materials, a crafting table) queues the prerequisite jobs in front of itself, with a loop guard. If a job fails, the rest of the plan is dropped and the companion says why.

**Persistence.** `<world>/mcfriends/companions.json` stores name, UUID, owner, skin (including fetched textures), personality, mode, defend flag, the current job and the queue. `<world>/mcfriends/memory/<name>.json` stores memory. Everything else is in vanilla playerdata.

## Development

- `./gradlew build`: compiles and runs the JUnit tests (intent parser, templates, skin UUIDs).
- `./gradlew runGametest`: runs a headless server with 35 GameTests covering movement, pathfinding (shafts, ladders, parkour, diving, digging out), gathering, mining, crafting, smelting, hunting, combat, free mode, shelter, chat, banter, sharing, spreading out, tool prep, searching for animals, picking up drops and heading home when lost. It resets its world on every run.
- `./gradlew runServer`: dev dedicated server in `run/server`. Type commands into the console.
- To see AI decisions, set `verboseAiLogging: true` and grep `latest.log` for `[<name>]`.

See [docs/TESTING.md](docs/TESTING.md) for the manual checklist.

## Known limitations

- **Skins**: a registered-account skin needs network access once per name. Without it, the companion falls back to a default skin.
- **Death and reskinning**: a dead companion leaves and rejoins after `respawnDelayTicks`, and changing skins also rejoins, so chat shows a leave/join pair.
- **They count as players**:
  - They appear in the tab list and player count, and they get stats and advancements.
  - They're added to `usercache.json` like any joining player.
  - The night only skips if every companion near you finds a free bed (or lower `playersSleepingPercentage`).
- **Spawn protection** on dedicated servers stops non-op companions from breaking blocks near spawn.
- **Griefing off** is conservative because vanilla doesn't record who placed a block:
  - Wood only comes from natural trees.
  - Normal pathing only breaks natural leaves; mining jobs and escape mode only dig natural terrain.
  - Pillars and bridges they place while travelling are left in the world.
- **Navigation**:
  - No boats, elytra, water buckets, or parkour beyond 2-block gaps.
  - Searches are limited to 96 blocks (partial paths and re-plans after that).
  - Very large height differences (e.g. getting home from a deep cave) can still fail.
- **Free mode** is a first step:
  - Its goal list is fixed. There's no long-term planning or base building yet.
  - It doesn't light up caves or store items in chests.
  - Unarmed nights mean digging in, not fighting.
- **Crafting**: crafting-table and furnace recipes only (no smithing, brewing or enchanting).
- **Farming** was removed for now (it'll come back reworked).
- **Combat**: melee only, no bows or shields.
- **Chat**:
  - Lines are server system messages formatted as `<Name> text`, because a fake player can't sign chat.
  - Understanding is pattern-based (no language model), so unusual phrasing gets "Sorry, what was that?". The dialogue file makes the wording easy to extend.
- A `/kick`ed companion stays registered. It comes back on restart or with `/companion spawn`.
