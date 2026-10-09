# MCFriends

Offline AI companions for Minecraft **1.20.1 (Fabric)** that join the server as real players. They follow you, chop trees, eat, fight or flee monsters, and chat. There are no network calls, API keys, LLMs or native libraries. The only dependency is Fabric API. Everything runs server-side, so it works on dedicated servers and in singleplayer, and clients don't need the mod.

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
| `/companion remove <name>` | Dismisses the companion and forgets it. Its playerdata file is kept. |
| `/companion list` | Shows name, owner, position, health, food and current activity. |
| `/companion stop <name>` | Stays put and drops any job. |
| `/companion task <name> <task>` | `follow`, `stay`, `come`, `gather logs [n]`, `defend`, `status` |

**Skins** work fully offline:
- **Default skins**: `alex`, `ari`, `efe`, `kai`, `makena`, `noor`, `steve`, `sunny`, `zuri`, optionally with `_slim` or `_wide`. The client chooses a default skin from the UUID hash, so the mod picks a UUID that lands on the requested skin.
- **Copy a player's skin**: give the name of a player who is **online**. Their signed texture property is copied.
- If `skin` is omitted, the skin is whatever the UUID hash gives.

## Talking to companions

Address a companion by name ("Bob, follow me"), or address all of yours ("everyone stop"). You can leave the name out if you own exactly one companion within 32 blocks and the message is an order.

| Say | Intent |
|---|---|
| follow me / come with me / let's go | follow |
| stop / stay / wait / stop following | stay here |
| come here / get over here / come | walk to you |
| gather 10 logs / chop some trees / get me a stack of wood | gather (only wood in this version) |
| give me 5 logs | toss you items |
| defend me / protect me | follow, and also attack mobs targeting you |
| status / what are you doing / how are you | status reply with real health, food, task and log count |
| hi / thanks | small talk |

Only the owner can give orders. Anyone can ask for status. Companions also speak on their own when they join or come back, at nightfall and morning, at low health, when a creeper is near, when starving with no food, when a job is done or failed, when they arrive, when a fight starts, and after a kill. How often depends on the companion's chattiness.

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
│            task/ (Follow, Stay, Idle, GoTo, GatherLogs, Eat, Fight, Flee/Retreat)
├─ chat/     IntentParser, Templates, Speech, ChatRouter, EventWatcher, CommandTasks
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

**Persistence.** `<world>/mcfriends/companions.json` stores name, UUID, owner, skin, personality, mode (`follow`/`stay`), defend flag and the current job with its remaining count. Everything else is stored in vanilla playerdata.

## Development

- `./gradlew build`: compiles and runs the JUnit tests (intent parser, templates, skin UUIDs).
- `./gradlew runGametest`: runs a headless server with 8 GameTests (spawn/physics, A*, following through a door, gathering logs, eating, fighting, fleeing a creeper, chat). It resets its world on every run.
- `./gradlew runServer`: dev dedicated server in `run/server`. Type commands into the console.
- To see AI decisions, set `verboseAiLogging: true` and grep `latest.log` for `[<name>]`.

See [docs/TESTING.md](docs/TESTING.md) for the manual checklist.

## Known limitations

- **Skins**: only the 18 built-in skins, or a copy of an online player's skin. Mojang can't be queried offline.
- **Death**: a dead companion leaves and rejoins after `respawnDelayTicks`, because vanilla respawn creates a plain `ServerPlayerEntity`. Chat shows a leave/join pair.
- **They count as players**:
  - They appear in the tab list and player count, and they get stats and advancements.
  - They're added to `usercache.json` like any joining player.
  - They count for sleeping, so skipping the night needs the owner to adjust `/gamerule playersSleepingPercentage` (e.g. 50 with one companion).
- **Spawn protection** on dedicated servers stops non-op companions from breaking blocks near spawn.
- **Griefing off** is conservative because vanilla doesn't record who placed a block. Wood only comes from trees that still have natural leaves.
- **Navigation**:
  - It can't climb ladders or vines, pillar up, do parkour, or use boats.
  - Trapdoors are handled only by their collision shape.
  - Searches are limited to 96 blocks; partial paths are followed, then it re-plans.
  - Only wooden doors and fence gates are opened. At an iron door it waits, facing the owner.
- **Gathering**: logs only. It chops what it can reach from the ground or from inside the trunk, so very tall or 2×2 trees are partly chopped.
- **Combat**:
  - Melee only: no bows, shields or automatic armour equipping.
  - Creepers are always fled.
  - Companions don't look for food themselves.
- Chat lines are server system messages formatted as `<Name> text`, because a fake player can't sign chat.
- A `/kick`ed companion stays registered. It comes back on restart or with `/companion spawn`.
