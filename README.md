# MCFriends

AI companions for Minecraft **1.20.1 (Fabric)** that join the server as real players. A companion can follow you or live on its own: it chops whole trees, mines (caves first, whole veins), crafts, smelts, hunts and cooks, fights monsters, sleeps when you sleep, helps the other companions, remembers what it has seen, and chats with you and with the others.

- **Server-side only.** It works on dedicated servers and in singleplayer, and clients don't need the mod.
- **Self-contained.** There are no API keys, language models or native libraries. The only dependency is Fabric API.
- **One optional network call.** Looking up the skin of a registered Minecraft account goes to Mojang, the same way player heads do. Turn it off with `allowSkinLookup`.

This document describes version **0.8.1**.

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
| `/companion colony` | The shared base: where it is, each chest and its purpose, what's stored, the stock targets (have/want), and the request board: each request, who took it and the top bids with their score breakdown. |
| `/companion colony want <item> <count>` | How many of something the base should keep (`0` stops collecting it). Works for the defaults (`food`, `coal`, `logs`, `iron_ingot`, `torch`, `cobblestone`) and any other item. |
| `/companion why <name>` | Its reasoning: current activity and queue, the board request it's working on (with its bid), its progression target, the chosen plan with its cost, the alternatives it considered, and what it has learned about each method. |
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
| this is home / set home here | Remember this place as home. Your home is also the shared base. |
| this is our chest / use this chest | The chest you're looking at (or standing next to) becomes a base chest. |
| this chest is for ores / food goes in this chest / put the wood here / this is the tool chest | The same, with a purpose: ores and metals, wood, food, tools and gear, building blocks, or everything else. |
| light up the base / place some torches / it's dark around here | Make torches if needed, then put torches on every pitch-dark patch of ground within 12 blocks of the base. |
| run away / scatter / spread out / split up | Free mode, but first each one walks 40-64 blocks off in its own direction (away from you, fanned out so a crowd splits evenly), then lives on its own from there. Handy for testing free mode. They still share the base, so they may drift back to it. |
| dance / everyone dance / bust a move | About 12 seconds of dancing (spins, hops, crouch spam, punching the air), then back to whatever it was doing. Anyone can ask, and it doesn't end free mode. |
| put your stuff away / empty your inventory / deposit | Store everything except the kit in the base chests. |
| the base needs 64 coal / keep 20 glass in the chests / the colony needs more food | How much the base should keep ("more" means two batches more). Free-roaming companions fill the gap through the request board. One companion answers. |
| we have enough cobblestone / stop collecting torches | Stop collecting that for the base. |
| go home / come home | Go home. |
| go to sleep / go to bed | Find a free bed and sleep (only at night). With no free bed around but one in its pockets, it puts that one down first. |
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
| what's in the chests? / what do we have? | What's stored at the base (one companion answers). |
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
  - **Breath:** a path may stay with the head under water for at most 14 blocks in a row, fewer when the companion is already short of air. Longer underwater stretches cost more the longer they get. It never routes through a flooded tunnel longer than a breath lasts.
- **Getting unstuck.**
  - **Stuck while walking:** it wiggles, then re-plans around the spot that stopped it.
  - **No normal path:** escape mode digs through natural terrain and pillars.
  - **Underground with the goal above:** it digs straight up like a player, breaking the block overhead, jumping and placing a block underneath. It stops before fluids, sand and gravel, and anything that isn't natural terrain.
  - **Short or failed searches:** a path that ends just short of the goal re-plans, and a search that fails almost at once is retried after a moment.
- **Breathing.** When a companion's air drops below half, it stops everything and swims to the nearest air the water actually leads to: up to about 32 blocks of swimming, never air behind a wall or above a solid ceiling. It swims that route itself, block by block (sideways, down, up), without the pathfinder. With no air in reach it swims straight up.
- **Giving up on wet jobs.** A job that sends a companion up for air three times is abandoned ("there's too much water here"), and counts as a failure of that method.

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
- **Wet and dangerous ore.** Ore that would let liquid in when mined (liquid directly above it, or a liquid source beside it, as in Baritone) is never mined, including when following a vein. Other ore next to water is chosen only when there's no dry ore. Ore next to lava is left alone.
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

