# MCFriends

AI companions for Minecraft **1.20.1 (Fabric)** that join the server as real players. They follow you, chop down whole trees, mine, craft, eat, fight monsters, sleep when you sleep, remember things, and chat. There are no API keys, LLMs or native libraries, and the only dependency is Fabric API. Everything runs server-side, so it works on dedicated servers and in singleplayer, and clients don't need the mod.

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
| craft a wooden pickaxe / make 4 torches | craft it, including intermediate items, crafting table and missing materials |
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

Item names are resolved against the game. Wood words mean chopping, ores and stone mean mining, and anything with a crafting recipe means crafting. For example, "get me sticks" crafts sticks.

**Unprompted remarks:** joining or coming back, nightfall and morning, low health, a creeper nearby, starving with no food, each step of a plan done, a plan failing (and why), arriving, fights and kills, fleeing unarmed, no bed to sleep in, its own initiative ("I'm going to craft a wooden sword, I don't want to be caught without a weapon"), and welcoming the owner back. How often depends on chattiness.

## Behaviour summary

- **Combat**: armed with at least 4 melee damage (any sword, axe, or stone-or-better tool), it fights any hostile mob, creepers included; it backs off while a creeper hisses. Unarmed, it runs. At low health with enemies around, it retreats toward you; bravery shifts that threshold. It wears the best armour it carries.
- **Trees**: once a tree is started, every reachable log comes down. For high logs it pillars up on throwaway blocks, digging a few dirt blocks first if it has none, then digs the pillar back down and collects the drops.
- **Mining**: it uses ore locations remembered from earlier scans, then scans around (cheaply, section by section), then explores by digging a staircase toward the ore's usual depth. It mines whole veins. During a mining job it may dig through natural terrain only (stone, deepslate, dirt, gravel, ores), never through built blocks or next to water or lava. It never mines through walls: it needs line of sight like a player.
- **Crafting**: a small planner uses the server's own recipes, so data packs count. It works out intermediate crafts and queues gathering or mining for missing raw materials. It can't smelt, so it will tell you iron ingots need smelting.
- **Sleep**: when you get into bed at night, companions within 48 blocks look for a free bed so the night can be skipped.
- **Initiative**: when idle near you in daytime with no orders, it sometimes sets its own goals: make a sword, then a pickaxe, then stone tools, and keep some wood. It announces them, and any order from you replaces them. Set with `initiative`.
- **Memory** (`<world>/mcfriends/memory/<name>.json`): places (home, bed, last death), ore and tree sightings, lifetime stats, people met (last seen, orders, thanks).

## Config: `config/mcfriends.json`

Created on first launch. Missing keys are filled in with defaults.

| Key | Default | Meaning |
|---|---|---|
| `maxCompanions` | 5 | server-wide limit |
| `aiTickInterval` | 5 | drives are re-scored every N ticks (body control runs every tick) |
| `tickBudgetMs` | 2.0 | total AI time per server tick, shared by all companions |
| `pathNodesPerTick` / `maxPathNodes` | 300 / 6000 | A* time slicing and search limit |
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
| `initiative` | 0.5 | how readily idle companions set their own goals (0 = never) |
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
├─ nav/      Terrain (cached block classes), PathFinder (time-sliced A*), Navigator (path
│            following, doors, break/bridge steps, stuck recovery), BreakPolicy, Goal
├─ perception/ Perception (threats, owner, time, light, inventory summary), BlockScanner
├─ brain/    Brain (utility selection + interrupt stack), Drives, Objective, Personality,
│            JobQueue + Jobs (persisted job specs, prerequisites), Initiative,
│            task/ (Follow, Stay, Idle, GoTo, GatherLogs, Mine, Craft, Deliver, Sleep,
│                   Eat, Fight, Flee/Retreat, Pickup helper)
├─ knowledge/ OreKind, ItemNames (chat phrase -> item), CraftPlanner (recipe planning)
├─ memory/   Memory (places, sightings, stats, people; per-companion JSON)
├─ chat/     IntentParser (clauses, intents), ChatRouter (intents -> jobs/replies),
│            Templates, Speech, EventWatcher, CommandTasks
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
- `./gradlew runGametest`: runs a headless server with 14 GameTests. They cover spawning and physics, A*, following through a door, gathering logs, felling a tall tree by pillaring, eating, fighting (armed and timid, zombie and creeper), fleeing, armour, chat, crafting a pickaxe from logs, mining stone after making a pickaxe, and tunnelling to coal then crafting a torch from one chat order. It resets its world on every run.
- `./gradlew runServer`: dev dedicated server in `run/server`. Type commands into the console.
- To see AI decisions, set `verboseAiLogging: true` and grep `latest.log` for `[<name>]`.

See [docs/TESTING.md](docs/TESTING.md) for the manual checklist.

## Known limitations

- **Skins**: a registered-account skin needs network access once per name. Without it, the companion falls back to a default skin.
- **Death**: a dead companion leaves and rejoins after `respawnDelayTicks`, because vanilla respawn creates a plain `ServerPlayerEntity`. Chat shows a leave/join pair. Changing skins also rejoins.
- **They count as players**:
  - They appear in the tab list and player count, and they get stats and advancements.
  - They're added to `usercache.json` like any joining player.
  - The night only skips if every companion near you finds a free bed (or lower `playersSleepingPercentage`).
- **Spawn protection** on dedicated servers stops non-op companions from breaking blocks near spawn.
- **Griefing off** is conservative because vanilla doesn't record who placed a block:
  - Wood only comes from trees that still have natural leaves.
  - Pathing only breaks natural leaves.
  - Mining jobs only dig natural terrain.
  - Pillars up trees use dug dirt and are removed afterwards. Dirt holes dug for blocks are left.
- **Navigation**:
  - It can't climb ladders or vines or use boats, and can't pillar while travelling (only up trees).
  - Searches are limited to 96 blocks; partial paths are followed, then it re-plans.
- **Trees**: logs too far sideways (long branches, the edges of giant canopies) can be out of reach even from the pillar, so they're left. Very tall trees take a while by hand.
- **Mining** has no torches and no lighting. It never mines next to fluids, which also means ores touching water or lava are skipped.
- **Crafting**: no smelting, so anything needing ingots, glass or cooked food fails with a reason. Only crafting-table and inventory recipes are supported.
- **Combat**: melee only, no bows or shields. Companions don't hunt for food.
- **Chat**: lines are server system messages formatted as `<Name> text`, because a fake player can't sign chat. Parsing is keyword-based, so unusual phrasing gets "Sorry, what was that?".
- A `/kick`ed companion stays registered. It comes back on restart or with `/companion spawn`.
