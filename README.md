# Botcraft

AI companions for Minecraft 1.20.1 (Fabric) that join the server as real players. A companion can follow you or live on its own: it chops whole trees, mines (caves first, whole veins), crafts, smelts, hunts and cooks, farms (adding watered plots as the base grows), fishes, fights monsters (with crits and a shield), sleeps when you sleep, helps the other companions, remembers what it has seen, and chats with you and with the others.

Each companion has a personality (twelve traits, presets such as guard or miner), moods that come and go, and feelings about the people it knows. Companions can live as one colony, as fixed teams (red vs blue), or as factions that form, split up, found their own bases far apart, spread their land until their borders meet (then it's war or a truce), raid each other and put bounties on whoever keeps killing them. Every faction remembers how each player has treated it, and only players on good terms may give orders.

- **Server-side only.** It works on dedicated servers and in singleplayer, and clients don't need the mod.
- **Self-contained.** There are no API keys, language models or native libraries. The only dependency is Fabric API.
- **One optional network call.** Looking up the skin of a registered Minecraft account goes to Mojang, the same way player heads do. Turn it off with `allowSkinLookup`.

## Install

You need Minecraft 1.20.1, Fabric Loader 0.15 or later, and Fabric API.

1. Build the jar with `./gradlew build`. It ends up in `build/libs/botcraft-<version>.jar`.
2. Put it in `mods/` next to Fabric API.
3. In singleplayer, create the world with cheats allowed, because `/companion` needs permission level 2.

## Quick start

```
/companion spawn Bob                        a companion appears in front of you; you're its owner
Bob, follow me
Bob, get 16 logs then craft a stone pickaxe
everyone, this is home                      sets the shared base for all companions
this chest is for ores                      while looking at a chest at the base
everyone, do your own thing                 free mode: they live and progress on their own
```

In free mode a companion keeps itself fed, makes its tools, sleeps in its own bed, works up from stone tools to diamond gear, tends the farm, and fills the base's stockpile through a request board that sends each job to whoever is best placed for it. It chooses through a goal tree weighed by its personality. `/companion colony` shows the base and the board, `/companion why Bob` shows what Bob is doing and why, and `/companion goals Bob` shows Bob's goal tree.

```
/companion faction teams red blue           two teams, enemies; each finds and founds its own base
red team, follow me                         address a whole team
Bob, when I say lumber I mean logs          teach a word (or a routine: "morning routine means ...")
Bob, get 10 iron  ...  make it 20           follow-ups: "make it 20", "and coal too", "Ann, you too"
```

Right-click a companion to open its inventory and take or give items.

A companion only listens when you say its name, "everyone", or its team ("red team, ..."). Its owner can always give it orders (unless `ownerAuthority` is `standing`). Other players need good standing with its faction: trusted players may give orders, friendly ones may ask favours, and others may only chat. Hit a companion and its faction remembers.

## Commands

All commands need permission level 2 by default (`commandPermissionLevel`).

| Command | Effect |
|---|---|
| `/companion spawn <name> [skin]` | Spawns a companion in front of you, with you as its owner. Re-using the name of a dismissed companion restores its inventory. |
| `/companion batch <count>` | Spawns up to 64 companions around you at once, with random two-word names such as AgentCreeper or SneakyBadger and random default skins. You own them all. |
| `/companion skin <name> <skin>` | Changes the skin. The companion leaves and rejoins in place. |
| `/companion info <name>` | Debug view: personality, orders, job queue, current drive scores, memory. |
| `/companion remove <name>` | Dismisses the companion and forgets it. Its playerdata file is kept. |
| `/companion removeall` | Dismisses every companion and forgets them, like `/companion remove` for each. Their playerdata files are kept. |
| `/companion freemode [on\|off]` | On: every companion lives on its own now, and new ones start that way (no "go have fun" needed). Saved in the config (`startFree`). |
| `/companion chat [all\|quiet\|off]` | How much companions say. `quiet` keeps answers and acknowledgements when spoken to, what they start on their own and what failed, and drops small talk, banter and remarks. Faction business (a leader's orders and check-ins, wars, parleys, bounties) still gets said. Saved in the config (`chatVerbosity`). |
| `/companion chat factions [leader\|all]` | Who talks in a faction. `leader` (default): only the one in charge (orders, check-ins, faction news), and members answering it or a player. `all`: everyone. Saved in the config (`factionChat`). |
| `/companion list` | Name, owner, position, health, food and current activity of every companion. |
| `/companion colony [of <faction>]` | A base: its chests, zones, upkeep, what's stored, the stock targets, and the request board with each request's top bids. Outside single mode it's the base of the faction whose land you're on (or the nearest). |
| `/companion colony want <item> <count>` | How many of something the base should keep (`0` stops collecting it). |
| `/companion why <name>` | Its reasoning: current activity and queue, its board request, its progression target, the chosen plan with its cost, and the alternatives. |
| `/companion goals <name>` | Its goal tree: what free mode is choosing between, the scores, what's put aside after failing and for how long. |
| `/companion archetype <name> <type>` | Give it a preset character: miner, farmer, guard, scout, builder, trader, leader, hothead, coward or joker. |
| `/companion trait <name> <trait> <0..1>` | Set one personality trait. |
| `/companion faction` | Factions: `mode single\|teams\|dynamic`, `teams red blue [owner]`, `warfare off\|defend\|pvp\|raid [faction]`, `create`, `remove`, `join <companion> <faction>`, `member <faction> <player> <rank>`, `relation <a> <b> <allied\|neutral\|hostile>`, `raid <faction>`, `ceasefire`, `authority absolute\|standing` (whether owners must earn obedience too), `info <faction>`. |
| `/companion standing <player>` | What every faction thinks of that player (-100..100), with any ranks they hold. |
| `/companion standing <faction> [player] [value]` | What one faction thinks of players, or set it. |
| `/companion stop <name>` | Stays put and drops any job. |
| `/companion task <name> <text>` | Anything you could say in chat, for example `get 10 logs then craft a stone pickaxe`. |

Skins can be one of the built-in defaults (`steve`, `alex`, `kai`, `noor`...), an online player's name, or any registered account's name. See [Skins](docs/GUIDE.md#skins).

## Documentation

- [docs/GUIDE.md](docs/GUIDE.md): everything you can say to a companion, what companions do on their own, limitations, every config key, and how the mod works.
- [docs/TESTING.md](docs/TESTING.md): the automated tests and the manual in-game checklist.
- [docs/PLAN.md](docs/PLAN.md): where this is going: the goal tree, personality, communication, factions, jobs and combat, and what's done so far.

The biggest gaps right now: no bows, no breeding, no Nether or End, and chat is understood with patterns rather than a language model, so unusual phrasing can get a "Sorry, what was that?". The guide lists the rest under [Limitations](docs/GUIDE.md#limitations).

## Building and testing

- `./gradlew build` compiles and runs the unit tests.
- `./gradlew runGametest` runs the headless GameTests (74 of them) on a fresh world.
- `./gradlew runSoak -PsoakMinutes=30 -PsoakCompanions=6` runs an unattended soak on a dedicated server with a fixed seed (`-PsoakSeed`, default 12345), then writes `run/soak/soak-report.md`: deaths and causes, stalls, failed jobs, what each companion achieved, AI time per tick, and what reached the base.
- `./gradlew runServer` starts a dev dedicated server in `run/server`.
- `./gradlew deployToTest` builds the jar, removes any older Botcraft, MCFriends or Companions jar from `<instance>/mods`, and copies the new one in. Point it at your Minecraft instance folder with the `MC_DIR` environment variable, `mc_dir=...` in `~/.gradle/gradle.properties`, or `-Pmc_dir=...`.
- To follow a companion's decisions, set `verboseAiLogging: true` in `config/botcraft.json` and grep `latest.log` for `[<name>]`.

| Component | Version |
|---|---|
| Minecraft | 1.20.1 |
| Yarn | 1.20.1+build.10 |
| Fabric Loader | 0.19.5 (the mod needs 0.15.0 or later) |
| Fabric API | 0.92.12+1.20.1 |
| Fabric Loom | 1.18.3 (needs Gradle 9.7+ running on Java 25) |
| Gradle | 9.7.1 (wrapper) |
| Java | JDK 17 toolchain, provisioned automatically |