- **Respawning.** A companion that dies comes back after `respawnDelayTicks`. It returns at its bed, else home, else (when following you) next to you if you're standing somewhere safe, else world spawn, always on dry, solid ground. It never reappears looking dead.
- **Getting its things back.** After respawning it goes back for what it dropped, and keeps looking for as long as the items last (5 minutes from the death), unless it died in lava, fire, the void or by drowning, or that spot has already killed it twice in 10 minutes. A death also counts as a failed goal, so free mode puts that goal aside for a while.
- **Unsafe saves.** A companion saved somewhere deadly (under water, in lava, inside a block) is moved to the nearest safe spot when the world loads.
- **Not getting lost.**
  - **Nobody to follow:** in follow mode with you offline, spectating or in another dimension, a companion that's finished or whose job failed heads home if it's more than 24 blocks away.
  - **Can't find a way back to you:** following you from far away, it walks if it can. It teleports only if the path fails, or it's still more than `teleportToOwnerDistance` away after 30 seconds of walking, and at most once a minute. It's never called back while on a job, however far the job takes it.

### The base and shared storage

- **One shared base.** Saying "this is home" sets the base for all companions. It's saved in `<world>/mcfriends/colony.json` along with the base chests.
- **Only base chests.** Companions use only chests they placed at the base, or ones you mark ("this is our chest"). They never touch any other chest. Double chests and barrels work too.
- **Chests with a purpose.** Say "this chest is for ores" (or wood, food, tools/gear, building blocks, everything else) while looking at a chest. Storing sends each item to the chest for its kind first, then to a general chest (one with no purpose), and only then anywhere with room. Fetching finds things by what's actually in the chests, whatever their purpose.
- **What they keep (the kit).** The best tool of each kind, a weapon, worn armour, up to 16 food (cooked first), 32 building blocks and 16 torches. Plus a **working stock** of what progress keeps needing: up to 16 logs, 16 planks, 16 sticks, 16 coal, 32 iron ingots, 32 raw iron, 16 diamonds, a crafting table, a furnace and a bed. Only extras beyond that, spare tools and junk (dirt, gravel, saplings, clay…) go into the chests, so they don't store something and fetch it straight back. Like goes with like: a chest already holding an item is filled first.
- **When they store things.**
  - **Free mode:** when back at the base with 6 or more stacks to store, or when nearly full (at the base, or while out: they come home to unload).
  - **Night chore:** while at the base.
  - **On command:** "put your stuff away".
  - **New chests:** when all chests are full, a companion crafts a chest from carried wood and places it at the base (spaced apart, so no accidental double chests).
- **Taking from the chests first.**
  - **Crafting** plans with what it carries plus what's stored, then fetches the difference ("craft an iron pickaxe" takes ingots and sticks from the chest instead of mining and chopping).
  - **Smelting** takes inputs and fuel (coal, else logs) from the chests.
  - **A hungry companion** takes food from the chests before hunting, and free mode keeps its food reserve topped up from there.
  - **Range:** this applies within about 96 blocks of the base. If the chests turn out to have less than expected, a companion takes what's there and gets the rest the usual way.
- **Base stations.** A crafting table or furnace put down at the base stays there for everyone, instead of being picked back up.
- **Night chores.** An armed companion at the base at night (with no bed) stores its things, cooks raw meat, smelts raw iron and crafts iron gear from carried and stored materials. It doesn't leave the base for anything after dark.

### Torches

