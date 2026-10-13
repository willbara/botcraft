# Botcraft

AI companions for Minecraft 1.20.1 (Fabric) that join the server as real players. A companion can follow you or live on its own: it chops whole trees, mines (caves first, whole veins), crafts, smelts, hunts and cooks, fights monsters, sleeps when you sleep, helps the other companions, remembers what it has seen, and chats with you and with the others.

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

In free mode a companion keeps itself fed, makes its tools, sleeps in its own bed, works up from stone tools to diamond gear, and fills the base's stockpile through a request board that sends each job to whoever is best placed for it. `/companion colony` shows the base and the board, and `/companion why Bob` shows what Bob is doing and why.

Right-click a companion to open its inventory and take or give items.

A companion only listens when you say its name or "everyone". Only its owner can give it orders, but anyone can ask it questions ("Bob, where are you?", "what's in the chests?").

## Commands

All commands need permission level 2 by default (`commandPermissionLevel`).

| Command | Effect |
|---|---|
| `/companion spawn <name> [skin]` | Spawns a companion in front of you, with you as its owner. Re-using the name of a dismissed companion restores its inventory. |
| `/companion batch <count>` | Spawns up to 64 companions around you at once, with random two-word names such as AgentCreeper or SneakyBadger and random default skins. You own them all. |
| `/companion skin <name> <skin>` | Changes the skin. The companion leaves and rejoins in place. |
| `/companion info <name>` | Debug view: personality, orders, job queue, current drive scores, memory. |
| `/companion remove <name>` | Dismisses the companion and forgets it. Its playerdata file is kept. |
| `/companion list` | Name, owner, position, health, food and current activity of every companion. |
| `/companion colony` | The shared base: its chests, what's stored, the stock targets, and the request board with each request's top bids. |
| `/companion colony want <item> <count>` | How many of something the base should keep (`0` stops collecting it). |
| `/companion why <name>` | Its reasoning: current activity and queue, its board request, its progression target, the chosen plan with its cost, and the alternatives. |
| `/companion stop <name>` | Stays put and drops any job. |
| `/companion task <name> <text>` | Anything you could say in chat, for example `get 10 logs then craft a stone pickaxe`. |

Skins can be one of the built-in defaults (`steve`, `alex`, `kai`, `noor`...), an online player's name, or any registered account's name. See [Skins](docs/GUIDE.md#skins).

## Documentation

- [docs/GUIDE.md](docs/GUIDE.md): everything you can say to a companion, what companions do on their own, limitations, every config key, and how the mod works.
- [docs/TESTING.md](docs/TESTING.md): the automated tests and the manual in-game checklist.

The biggest gaps right now: melee combat only, no farming or breeding yet, no Nether or End, and chat is understood with patterns rather than a language model, so unusual phrasing can get a "Sorry, what was that?". The guide lists the rest under [Limitations](docs/GUIDE.md#limitations).

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