- **In caves and tunnels.** Underground with torches on hand, a companion puts one on the floor beside it whenever its spot gets dark (block light below 6), so the places it mines and explores end up lit every six or seven blocks. It waits until it isn't mid-swing or fighting.
- **At the base.** Monsters spawn on pitch-dark ground. When the area within 12 blocks of the base (at about its level) has any, free mode lights it up: torches it carries, or torches it crafts from coal and sticks (taken from the chests if need be). It's also a night chore at the base, and an order ("light up the base").
- `placeTorches` in the config turns all of this off.

### Watchdog

Every task has its own time limit, but a task can keep itself busy without getting anywhere (re-planning to the same unreachable spot, retrying the same placement). If a companion is on a job and for 3 minutes it neither moves more than a few blocks, nor breaks anything, nor gains any items (eating doesn't count), the job is abandoned with "I got stuck" and the usual failure handling takes over. Sleeping, sheltering for the night and waiting on a friend are exempt.

### Working together

- **Spreading out.** Each companion claims the tree, ore, cave or animal it's working on. When choosing, spots near someone else's claim, or near any player or companion standing there, are avoided while there's another choice.
- **Base stock and the request board.** The base keeps a stockpile, with these defaults:
  - 32 cooked food;
  - 32 coal;
  - 64 logs;
  - 16 iron ingots;
  - 32 torches;
  - 64 cobblestone.

  Change these, or add any other item, with "the base needs 64 coal", "keep 20 glass in the chests", "we have enough cobblestone" or `/companion colony want`.

  When the chests are short, a request for one batch goes on the board (16 logs, 8 coal, 6 food...). A free-roaming companion with nothing pressing (night, storage, food, basic tools and a bed come first) takes the most important request it's the **best bidder** for, gets the batch whichever way the planner finds cheapest, and stores it, even out of its own working stock. It says what it's doing: "I'll get 8 coal for the base, it's quickest for me."

  A bid adds up a few terms, in the same unit (two minutes = one point):
  - **time:** how long the planner thinks it would take that companion, with what it has learned;
  - **to base:** how far it is from the base;
  - **busy:** a penalty for being in the middle of something of its own;
  - **turns:** how many requests it has done lately;
  - **failed:** whether it failed at this one recently;
  - **helpful:** a bonus for a helpful personality.

  A companion carrying spare (beyond its kit) simply drops it off. Each companion holds one request at a time and alternates them with its own progression. A request whose holder dies, gets orders, or stops working on it goes back on the board. `/companion colony` shows it all.
- **Sharing.** When a job needs something worth a trip (coal, ores or ingots, gems, wool, a pickaxe, food), a companion asks the *idle or free-roaming* friend with the best bid ("Hey Ann, could you spare 3 coal?"): closest, with the most to spare, and not busy with something of its own. The friend brings it over and goes back to what it was doing. These requests show on the board too.
  - Cheap things (cobblestone, logs, dirt, planks, sticks) it always gets itself.
  - At most one request every 3 minutes, and the same item at most every 10 minutes.
  - A helper helps one companion at a time, then gets a 2-minute breather, and is never pulled off one of your orders.
  - Only spares are given, and tools only if the friend has two. Handed-over items can only be picked up by the recipient.
  - If nothing arrives, the companion gets the item itself.
- **Social life.** Companions greet each other and banter when idle: weather, work, favourites, jokes, complaints. They also share where they found ores, which the listener remembers. They praise each other's kills, mourn deaths and come to each other's help.

### Planning and progression

- **Progression targets** are data, a ladder in `brain/plan/Targets.java`. Free mode first gets the basic wooden and stone tools, then works up the ladder:
  1. iron pickaxe and iron sword;
  2. iron chestplate, leggings, helmet and boots;
  3. diamond pickaxe and diamond sword;
  4. diamond armour.

  Each step is "have this": met by carrying or wearing it (or something better).
- **The planner** (`brain/plan/Planner.java`) works out every way to get what the next target needs, recursively:
  - carrying it already, or taking it from the base chests;
  - crafting it (planning its ingredients), or smelting it (planning the input and fuel);
  - mining it (planning the right pickaxe first), hunting it, or chopping it.

  Each option is priced in seconds from what the companion knows:
  - distance to the base and to known ore, caves, trees or herds;
  - how long each method has taken it before and how often it worked;
  - deaths, and the danger of being outside at night.

  The cheapest option becomes a list of ordinary jobs. For example, an iron pickaxe is "take 3 ingots and 2 sticks from the chest, craft" when the chest has them, "smelt the raw iron I carry, craft" when that's cheaper, and "mine iron, smelt it, chop a log for sticks, craft" from scratch. The tasks still check reality as they go: if a chest turns out empty, crafting re-plans.
- **Learning.** Every finished job records how it went for its method ("mine iron", "hunt cow", "gather logs", "craft", "smelt"...): attempts, successes, items gained, time and deaths. They're saved in the companion's memory. A method that keeps failing or killing a companion gets expensive, and another way wins.
- **Seeing why.** `/companion why <name>` shows the target, the chosen plan and its cost, the alternatives with theirs, and the learned stats.

### Free mode (autonomy)

"Do your own thing" turns on a player-like routine around home:

- **Night:** sleep in a bed: a free one near home, else its own bed from its pockets (put down at home), else one it crafts on the spot from 3 wool of one colour (carried or in the base chests) and planks. Only with none of that does an armed companion guard home and an unarmed one dig in (three blocks down, roofed) until morning. Errands still running at dusk (hunting, chopping, exploring) are dropped so it heads in instead of being caught outside.
- **Food:** cook raw meat, ask a friend, or hunt, and keep a reserve of about 6 food when animals are known nearby.
- **Progression:** wooden and stone tools, then a bed of its own at home (hunting sheep for the wool if need be; companions don't share beds, so everyone can sleep), then smelting carried raw iron, an iron pickaxe, an iron sword and iron armour piece by piece.
- **The base's needs:** requests from the board (see [Working together](#working-together)), taking turns with its own progression.
- **Whatever suits where it is:** chopping with an axe in a forest; mining coal, iron, then diamonds (with an iron pickaxe) in a nearby cave; turning spare coal into torches.
- **Otherwise:** it revisits a remembered cave or wanders.

It says what it's doing, and a goal that fails is put aside for a while. **Any other order ends free mode** (back to following you), so its plans never compete with yours.

**Free mode ignores you completely.** It never follows you, and never teleports to you. If it dies with no bed or home, it respawns at world spawn, not at your side.

**Free mode's failsafe** is the only teleport, and it always goes home (or to world spawn without a home), never to you. It happens only when something has clearly gone wrong:
- stuck inside blocks for 3 seconds;
- not moving more than a few blocks for 5 minutes (sleeping, sheltering, waiting on a friend or resting near home don't count);
- the watchdog giving up on two jobs within 10 minutes.

The soak report lists every rescue. In follow mode it only does light tool upkeep near you. `initiative` controls how readily it sets its own goals.

### Sleep

It goes to bed when you do (and on its own at night in free mode), so the night can be skipped. It uses its own bed if it has one, any free bed nearby otherwise, and puts down a bed it's carrying if there's none free. Awake companions never hold up the night skip (see [Limitations](#players-and-the-server)).

### Speech

- **Lines** come from `dialogue.json`, a large pool that avoids repeating itself.
- **Voices.** Each companion has its own voice (casual or proper, calm or excited, filler words, emoticons, a nickname for you), so the same line sounds different from different companions.
- **Unprompted remarks** on nightfall and morning, weather, new biomes, low health, creepers, you getting hurt or dying, diamonds, each step of a plan, plan failures (with the reason), kills, fleeing, missing beds, its own initiative, and idle musings. How often depends on chattiness.
- **Customising.** Create `config/mcfriends-dialogue.json` in the same format as [`dialogue.json`](src/main/resources/mcfriends/dialogue.json); any key you define replaces that key's default lines. Placeholders include `{player}`, `{nick}`, `{name}`, `{friend}`, `{mob}`, `{item}`, `{count}`, `{plan}`, `{result}`, `{reason}`, `{biome}`, `{time}` and `{fav}`. Restart to reload.

### Easter eggs

Spawn a companion named **popbob** (any capitalisation) and it turns on everyone:

- It goes for the nearest player or companion within 48 blocks, with the best weapon it has, taunting as it goes.
- With nobody around it wanders and griefs: planks, wool, glass, stairs, slabs, fences, doors, torches, crafting tables, cobblestone, bricks and similar build blocks. It never breaks containers or other block entities, dirt or stone, or anything unbreakable.
- It ignores every order and question, doesn't greet anyone, and never shares or asks for help.
- Other companions treat it as a threat. Armed ones fight it from up to 12 blocks away; unarmed ones fight back once it's within 4.
- It can only hurt players and companions when PvP is on (`pvp=true` in `server.properties`; singleplayer has it on).
- Remove it with `/companion remove popbob`, or set `easterEggs: false` to make popbob an ordinary companion.

---

## Limitations

### Players and the server

- **They count as players** (but don't use player slots):
  - They appear in the tab list, the player count and `usercache.json`, and they earn stats and advancements.
  - For skipping the night, only companions **in bed** count: awake ones (standing guard, no bed) are left out, like spectators. So the night skips once you (and every other real player) are asleep, whatever the companions are doing. With no real players online, it skips once all the companions are in bed.
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
- **Sealed underwater caves.** If no air is reachable within about 32 blocks of swimming, a companion can still drown. Paths keep breathing room, but currents can push a companion off its route.
- **No portals.** Companions don't use Nether or End portals on their own. They follow you between dimensions by teleporting.

### Tasks

- **Crafting** covers crafting-table and furnace recipes only: no smithing, brewing, enchanting, stonecutting, blast furnaces or smokers.
- **No farming.** It was removed for now and will come back reworked.
- **Storage is basic.** There's one base, chests aren't sorted beyond "like with like", and nothing is reserved: two companions can head for the same items, and the second gets whatever's left. Companions don't organise chests you fill yourself, and they never use chests away from the base.
- **Lighting is basic.** Torches go on the floor (never on walls), only around the base and where a companion is underground. They aren't picked back up, and nobody lights paths between places.
- **Hunting** never takes the last two animals of a species nearby (the last one when starving), so a lone cow is always left alone. There's no breeding yet, so a heavily hunted area runs out of animals. Animals beyond about 96 blocks, or more than 48 blocks up or down, aren't noticed until the companion gets closer. Fish, crops and other food sources aren't used.
- **Mining** reads ore positions from the world, like an "x-ray" of the 24-block scan area. A player would have to dig to find buried ore.
- **Tool upgrades** stop at iron.

### Combat

- **Melee only:** no bows, crossbows or shields.
- **No fighting when weak:** below 4 melee damage a companion flees instead of fighting, even from weak mobs.

### Free mode

- **Progression stops at diamond gear.** The target ladder ends there: no enchanting, Nether or End yet. There's no base building, trading or exploring for structures.
- **A simple planner.** It plans one target at a time for one companion (no group plans yet: that's the request board). Its time estimates start from rough guesses and improve with experience.
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
| `teleportToOwnerDistance` | 48 | When following and farther than this, walk first and teleport only if walking fails or takes over 30 s (at most once a minute). Also used to follow you into another dimension. Never applies during a job. 0 disables. |
| `treeSearchRadius` | 32 | Blocks. |
| `respawnDelayTicks` | 100 | Delay before a dead companion rejoins. |
| `forceSurvival` | true | Companions join in survival mode. |
| `allowSkinLookup` | true | Allow looking up registered accounts' skins from Mojang. |
| `autoSkinFromName` | false | With no skin argument, try the companion's own name as an account. |
| `initiative` | 0.5 | How readily idle companions set their own goals (0 = never). In free mode it gates the whole Life planner. |
| `sleepWithOwner` | true | Go to bed when the owner sleeps. |
| `commandPermissionLevel` | 2 | Permission level for `/companion`. |
| `placeTorches` | true | Put torches down in dark caves and around a dark base. |
| `easterEggs` | true | Easter eggs. Currently: a companion named "popbob" (see [Easter eggs](#easter-eggs)). |
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
├─ colony/      Colony (the base, its chests and their purposes, stock; colony.json), Kit (what a companion keeps), Purpose
├─ brain/plan/  Planner (hierarchical "get N of X" with costs), Targets (progression ladder), MethodStats (learned costs)
├─ brain/       Brain (utility selection + interrupt stack), Drives, Objective, Personality,
│               JobQueue + Jobs (persisted job specs, prerequisites, optional prep), Life (free mode),
│               Sharing, Claims (spreading out), Leftovers (own drops), Watchdog (stalled jobs)
│  └─ task/     Follow, Stay, Idle, GoTo, GatherLogs, Mine, Craft, Smelt, Hunt, Place, Deliver,
│               Await, Sleep, Shelter, Eat, Fight, Flee/Retreat, Breathe, Collect, Deposit, Withdraw, Dance, ToolPrep,
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
- `<world>/mcfriends/colony.json`: the base position and its chests.
- `<world>/mcfriends/companions.json`: name, UUID, owner, skin (including fetched textures), personality, mode, defend flag, the current job and the queue.
- `<world>/mcfriends/memory/<name>.json`: memory.
- Vanilla playerdata: everything else.

---

## Development

- `./gradlew build` compiles and runs the JUnit tests (intent parser, templates, skin UUIDs).
- `./gradlew runGametest` runs a headless server with 72 GameTests. It resets its world on every run. The tests cover:
  - movement and pathfinding: shafts, ladders, parkour, diving, digging out, surfacing for air (including swimming a sideways route out), and never pathing through a flooded tunnel longer than a breath;
  - gathering, mining (including whole veins) and crafting (including table budgeting);
  - smelting, and hunting (near, far, sword first, searching, noticing herds, starving, keeping a food stock);
  - combat (including full-damage hits), free mode (including ignoring the owner, and the failsafe), shelter, safe respawns, gear recovery after death, the watchdog, and heading home when lost;
  - chat, banter, sharing, spreading out, tool prep and picking up drops;
  - the base: storing all but the kit, chests with a purpose, placing a chest when needed, and crafting from base stock (never from other chests);
  - the planner (using stock, smelting what's carried, chaining from scratch, learning from failure), the target ladder, the request board (bidding, failure memory, stocking the base end to end), dancing, scattering on "run away", and beds (putting a carried bed down, crafting one at night in free mode, the night skip with a companion still awake);
  - torches in a dark cave and lighting up a dark base;
  - the popbob easter egg (attacking, being fought back, griefing but never chests, ignoring orders);
  - player slots.
- `./gradlew runSoak -PsoakMinutes=30 -PsoakCompanions=6` runs an unattended soak test. It starts a dedicated server in `run/soak` with a fresh world (seed `-PsoakSeed`, default 12345, Normal difficulty), and spawns the companions in free mode at world spawn with no player online. When time is up it writes `run/soak/soak-report.md` and stops. The report covers:
  - deaths per companion-hour, with causes;
  - stalls, failsafe rescues, finished and failed jobs (with reasons), and interrupts;
  - what each companion gathered and hunted, how far it walked, its hunger at the end, and the best pickaxe, sword and armour it reached;
  - AI time per tick, how many chunks stayed loaded, and items stored at the base (with the final stock).
- `./gradlew runServer` starts a dev dedicated server in `run/server`. Type commands into the console.
- To see AI decisions, set `verboseAiLogging: true` and grep `latest.log` for `[<name>]`.

See [docs/TESTING.md](docs/TESTING.md) for the manual checklist.
