# Botcraft guide

The full reference: everything you can say to a companion, what companions do on their own, where they fall short, every config key, and how the mod is put together. For installing and a quick start, see the [README](../README.md).

## Contents

1. [Talking to companions](#talking-to-companions)
2. [Skins](#skins)
3. [What companions can do](#what-companions-can-do)
4. [Limitations](#limitations)
5. [Configuration](#configuration)
6. [How it works](#how-it-works)

---

## Talking to companions

### Addressing

A companion only responds when you say its name ("Bob, follow me") or address all of yours ("everyone stop", "all of you, mine coal"). Chat that doesn't name a companion is ignored, so an order for one never interrupts another.

- Names tolerate one typo in a direct address ("herobrin, follow me"), for names of four or more letters.
- **Teams.** Outside single mode, "red team, follow me" addresses every companion in that faction. "everyone" also reaches companions in factions you lead or are an officer of.
- **Who may ask what** depends on who you are to the companion (see [Factions, standing and warfare](#factions-standing-and-warfare)):

| You are | You may |
|---|---|
| its owner (the player who spawned it) | give any order (unless `ownerAuthority` is `standing`) |
| a leader or officer of its faction | give any order |
| trusted (standing 60 or more) | give any order |
| friendly (standing 20 or more) | ask small favours: give/bring me something, come here, hunt, smelt, craft, farm, fish, light, dance, cancel; and ask anything |
| neutral | ask public questions and chat (not where it is, what it carries, or what's in the chests) |
| an enemy (standing -30 or less, or just hit it) | nothing but "sorry" |

### Combining orders

- **Chain orders** with "then", "and then", "after that", commas, or "and" followed by a verb: "Bob, get logs, make planks, then make a crafting table".
- **"After you X, Y"** runs X first: "after you get logs, make a crafting table".
- **Add to the current plan** instead of replacing it by starting with "also …" or "when you're done …".
- **Purpose clauses keep the goal:** "make a furnace to smelt iron" means "smelt iron", because the planner works out the furnace itself.
- **Follow-ups** change your last order (for about 90 seconds, to the same companions, without saying their names again):
  - "make it 30" / "20 instead" / "actually, make it a stack": a new count;
  - "no, I meant gold": a different thing;
  - "and coal too": add the same kind of job for another thing;
  - "same again" / "one more time": repeat it;
  - "Alice, you too": give Alice the same order.
- **Amounts:** "a stack" (64), "half a stack" (32), "two stacks" (128), "a stack and a half" (96), "2 and a half stacks" (160), "a couple" (2), "a dozen" (12).

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
| make diamond armor / get iron tools / make a full set of iron armour / get diamond gear | Every piece it doesn't already have (as good or better), each planned the way free mode plans it, when its turn comes: a stored piece from the chests, otherwise crafted, mining what's missing. Armour is helmet, chestplate, leggings and boots; tools are pickaxe, sword, axe and shovel; gear is the armour plus pickaxe, sword and axe. Plain "armour" means iron. |
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
| farm / go farming / tend the crops / harvest the wheat / plant some potatoes | Look after the base's farm plots: harvest ripe crops, replant, till soil (making a wooden hoe if it has seeds and soil to till), pull up grass for seeds, and give a dry plot its water. "harvest 10 wheat" stops after 10. |
| go fishing / catch some fish / catch 5 fish | Fish at the nearest water it knows of (making a rod first from sticks and string). |
| join the red team / you're with blue now | Move to that faction (outside single mode; needs order rights). |
| when I say lumber I mean logs / "lumber means logs" | Teach a word. It's swapped for its meaning in everything anyone says afterwards. Saved per world. |
| morning routine means get food then light the base | Teach a routine: saying "Bob, morning routine" now does both. |
| forget lumber | Forget a taught word. |
| sorry | After hurting a companion or its faction: a small standing repair (more if it trusts you). Repeated apologies within 5 minutes don't count. |

Item names are resolved against the game's own item list:
- wood words mean chopping;
- ores and stone mean mining;
- leather, feathers, wool and raw meat mean hunting the right animal;
- anything with a crafting recipe means crafting (for example, "get me sticks" crafts sticks);
- a single piece of armour or a tool ("make a diamond chestplate") is planned like a gear group, so a spare in the chests is taken instead of crafting a new one.

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
| good job / you suck / sorry / i love you | It reacts in character, and remembers (praise and insults change how it feels about you). |
| do you trust me? / are we cool? / do you like me? | How it and its faction see you: trusted, friendly, neutral or not at all. |
| do you trust alex? / what do you think of Ann? | How it feels about another player or companion. |
| how do you feel? | Its mood (shaken, happy, frustrated, tired, bored, sad, annoyed) or its health and hunger. |

The parser also copes with:
- **typos in command words:** "folow", "mien", "crfat";
- **chat shorthand:** "u", "ur", "pls", "gimme", "wyd";
- **filler words:** "for me", "please", "yourself";
- **anything else:** a keyword fallback catches phrasings like "could you start following me around".

---

## Skins

A skin argument is resolved in this order:

1. **Built-in default skins:** `alex`, `ari`, `efe`, `kai`, `makena`, `noor`, `steve`, `sunny`, `zuri`, optionally with `_slim` or `_wide`. This is fully offline. The client picks a default skin from the UUID hash, so the mod chooses a UUID that lands on the requested skin.
2. **An online player's name:** their signed skin is copied. Also offline.
3. **Any registered Minecraft account,** for example `/companion spawn herobrine herobrine`. The skin is fetched from Mojang off the server thread and stored with the companion, so restarts don't need the network. The companion keeps its own UUID, never the account's.

With no skin argument, the skin is whatever the UUID hash gives. With `autoSkinFromName: true`, the companion's own name is tried as an account first.

Companions show every layer of their skin (hat, jacket, sleeves, trouser legs) and the account's cape if it has one (Mojang capes come with the skin; mod capes such as OptiFine's are up to the client). Companions spawned before 0.14.8 pick this up the next time they join (a restart, a respawn, or a skin change).

---

## What companions can do

### Being a player

- **Real player entities.** Each companion joins through the vanilla login path, so it has a name tag, a tab-list entry, join and leave messages, survival physics, hunger, fall damage, armour, stats and advancements. Its inventory, health and position are saved in normal playerdata.
- **Human-like control.** Looking turns smoothly with slight aim jitter, reactions have a short delay, and movement uses the same inputs a client sends (walk, sprint, jump, sneak).
- **Inventory.** Right-click a companion to open its inventory as a chest screen (no client mod needed): rows 1–3 are the main inventory, row 4 the hotbar, row 5 helmet, chestplate, leggings, boots and off hand. Take, give and move items as in a chest; shift-click armour from your inventory to put it on the companion. Armour slots only take armour that fits, and Curse of Binding keeps armour on as it does for players. Anyone within 8 blocks can open it. A job counting on something you took notices the next time it checks and plans again.
- **No player slots used.** A world full of companions never turns a real player away with "server is full".
- **Personality.** Chattiness, bravery, curiosity and helpfulness are random but stable per name. They shape how often a companion speaks, whether it fights or flees, how much it wanders, and how readily it works.

### Moving around

- **Pathfinding (ported from Baritone).** A* is weighted in game-tick costs, with Baritone's move set, costs and heuristic, and spread over ticks within a time budget: each companion has its own share every tick (saved up while it's idle, for a long search), and borrows from a shared pool that grows when the server has time to spare, so one companion's hard search can't starve the others. The moves are:
  - walk, mining up to two blocks in the way;
  - diagonal step through open space, or past one blocked corner, sliding round it;
  - jump up one block, mining the head room, or placing the step first if there is none;
  - step down one block, mining the three blocks of the step (which is how it digs a staircase), or walk off a drop of up to 3 blocks, or into still water with ground right under it, never through flowing water (as in Baritone);
  - dig down through the block underfoot;
  - pillar up and bridge gaps with throwaway blocks (placing against a side, or sneaking out and placing behind); a pillar only goes up from a full floor, not from snow or a bottom slab, and a step up is only planned where a jump can clear it;
  - climb ladders and vines;
  - parkour across 1 or 2 block gaps, landing level, never from or onto leaves (Baritone also jumps 3-block gaps and onto ledges one block up; with no run-up those missed every time on the nav bench, so they are left out);
  - swim;
  - open doors and fence gates.
- **Digging.** Every search may dig natural terrain (stone, dirt, sand, gravel, ores, tree-grown leaves). Mining time with the best tool on hand, Efficiency included, is part of the cost, so a path goes round when that is quicker and digs when it isn't. Each step mines whatever is in the cells its body passes through when it runs, as in Baritone. A block hidden from view is reached by mining what hides it only if that is right next to the companion.
- **How far they see.** As far as the server lets players see (its view distance), at most 12 chunks. A path search may reach that far, and the surface within it is in sight: a companion that finds no trees nearby heads for the nearest wood it can see (across water, past a hill) before searching at random. Underground, ores and caves are still only found close by. Seeing the surface reads just the top block of every other column and is shared by all companions for a few minutes.
- **Smooth walking.** On open ground it walks straight at the furthest reachable step instead of zig-zagging from node to node. Flat, clear ground is costed at sprint speed when the companion will sprint it. It keeps walking up to a block it is breaking on a level step (a leaf at head height) instead of standing still for each one.
- **Forests.** Standing on leaves costs extra, so paths keep to the forest floor and break leaves at head height rather than climb onto the canopy and cross the treetops. Under trees counts as outdoors, not underground (the leaves are ignored), so a forest doesn't trigger digging straight up or torch placing.
- **Following a moving goal.** When the goal moves (an owner walking on), the companion keeps its current path if that still ends in the new goal, or for a partial path, still gets it at least 2 blocks closer. Otherwise it re-plans, and the new search costs the old path's remaining cells at half, so it keeps to the same way round obstacles instead of switching sides each time (Baritone does both).
- **Avoiding hazards.**
  - **Lava** and fire are strongly avoided. A companion that ends up in lava anyway wades straight to the nearest dry footing within 10 blocks (path finding can't help there, since every block around counts as deadly).
  - **Water:** swimming costs about twice as much as walking, as in Baritone, so paths go around water when the dry way is not much longer. Flowing water is never walked off into or fallen through: the current pushes the body off its line.
  - **Waterfalls:** no climbing (vines, ladders) or swimming up in or right beside falling water, which drags you back down. No digging-up-and-pillaring while standing in water either.
  - **Swimming upright:** in water a companion only sprint-swims (the swimming pose, head under) where the route itself goes under water. Across the surface it swims upright, so it can breathe.
  - **Breath:** a path may stay with the head under water for at most 14 blocks in a row, fewer when the companion is already short of air. Longer underwater stretches cost more the longer they get. It never routes through a flooded tunnel longer than a breath lasts.
- **Following a path.** As in Baritone, each step knows where the body may be while it runs. A companion knocked back onto an earlier step picks up from there. One more than 3 blocks off the path, or more than 2 blocks off for 10 seconds, re-plans. It jumps only on steps that go up (when lined up and close to the step, or at once when nothing overhead could bump its head), and on parkour steps at the edge of the gap.
- **Getting unstuck.**
  - **Stuck while walking:** it re-plans, and the second time at one spot it plans round it. After five, the move fails.
  - **No path at all:** it searches once more, allowing a jump down of more than 3 blocks (like a player stuck on a pillar, as long as the fall leaves it at least 3 hearts) and digging the base's ground.
  - **Underground with the goal above:** if that finds nothing either, it digs straight up like a player, breaking the block overhead, jumping and placing a block underneath. It stops before fluids, sand and gravel, and anything that isn't natural terrain.
  - **Short searches:** a path that ends just short of the goal re-plans, and a search that fails almost at once (planned from mid-step, mid-fall or mid-swim) is tried again half a second later.
  - **Standing on an edge:** when the middle of the companion is over a drop, the search starts from the block it is actually standing on.
  - **Long searches:** as in Baritone, a search that has a partial path worth taking (one that ends more than 5 blocks from the start) stops after `pathPrimaryNodes` nodes and hands it over; one that has nothing yet runs on to `maxPathNodes`. The limits count nodes, not time, so how busy the server is changes how soon a search ends, never how it ends. The partial path ends at the best node by distance left plus cost so far (cost divided by 1.5, 2, … 10, the first that ends more than 5 blocks out). The companion walks it and plans the next stretch on the way.
  - **The world changing ahead:** four times a second it re-checks the next five steps, like Baritone: it re-plans when a block appears that it may not mine, the floor it needs is dug out, or lava flows in. A block it can mine is simply mined when the step runs.
  - **Slow steps:** a step that takes 5 seconds longer than the planner expected counts as stuck.
  - **Doors and gates:** a step through a closed door or gate isn't done until it has been opened (pressed against a closed gate used to count as through it).
  - **Steps down by an edge:** where the ground past the landing drops away, it comes in at half speed, so it doesn't overshoot into the drop.
  - **Bridges:** after two bridge blocks it couldn't place, it looks for a way round without bridging. Bridges are only planned off blocks with solid faces (not leaves).
- **Breathing.** When a companion's air drops below half, it stops everything and swims to the nearest air the water actually leads to: up to about 32 blocks of swimming, never air behind a wall or above a solid ceiling. It swims that route itself, block by block (sideways, down, up), without the pathfinder. With no air in reach it swims straight up.
- **Giving up on wet jobs.** A job that sends a companion up for air three times is abandoned ("there's too much water here"), and counts as a failure of that method.

### Awareness

- **Surroundings.** Each companion keeps a rolling picture of everything within 8 blocks: ore of every kind, water, lava, and where to come up for air. It reads one horizontal layer per tick, so the whole cube refreshes about once a second at a small, steady cost.
- **Seeing like a player.** A block counts as reachable when the eyes can see any part of it within reach, not only its centre.
- **Animals in the distance.** A hunter looks up to about 96 blocks away (48 up or down) for a herd, and remembers where herds were.
- **Memory.** Places (home, bed, furnace, last death), ore, tree, cave and herd sightings, lifetime stats, biomes visited and people met are saved per companion.

### Gathering wood

- **Whole trees.** Once a companion starts a tree it finishes it. It chops every log in reach, lowest first, breaking a leaf that hides one. For logs out of reach it asks the path finder for a spot within reach of the next log, the way Baritone mines logs: the path breaks leaves and towers up on throwaway blocks as needed. With no blocks to climb with, it digs some dirt nearby first. The tower is left standing; at home the base upkeep clears it, as with other litter.
- **Tree choice.** It prefers trees about the size it needs. Trees within 8 blocks of another companion's tree are left to them, as with ore veins, so companions spread out through a wood (they share only when every tree is near someone). All candidates go into one path goal; A* reaches the cheapest to get to, and the companion locks onto it.
- **Searching.** With no trees in range it goes to a remembered wood. If it's underground it heads out to open sky first, whichever way that is (a cave mouth, or digging up); otherwise it walks about 48 blocks in one direction (away from where others are working) and looks again, up to 5 trips. Underground means no sky in sight anywhere around it, so standing under an overhang or at the foot of a cliff doesn't count. Hunting searches the same way, and from underground it heads out before going after animals it noticed up on the surface.

### Mining

When asked for an ore, a companion works as Baritone's mine command does:

1. Every ore of that kind it knows of within about 24 blocks counts, exposed or buried, including spots it or a friend remembers. The nearest 32 become one path goal: get into the ore block, or right under it. A* reaches whichever is cheapest to get to, digging included, so the nearest reachable ore wins, and the path's last moves mine it. The companion locks onto that ore so others leave it be.
2. With none in range: a cave. It goes in and looks again. For deep ores (diamonds, redstone, gold, lapis) it searches further and prefers caves near the ore's depth.
3. If there's no good cave, it digs a staircase down to the ore's depth, then strip-mines forward, looking again every 16 blocks.

While mining:
- **Whole veins.** It mines every block of a vein, including blocks joined only at the corners, and steps over to reach the rest. It finishes the vein even past the requested count.
- **Ore on the way.** Any of the target ore it passes within reach while walking, exploring or heading for a cave gets mined.
- **The base's ground.** Mining leaves home's ground alone (the home zone, from 6 blocks under its ground upwards, hills included), including when digging a staircase or strip mine; it walks out of home first. Deep caves under home can still be mined. Soaks showed stone jobs digging out the base until its chests and furnaces stood on one-block pillars.
- **Wet and dangerous ore.** Ore touching liquid above or beside it is never mined, including when following a vein.
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
- **Noticing animals.** While doing anything else, a companion notices groups of three or more food animals within 64 blocks and remembers where the herd is (the middle of the densest bunch). Remembered spots it can see that turn out empty are forgotten. Up to 12 herds are remembered.
- **Chasing.** Up close it runs straight at the animal, but never off a ledge; it paths around instead.
- **Searching.** It looks within 40 blocks (and chases up to 56), then for herds up to 96 blocks away (48 up or down), then at remembered herd spots. Only then does it walk off in a direction, up to 8 trips of 48 blocks. Search trips stay inside the world border and out of enemy land (no herds, trees or searching there). A fight or a meal on the way pauses the trip, which then carries on: it doesn't use up a trip or the herd it was heading for.
- **Weapons.** It never hunts bare-handed: with nothing better than a fist, it makes a wooden sword first.
- **Keeping food:**
  - **Free mode** keeps a reserve: with fewer than 6 food items it hunts and cooks when it knows where animals are, before getting hungry.
  - **Follow mode** cooks raw meat it carries, or hunts nearby, when it gets hungry with nothing to eat.
  - **Cooking:** raw meat counts as food in a pinch, but it gets cooked, since a steak fills far more than raw beef.
- **Eating.** A hungry companion eats its best food, asks a friend, or hunts.

### Farming

- **The farm** is the base's farm zone (a flat 9x9 near water, laid out with the other zones, or set by hand with `/companion colony zone farm here`), and the plots added next to it. In a wood with no open ground, the best plot that would do once its trees are down is laid out anyway: home grows round it, its trees are felled with the rest of home's, and farming starts as the ground clears.
- **More plots** (`expandFarms`). Every few minutes, once every plot is three-quarters planted, the farm grows: a new 9x9 plot (`farm2`, `farm3`, `farm4`) one block from an existing plot (a path between them), on whichever side has the most flat soil at about the same height, nearest water and the core. A base gets one plot, plus one for every four members, up to four. Home grows to take them in.
- **Water.** One water block wets farmland up to 4 blocks away, so a 9x9 plot is all wet from a block of water in its middle. A plot with dry soil (not within 4 blocks of water) gets one: the farmer fills a bucket at the nearest open water (making a bucket first if the base has 3 iron, or taking one from the chests), digs out the plot's middle and pours it in. The middle of every plot is kept for water: never tilled, planted, or filled back in by upkeep.
- **Tending it** ("farm", or free mode when there's something to do), nearest work first:
  1. **Harvest** ripe wheat, carrots, potatoes and beetroot.
  2. **Plant** empty farmland. Potatoes and carrots go first (they're food straight away), then wheat seeds, then beetroot seeds.
  3. **Pull up grass and flowers** in the farm, and grass around it when it has no seeds (grass drops seeds now and then): within 8 blocks, else up to 20. Seeds, potatoes or carrots in the base's chests are taken first.
  4. **Till** grass and dirt with a hoe when there are seeds to plant. It makes a wooden hoe first if it has none.
- **Picking up.** Drops are picked up like any others.
- **Free mode** goes farming by day, within 64 blocks of the base, when it's worth the trip, and bakes bread from 3 wheat when short of food:
  - at least 6 ripe crops (any, when it has no food and is hungry);
  - at least 3 tiles to plant or till;
  - grass and flowers in the way, or (with nothing to plant) grass around the farm to pull up for seeds;
  - or a dry plot, when it carries a bucket.

  One companion tends a base's farm at a time, and a farmer rests from farming for 3 minutes after a run. The farm is looked at every 20 seconds, shared by everyone at the base.

### Taming wolves

- **When** (`taming`, free mode, by day): a wild wolf (not tamed, not angry) is in sight within 24 blocks, and the companion has bones, or the base's chests do and the base is close enough to fetch them (it takes up to 8 first). Not with two wolves of its own about already, and not again for 5 minutes after taming one.
- **Low priority.** It's offered last among free mode's goals, so it's picked only when nothing more pressing is on, and like every free-mode goal it's only chosen between jobs: it never interrupts one, or a leader's order.
- **How:** walks up to the nearest wild wolf and feeds it bones, as a player does (a one-in-three chance each), up to 10, until it's tamed. Then the wolf gets up and follows its new owner, and fights what it fights. Gives up if the bones run out, the wolf gets away, or it can't get near within 30 seconds.

### Fishing

- **Finding water.** While about, a companion notices open water (a lake, a river, the sea: enough of the surface round it) and remembers it.
- **Going fishing** (`fishing`): by day, a curious companion (explorers and scouts) with remembered water within 64 blocks, and a fishing rod or the makings of one (two string, and sticks or wood), goes fishing now and then; anyone goes when short of food. "Go fishing" asks for it.
- **How.** It makes a rod if it has none (three sticks and two string, from the chests if need be), walks to a dry spot on the shore with open water in front, casts out, waits for a bite (the moment the bobber dips) and reels in, until it has caught 3 to 7 fish (6 when hungry). A cast that lands on the bank is reeled in and thrown again; a spot where casts keep missing is forgotten. Raw cod and salmon are cooked like raw meat.

### Picking up after itself

- **Drop tracking.** Every block a companion breaks and every animal or mob it kills marks a drop spot.
- **Collecting.** Before its next job, and every so often when idle, it collects anything of its own still lying around: logs that fell out of reach, ore that dropped into a hole, its broken crafting table, meat.
- **Other people's items.** Items someone threw (your drops, another companion's delivery) are never collected on purpose.
- **Unreachable items** are let go of, and the log says what it picked up and what it missed.

### Combat and safety

- **Fighting.** With at least 4 melee damage it fights any hostile mob, creepers included, backing off while a creeper hisses.
- **Reacting.** It keeps an eye out between decisions (every 3 ticks) and decides at once when something new comes at it or a friend. Getting hit makes it, and every faction-mate within 24 blocks, look round and decide on the spot (fight back, back off). It then takes 2-5 ticks to land the first swing at a new target.
- **Critical hits.** In reach with its swing nearly charged, it often jumps and strikes on the way down (vanilla's critical hit, 50% more damage). Aggressive companions do it more.
- **Shields.** A shield it carries goes in its off hand. It raises it while a skeleton draws its bow, a player draws a bow or a creeper hisses close by, walking in behind it toward a skeleton and backing off from a creeper, and lowers it to hit back. A shield is on the progression ladder after the iron sword.
- **Players and other factions.** It fights players its faction considers enemies, other factions' companions it's at war with, and anyone who just hit it, as warfare allows (see [Factions, standing and warfare](#factions-standing-and-warfare)). It stops when they stop being enemies (an apology, a ceasefire, or in `defend` mode once they leave the base).
- **Mobs under water** (drowned, guardians) aren't chased into the water. It only hits back when one is right on top of it.
- **Fleeing.** Unarmed, it runs (never off a cliff). At low health it retreats from monsters that are close, and from anything that shoots (skeletons, pillagers, witches, blazes) that can see it within 16 blocks; from those, safe means out of their sight or 24 blocks off, and it prefers somewhere they can't see. Too hurt to fight, it only hits back when a mob is right on it. Hurt with no food, it can't heal (natural healing needs a nearly full hunger bar), so it asks a friend for food or hunts.
- **Armour.** It wears the best armour it has.
- **Protecting others.** Companions defend each other: a mob going after a nearby companion counts as a threat. With "defend me", it also attacks mobs targeting you.

### Death, rejoining and getting lost

- **Respawning.** A companion that dies comes back after `respawnDelayTicks`. It returns at its bed, else home, else its faction's base, else (when following you) next to you if you're standing somewhere safe, else world spawn, always on dry, solid ground. It never reappears looking dead. A bed or home at another faction's base, or on enemy land, is skipped. Joining a faction with a base makes that base home (and forgets a bed somewhere else), so members who change sides don't come back to life among their old faction.
- **Getting its things back.** After respawning it goes back for what it dropped, and keeps looking for as long as the items last (5 minutes from the death), unless it died in lava, fire, the void or by drowning, or that spot has already killed it twice in 10 minutes. Killed by a player or another faction's companion, it waits until the killer has moved off (more than 40 blocks from the spot, dead or gone), checking every few seconds, then goes. A death also counts as a failed goal, so free mode puts that goal aside for a while.
- **Unsafe saves.** A companion saved somewhere deadly (under water, in lava, inside a block) is moved to the nearest safe spot when the world loads.
- **Not getting lost.**
  - **Nobody to follow:** in follow mode with you offline, spectating or in another dimension, a companion that's finished or whose job failed heads home if it's more than 24 blocks away.
  - **Can't find a way back to you:** following you from far away, it walks if it can. It teleports only if the path fails, or it's still more than `teleportToOwnerDistance` away after 30 seconds of walking, and at most once a minute. It's never called back while on a job, however far the job takes it.

### The base and shared storage

- **One shared base.** Saying "this is home" sets the base for all companions. It's saved in `<world>/botcraft/colony.json` along with the base chests.
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
- **Base stations.** A crafting table or furnace put down at the base stays there for everyone, instead of being picked back up. Companions at the base use the base's own table and furnace (from the base map, up to 24 blocks away) before putting down another.
- **Gear in the chests.** Armour, tools and weapons in the base chests that beat what a companion wears or carries get taken and worn: in free mode as soon as it's near the base, and before an ordered mining, chopping or hunting job that starts at the base. Only companions whose home is the base do this. A piece about to be put on isn't stored again.
- **The base map.** The ground within 40 blocks of the base is read a few columns per tick (a full refresh every few seconds): ground height and kind in each column, and where the chests, furnaces, crafting tables, beds and torches are. It's shared by everyone.
- **Zones.** Once the base map has seen the whole area, the base is laid out and saved in `colony.json`:
  - **core:** 7x7 around the home spot, for chests, furnaces and the crafting table; new chests go here first;
  - **beds:** a row of bed slots (11 long, 2 deep) on the flattest side just outside the core; beds put down at home go into the next free slot, heads toward the core;
  - **farm:** a flat 9x9, soil preferred, near water if there is some, 10-28 blocks out (and `farm2`-`farm4` as the farm grows, see Farming);
  - **pen:** another flat 9x9, clear of the farm;
  - **paths** from the core to the farm and the pen;
  - **home:** the base and all the zones above, plus 3 blocks, and at least 16 blocks each way from the home spot (33x33). It grows when a zone is added.

  Nothing but what a zone is for gets put down on the bed row, the farm, the pen or a path. `/companion colony` lists the zones (outside single mode, of the faction whose land or base you're at; `/companion colony of <faction>` for another; the zone commands work the same way). `/companion colony zone <kind> here [size]` sets one by hand, centred where you stand (the bed row runs along the way you face); planning never replaces it. `/companion colony zone <kind> clear` hands it back to the planner. Moving the base plans the zones again (except hand-set ones).
- **Blocks companions put down to get around** (bridges, pillars, shelter roofs, the tower for chopping a tall tree) are remembered in `colony.json` as litter. Navigation may break those again, so nobody gets walled in by a roof or pillar another companion left behind.
- **Natural or built.** Companions tell what nature made from what someone built, and only take resources from natural blocks:
  - every block a player places is recorded, and so are base blocks companions place (chests, furnaces, tables, beds, torches), in `<world>/botcraft/built.dat`; breaking a block takes it off the record;
  - for anything older, blocks that never occur in nature count as built: planks, bricks, glass, wool, stairs, slabs, fences, walls, doors, polished, cut and stripped blocks, cobblestone, concrete, persistent (placed) leaves and every block entity;
  - a log counts as part of a tree only if its trunk is topped with tree-grown leaves, so a log cabin's walls are built.

  Built blocks are never broken while getting around or digging (unless `griefing` is on), dirt someone put down isn't dug for pillars, and chopping a tree stops at logs someone placed (a cabin wall the tree grew against).
- **Looking after home.** Inside the home zone:
  - **no mining or digging for dirt** (see "The base's ground" under Mining);
  - **no litter:** a bridge or pillar block at home costs as much as walking about 20 extra blocks, so paths go round when they can (not when escaping from a hole);
  - **tables and furnaces go in the core.** A companion elsewhere at home walks to the core to put one down;
  - **upkeep:** every few seconds the base looks over home and 12 blocks round it for:
    - litter companions left (taken down, unless it fills a hole in the ground or holds something up); dirt a companion put down that has since grown grass still counts;
    - pillars and lone blocks of dirt, grass or cobblestone standing up out of flat ground (older litter nobody recorded), and stumps (logs with no leaves on them, what a felled tree leaves); nothing a player built, and nothing holding up a torch, chest or the like;
    - natural trees standing in the home zone (`clearTrees`): felled, all of them. While a base still has trees in its home zone or no farm or pen yet, clearing it comes first: the tidy request goes to the top of the board (leaders hand it out first), and free companions near the base join in felling, several at once, until it's done (the logs go into the chests). Otherwise, by whoever takes the job (it needs nothing to climb with: home's open ground may be dug for dirt to tower up on, and upkeep fills the holes back in afterwards). On a base founded in a wood, that's what makes room for the farm and pen;
    - one-block pits in flat ground, and ground a companion dug out at home (filled back in; holes players dig in the zones are left alone);
    - bumps or dips up to 3 blocks deep in the core, bed row, farm plots and pen (levelled to the zone's ground; never built blocks, trees, water or ground under furniture);
    - a fence round home (`fenceHome`), once the chests hold 24 logs: fence posts along the home zone's edge, with gates where the paths cross it and in the middle of each side. Fences and gates are made from the base's wood. The fence follows the home zone, not the faction's land: when home grows (a new farm plot), a new fence goes up along the new edge and the old one stays inside it.

    It goes on the request board as "tidy up the base", and a free companion takes it like any other request. Holes are filled with dirt or other throwaway blocks the companion carries. `/companion colony` shows what's waiting.
- **Night chores.** An armed companion at the base at night (with no bed) stores its things, cooks raw meat, smelts raw iron and crafts iron gear from carried and stored materials. It doesn't leave the base for anything after dark.

### Torches

- **In caves and tunnels.** Underground with torches on hand, a companion puts one on the floor beside it whenever its spot gets dark (block light below 6), so the places it mines and explores end up lit every six or seven blocks. It waits until it isn't mid-swing or fighting.
- **No coal yet?** On a new base, free mode doesn't wait for coal to turn up: with a pickaxe it goes and mines 4 coal, otherwise it burns 6 logs into charcoal, then makes the torches.
- **At the base.** Monsters spawn on pitch-dark ground. When home has any (out to the corners of the home zone, up to 32 blocks from the base: within 12 blocks at about the base's level, roofs and all, and further out the open ground, uphill and down), free mode lights it up: torches it carries, or torches it crafts from coal and sticks (taken from the chests if need be). It's also a night chore at the base, and an order ("light up the base").
- `placeTorches` in the config turns all of this off.

### Watchdog

Every task has its own time limit, but a task can keep itself busy without getting anywhere (re-planning to the same unreachable spot, retrying the same placement). If a companion is on a job and for 3 minutes it neither moves more than a few blocks, nor breaks anything, nor gains any items (eating doesn't count), the job is abandoned with "I got stuck" and the usual failure handling takes over. Sleeping, sheltering for the night and waiting on a friend are exempt.

### Working together

- **Spreading out.** Each companion claims the tree, ore, cave or animal it's working on. When choosing, spots near someone else's claim, or near any player or companion standing there, are avoided while there's another choice.
- **Seeing each other.** A few times a second each companion publishes where it is, what it's doing and the next four blocks of its path. Path searches treat the blocks others stand in or are about to step into as busy (passable, but avoided when there's a way round), and a dropped item someone is going for is left to them for 10 seconds. `/companion colony` lists everyone and what they're doing.
- **Base stock and the request board.** The base keeps a stockpile, with these defaults:
  - 32 cooked food;
  - 32 coal;
  - 64 logs;
  - 16 iron ingots;
  - 32 torches;
  - 64 cobblestone.

  Change these, or add any other item, with "the base needs 64 coal", "keep 20 glass in the chests", "we have enough cobblestone" or `/companion colony want`.

  When the chests are short, a request for one batch goes on the board (16 logs, 8 coal, 6 food...). A free-roaming companion with nothing pressing (night, storage, food, basic tools and a bed come first) takes the most important request it's the best bidder for, gets the batch whichever way the planner finds cheapest, and stores it, even out of its own working stock. It says what it's doing: "I'll get 8 coal for the base, it's quickest for me."

  A bid adds up a few terms, in the same unit (two minutes = one point):
  - **time:** how long the planner thinks it would take that companion, with what it has learned;
  - **to base:** how far it is from the base;
  - **busy:** a penalty for being in the middle of something of its own;
  - **turns:** how many requests it has done lately;
  - **failed:** whether it failed at this one recently;
  - **helpful:** a bonus for a helpful personality.

  A companion carrying spare (beyond its kit) drops it off. Each companion holds one request at a time and alternates them with its own progression. A request whose holder dies, gets orders, or stops working on it goes back on the board. `/companion colony` shows it all.
- **Sharing.** When a job needs something worth a trip (coal, ores or ingots, gems, wool, a pickaxe, food), a companion asks the *idle or free-roaming* friend with the best bid ("Hey Ann, could you spare 3 coal?"): closest, with the most to spare, and not busy with something of its own. The friend brings it over and goes back to what it was doing. These requests show on the board too.
  - Cheap things (cobblestone, logs, dirt, planks, sticks) it always gets itself.
  - At most one request every 3 minutes, and the same item at most every 10 minutes.
  - A helper helps one companion at a time, then gets a 2-minute breather, and is never pulled off one of your orders.
  - Only spares are given, and tools only if the friend has two. Handed-over items can only be picked up by the recipient.
  - If nothing arrives, the companion gets the item itself.
- **Social life.** Companions greet each other and banter when idle: weather, work, favourites, jokes, complaints. They also share where they found ores, which the listener remembers. They praise each other's kills, mourn deaths and come to each other's help.

### Personality and mood

- **Twelve traits,** each 0 to 1, stable per companion: chattiness, bravery, curiosity, helpfulness (the original four), and diligence, aggression, caution, sociability, generosity, ambition, loyalty and humor. Companions from before 0.12 get the new eight from their UUID, so they don't change between restarts. `/companion info <name>` shows them.
- **What they do:**
  - bravery and caution: how hurt it gets before retreating;
  - caution: how much risk and past deaths weigh in the planner;
  - aggression: how readily it fights players and enemies, and how often it goes for a critical hit;
  - diligence: how strongly it sticks with a goal once started;
  - ambition: how much its own progression matters, and who founds a faction's base;
  - loyalty and helpfulness: how much the base's needs matter, and how likely it is to leave a faction;
  - curiosity: exploring;
  - generosity: how much it keeps back when a friend asks for something;
  - sociability and chattiness: banter.
- **Archetypes.** `/companion archetype <name> <miner|farmer|guard|scout|builder|trader|leader|hothead|coward|joker>` sets a preset; `/companion trait <name> <trait> <0..1>` sets one trait.
- **Experience** nudges traits a little (never more than 0.2 from where they started): each death makes it a bit less brave, kills make it a bit more aggressive.
- **Mood** is short-lived and fades on its own: fear (damage, crowds of monsters), joy (jobs done, kills, praise, gifts), frustration (failures, deaths), fatigue (long work; cleared by sleeping), boredom (the same goal over and over), grief (a friend died nearby) and anger (a player hit it). Fear makes it retreat sooner; fatigue makes progression less appealing and boredom exploring more; and strong moods change how it talks (dialogue keys can have `.scared`, `.happy`, `.grumpy`, `.tired`, `.sad` and `.angry` variants).
- **Relationships.** It remembers how it feels about each player and companion: liking (-1 to 1), trust (0 to 1, starting at 0.5) and favours owed. Gifts, help in a fight, thanks and praise raise them; insults, hits, deaths and theft lower them; they drift back toward neutral over a few in-game days. They show in `/companion info`.

### Factions, standing and warfare

- **Modes** (`factionMode`, or `/companion faction mode`):
  - **single** (the default): one colony, exactly as before 0.12.
  - **teams:** fixed teams. `/companion faction teams red blue` splits the companions evenly between new teams Red and Blue (`... red blue owner` keeps each owner's companions together; any number of teams). Teams start as strangers: neutral, with a grace period (below). `/companion faction relation red blue hostile` makes them enemies at once if that's what you want.
  - **dynamic:** factions form and split on their own, and fight and raid each other.
- **No faction.** Outside single mode, the default colony isn't a faction at all: it's the companions with no faction, each one for itself. It has no relations, wars, land, raids, leaders or bounties, and nobody's at war with it; companions deal with each of them as an individual (whoever hits them gets hit back). They still share the colony's base and chests between them, and are taken in by a faction when `recruit` is on. `/companion faction` lists them as "no faction".
- **A faction has its own everything:** its own base (the default colony keeps `colony.json`; others are `colonies/<id>.json`), its own base map, zones, upkeep and request board, and its own stock. Companions only bid on, share with and ask for help from their own faction (or allies). Names show in the faction's colour (a vanilla scoreboard team), and members can't hurt each other.
- **Bases found themselves** (outside single mode, `dynamicBases`): a faction without a base sends its most ambitious member, by day, to the best spot nearby: flat ground, water and trees close, no lava, not the sea, not on someone's buildings, at least 160 blocks from another faction's base and 3 chunks from its land (the further the better). Open ground on the site counts most (a clearing beats the middle of a wood, which has to be felled first), with water and woods a short walk away (up to 38 blocks out). If nowhere in sight will do, it walks on, away from the nearest other base, and looks again. It founds the base there (every member's home), and the zones are laid out as usual. Zones set by hand are never moved. In single mode, you still say "this is home".
- **Land** (`territory`, teams and dynamic mode). A faction holds the chunks round its base (5x5 chunks to start, never lost) and spreads out every minute and a half into unclaimed chunks on its frontier, nearest the base first: one chunk a time plus one for every four members, up to 25 + 8 per member, and no further than 14 chunks from the base. `/companion faction info` shows how much it holds and whose land borders it.
  - **Borders.** When two factions' land first touches, they meet. Enemies already at war say so, and the fighting starts at the border. In dynamic mode, neutral ones size each other up: an aggressive leader, and lopsided numbers, make war likely ("We're going to war with Red!"); a sociable other side makes a truce likelier ("Truce with Blue. Stay on your side."), which guarantees 20 minutes of peace.
  - **Only neighbours fight wars.** Raids, and declarations of war, only happen between factions whose land touches (a raid ordered with `/companion faction raid` goes anyway). Enemies on a faction's land count as intruders (see warfare below).
  - **Occupation.** At war, a chunk of the enemy's border land where a faction's companions stand and none of the enemy's do becomes its own (never the 5x5 round the enemy's base).
  - **Peace terms.** The side that sues for peace gives up 3 chunks of its border land to the other.
- **Bounties** (`bounties`). Every kill of a faction's member puts a price on the killer's head, player or companion (a leader's death counts double). Bounties halve every 30 minutes without another kill. From 3 the killer is wanted ("Red10 has killed 3 of us. There's a price on their head now."): fought on sight as long as the two sides are enemies, spotted from 32 blocks instead of 16, picked before other enemies, and their faction is the first one raided. The member who kills a wanted target collects: the bounty is cleared, it's cheered, and its friends think better of it. `/companion faction info` lists the bounties.
- **Player standing.** Each faction keeps a standing for every player it has dealt with, from -100 to 100, starting at `playerStartStanding` (0); players who own a companion in the faction, or are members, start at `ownerStartStanding` (90).

  | What you do | Standing |
  |---|---|
  | hit one of its companions | -25 (the first hit while friendly gets a warning) |
  | kill one | -60 |
  | open its base chests without being trusted (once a minute at most) | -15 |
  | take things out of a companion's inventory without order rights | -15 per stack |
  | break something built at its base | -5 per block |
  | kill a mob that was after one of its companions | +5 |
  | give a companion something (through its inventory screen) | +2 per stack |
  | say sorry afterwards | +10 (+20 if that companion trusts you) |
  | a quiet in-game day | 10% of the way back to where you started |

  The bands are trusted (60+), friendly (20+), neutral, and hostile (-30 or less), with a 5-point margin before you drop out of one, so a player near a threshold doesn't flicker. The companion it happened to (and friends who saw it) also remember it personally, and the victim calls it out in chat ("Steve just attacked me!"). An enemy can't open a companion's inventory. `/companion standing <player>` shows what every faction thinks of a player; `/companion standing <faction> [player] [value]` shows one faction's view, or sets it; `/companion faction info <faction>` lists everyone.
- **Owner authority** (`ownerAuthority`, or in game `/companion faction authority absolute|standing`, saved to the config): `absolute` (the default) means your own companions always obey you and never treat you as an enemy, whatever you do. They still feel it: hitting or killing one is remembered (it and its friends like and trust you less), they say so ("I'll still do what you say. But I don't trust you any more."), and the faction's standing for you falls like anyone's (see `/companion standing`), but there's no bounty and no fighting back. `standing` means you're judged like anyone else, starting trusted at `ownerStartStanding`: hit them enough and they stop listening, fight back, and put a price on your head.
- **Warfare** (`warfare`, or per faction with `/companion faction warfare <level> [faction]`):
  - **off:** never fight players or other factions.
  - **defend** (the default): fight whoever just hit them, and enemies (hostile players, enemy factions' companions) who are inside their base (48 blocks) or on their land, who just attacked one of them, or who are wanted.
  - **pvp:** enemies on sight.
  - **raid:** pvp, and raids on enemy bases. Dynamic factions always raid.
- **Raids.** Every minute, a raiding faction with a base looks at the nearest enemy base. It raids if two or more of its free-roaming members are armed and healthy, they clearly outmatch the defenders it can count (aggressive leaders settle for less), and it hasn't raided in 15 minutes. Up to four go together. The raiders walk to the enemy base and fight whoever's there. With `griefing: true` they break in (the path finder may go through what the enemy built, except doors, beds and gates) and take the most valuable things from the enemy's chests: diamonds, gold and emeralds first, then iron, gear and tools, then food and fuel. Without griefing it's a two-minute skirmish and nothing is taken. Raiders pull out below 40% health, when their pockets are full, or after two minutes, then go home and put the loot away. `/companion faction raid <faction>` sends one now.
- **Splitting up** (dynamic mode). Every few minutes each member's unhappiness is worked out from:
  - disliking the others (fights, grudges, no friends among them);
  - ambition without loyalty;
  - frustration;
  - crowding.

  Unhappy members grumble. When three or more stay unhappy, and they're a quarter of the faction, the most ambitious leaves with them, and with whoever likes it better than the rest, as a new faction ("Bob's Band"). They start on cool terms with the old one (as enemies with `territory` off), found their own base well away, and when their land meets the old faction's it's war or a truce. At most one split per faction per in-game day, and at most `maxFactions` factions. Empty factions disband. Companions grow fond of each other by chatting and helping each other out.
- **Grace** (`factionGraceMinutes`, 15). A new faction (teams just made, a split) starts neutral with everyone, and for its first 15 minutes nothing can sour past cool: hits between factions still count against them, but can't make them enemies, and no war is declared or started at a border.
- **Getting on** (teams and dynamic mode). Every two minutes, for every two factions whose members are within 12 blocks of each other:
  - **Sparks:** a hothead (aggression 0.65 or more) may pick a quarrel with the other side's member it's near ("Watch where you're going, Bob!"). The two factions think 8 less of each other; four or so quarrels and they're enemies, and the fighting starts. Hotter heads quarrel more. Not during grace.
  - **Getting along:** otherwise, sociable members who meet without trouble warm the factions to each other, a point or two each time, up to alliance (60 both ways).
- **Recruiting** (`recruit`, teams and dynamic mode). A companion in no faction of its own (newly spawned, or its faction gone) is taken in within half a minute: the smallest team in teams mode, the faction it likes best (nearer ones first) in dynamic mode. Its new leader welcomes it.
- **Leaders' plans** (teams and dynamic mode). Every three minutes or so by day, a faction's leader (or an officer while it's away) thinks about the bigger picture:
  - **Expand:** with free land at the frontier and the faction under its land cap, it sends up to three idle members (the bravest and most ambitious) out to stake claims ("Push our border out"): a free chunk on the border with one of the faction's members standing in it becomes the faction's at once, on top of the steady spread. Ambitious leaders do this more.
  - **Scout:** it sends its most curious idle member 120-200 blocks past the border, towards a faction it hasn't met (else somewhere new), and back. Curious leaders do this more.
  - **War for resources** (dynamic mode): when its base is under half stocked and a neighbour is clearly richer (by what's in its chests) and weaker (fewer, worse-armed fighters), an aggressive, ambitious leader may declare war on it ("We need what Blue has. Arm up, we're taking it.") and send a raid at once. Never in a grace period or a truce, and only against neighbours.
- **Merging** (dynamic mode). Every three minutes, two factions may become one if:
  - they aren't enemies, neither has split or merged in the last in-game day, and they're close (land touching, or bases within 200 blocks, or one has no base yet);
  - they think well of each other (40 or more both ways) and their members get on;
  - the smaller side's leader is willing to stop leading (not too ambitious, or fond of the other leader).

  A band warming back to the faction it split from, or a faction down to two members, comes round more easily (20 is warm enough, and it's likelier). The larger faction (or the old one, for a reunion) takes in the other's members, land, the players it knew and its bounties; the other's leader becomes an officer, and its base's chests stay where they are. `/companion faction merge <into> <from>` does it at once.
- **War and peace** (dynamic mode). Every two minutes:
  - **Peace:** a faction at war that has lost 6 or more members to the other side in 20 minutes, at least twice what it took from them, sues for peace ("We've lost too many. Truce with Blue!"). The two become neutral, and neither declares war on the other for 20 minutes.
  - **War:** after that, a bigger faction with an aggressive member may declare war on a neutral neighbour with a base ("We're going to war with Red!"). Aggressive factions, and lopsided ones, do it more.

  So wars come and go instead of lasting forever.
- **Leaders and officers** (teams and dynamic mode). Every two minutes, and as soon as teams are made or a faction splits:
  - **A faction without a leader picks one:** the member the others would follow. Ambition counts most of its traits, then bravery, loyalty, sociability and diligence, but what the others think of it (liking and trust) counts more than any of them. A player who leads a faction keeps leading it.
  - **The leader picks its officers:** one for every four members (none under three members, three at most), the ones it likes and trusts most, weighed with their loyalty, diligence, bravery and weapon. An officer is only replaced by someone clearly better (0.15 on the leader's scale), and steps down if the faction shrinks.
  - **Succession:** if the leader leaves the faction or is removed, its best officer takes over, else a new pick.
  - The leader speaks for the faction (officers when it's away), founds its base and gets the founder's pick. `/companion faction info` shows the ranks.
  - **Giving orders.** Every 30 seconds by day, the leader (or an officer while it's away) looks over the faction: how well the base is stocked against its stock targets, and who's standing about. Idle members are given the most important open request they can do ("Bob, see to 16 logs for the base."): up to three at a time while the faction is new (its first 20 minutes) or struggling (under 40% stocked), two when middling, one when thriving. Anyone else idle is told to get on with something of their own. Every 8 minutes a struggling faction hears a rallying call, and a thriving one praise (which cheers everyone up).
- **Diplomacy by command:** `/companion faction relation <a> <b> <allied|neutral|hostile|number>`. Enemies are -30 or less; allies are 60 or more both ways. Hits and raids between factions push them apart. `/companion faction ceasefire` makes everyone neutral at once.
- **Respawn grace:** for a minute after respawning, a companion only hits back and doesn't go looking for enemies. A companion killed by a player or an enemy doesn't go back to where it died for its things.
- **Commands:** `/companion faction` lists factions. The subcommands are:
  - `mode`, `warfare`, `teams`;
  - `create <id> [colour]`, `remove <id>`;
  - `join <companion> <faction>`;
  - `member <faction> <player or companion> <member|officer|leader|remove>`: a companion given a rank also joins the faction. Its leader founds the faction's base and speaks for it (truces, declarations of war). Ranks given this way are never changed by the automatic leadership below. A companion still following you stays with you; say "do your own thing" to let it live with its faction;
  - `relation`, `raid`, `ceasefire`;
  - `merge <into> <from>`: one faction joins another (see Merging);
  - `authority [absolute|standing]`: show or switch owner authority (see above);
  - `info <faction>`.

- **Ceasefires and parleys** (dynamic mode, `ceasefires`). A lopsided war ends when the losing side sues for peace. An even one used to go on for ever, every death costing iron and diamond gear. Now, once a war has run 8 minutes and both sides are bleeding (10 deaths between them lately, at least 3 each), every 2 minutes the two leaders may agree to talk: likelier the longer and bloodier it's been, and with calmer, more sociable leaders.
  - **The parley.** Both leaders call everyone home ("Everyone back to base, now!"), and nobody fights the other side while they talk (leader's rules). Each leader walks out with up to two officers to the ground halfway between the bases; when they're face to face (or after 3 minutes, by messenger) they talk.
  - **Hotheads.** Then the most aggressive member of either side may refuse it ("Peace? Never!") and the war is on again: a small chance (12-37%, by how hot-headed), halving every round. After 2 minutes of renewed fighting the leaders call everyone home and try again, round after round, until a parley holds.
  - **The ceasefire** then holds for 40 minutes: no fighting, no raids, no declarations. The terms, RISK-style: the borders freeze (neither side takes free land touching the other's), and the side that lost clearly more in the war gives up border land: a chunk for every 4 deaths more, up to 2.
  - **Strain.** While it holds, what would have brought war back (a member hit or killed, a raid, a hothead's quarrel) can't, but strains the deal. Strain eases off over time; as it builds the leaders warn each other, and after the first 12 minutes enough of it may make the angrier leader call the ceasefire off. Kept with the factions, so it survives a restart.
  - **After it runs out** the two are neutral, and for 30 minutes a new war between them is half as likely.
  - `/companion faction parley <a> <b>` starts a parley; `/companion faction ceasefire <a> <b>` agrees a ceasefire at once; `/companion faction info` shows "(ceasefire, N min left)". (`/companion faction ceasefire` alone still makes every faction and player neutral.)

### Planning and progression

- **Progression targets** are data, a ladder in `brain/plan/Targets.java`. Free mode first gets the basic wooden and stone tools, then works up the ladder:
  1. iron pickaxe and iron sword;
  2. iron chestplate, leggings, helmet and boots;
  3. diamond pickaxe and diamond sword;
  4. diamond armour.

  Each step is "have this": met by carrying or wearing it (or something better). A shield comes right after the iron sword.
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
  - **Thompson sampling:** the success rate the planner uses is drawn from what's known, not averaged. A method with little experience varies a lot from one draw to the next, so one that was unlucky early still gets tried now and then; one with lots of experience settles on its true rate. A draw holds for five minutes, so plans and bids don't flicker. Turn it off with `-Dbotcraft.off=thompson`.
- **Seeing why.** `/companion why <name>` shows the target, the chosen plan and its cost, the alternatives with theirs, and the learned stats.

### Free mode (autonomy)

"Do your own thing" turns on a player-like routine around home. `/companion freemode on` sets every companion loose at once (except ones told to stay) and makes new ones start that way (`startFree`), with home where they arrive; `off` goes back to new ones following whoever spawned them. Between jobs a free companion decides again within a second, so it doesn't stand about.


- **Night** (`nightWork`, on by default): no shutting down. Errands that take it away from home (hunting, chopping in the woods, exploring, fishing) are dropped at dusk and it heads home; then, armed:
  1. **Sleep** only if that gets the night skipped: a real player is in bed, or there's no real player in the world to wait for. (Otherwise lying in bed until morning is wasted time.) It still sleeps once there's nothing left to do.
  2. **Night chores:** store its things, light the base, cook, smelt raw iron, make iron gear from the chests.
  3. **Work close to home:** the base's upkeep from the board (clearing trees, levelling, filling, fencing), the farm, and mining from home for what the base is short of (coal, then iron, then stone), bringing it back.

  Unarmed at home with wood to hand, it makes a sword first; otherwise it digs in (three blocks down, roofed) until morning. Companions working through the night get no phantoms. `nightWork: false` goes back to bed-at-dusk: sleep in a bed (a free one near home, its own, or one crafted from 3 wool), else guard home or dig in.
- **Food:** cook raw meat, ask a friend, or hunt, and keep a reserve of about 6 food when animals are known nearby. Tend the base's farm when its crops are ripe or it needs planting (`farming`), and bake bread from wheat when short of food.
- **Progression:** wooden and stone tools, then a bed of its own at home (hunting sheep for the wool if need be; companions don't share beds, so everyone can sleep), then smelting carried raw iron, an iron pickaxe, an iron sword and iron armour piece by piece.
- **The base's needs:** requests from the board (see [Working together](#working-together)), taking turns with its own progression.
- **Whatever suits where it is:** chopping with an axe in a forest; mining coal, iron, then diamonds (with an iron pickaxe) in a nearby cave; turning spare coal into torches.
- **Otherwise:** it revisits a remembered cave or wanders.

It says what it's doing. Any other order ends free mode (back to following you), so its plans never compete with yours.

**The goal tree.** Each decision, everything that makes sense right now is offered under a root:
- **survive:** sleep, food, farm, bread, bed;
- **progress:** tools, the target ladder, smelting, caves, wood, torches;
- **faction:** storage, light, the request board, founding the base;
- **curiosity:** exploring;
- **rest:** heading home.

Each goal has a base priority (its place in the order above). The tree weighs them by how much this companion cares about each root:
- cautious ones about surviving;
- ambitious ones about progress, less when tired;
- loyal and helpful ones about the faction;
- curious ones about exploring, more when bored.

The goal it's already on gets a bonus (more for diligent companions), so it doesn't flip-flop. A goal that fails is put aside for 10 minutes, then 20, 40, up to an hour if it keeps failing, and is fine again as soon as it works. `/companion goals <name>` shows the tree: the scores, what was chosen and why, what's put aside and for how long. `-Dbotcraft.off=goalUtility` restores the plain fixed order.

Free mode ignores you completely. It never follows you, and never teleports to you. If it dies with no bed or home, it respawns at world spawn, not at your side.

The failsafe is free mode's only teleport, and it always goes home (or to world spawn without a home), never to you. It happens only when something has clearly gone wrong:
- stuck inside blocks for 3 seconds;
- digging its way out of rock by hand (no pickaxe) for 90 seconds;
- giving up two jobs within 10 minutes because of water (the next job would lead it straight back in);
- not moving more than a few blocks for 5 minutes (sleeping, sheltering, waiting on a friend or resting near home don't count);
- the watchdog giving up on two jobs within 10 minutes.

The soak report lists every rescue. In follow mode it only does light tool upkeep near you. `initiative` controls how readily it sets its own goals.

### Sleep

It goes to bed when you do (and on its own at night in free mode), so the night can be skipped. It uses its own bed if it has one, any free bed nearby otherwise, and puts down a bed it's carrying if there's none free. Awake companions never hold up the night skip (see [Limitations](#players-and-the-server)).

### Speech

- **Lines** come from `dialogue.json`, a large pool that avoids repeating itself.
- **Voices.** Each companion has its own voice (casual or proper, calm or excited, filler words, emoticons, a nickname for you), so the same line sounds different from different companions.
- **Unprompted remarks** on nightfall and morning, weather, new biomes, low health, creepers, you getting hurt or dying, diamonds, each step of a plan, plan failures (with the reason), kills, fleeing, missing beds, its own initiative, and idle musings. How often depends on chattiness.
- **In a faction** (teams and dynamic mode, `/companion chat factions leader`, the default), only whoever's in charge talks: the leader (or an officer while it's away) gives orders ("RainyTiger, see to 6 cooked food for the base."), checks in on them every couple of minutes ("how's it going?") until they're done, and speaks for the faction (wars, truces, bounties, land). The member given the order answers ("On it."), answers the check-ins with what it's doing, and reports back when it's done or gave up; the leader says thanks. Everyone else keeps quiet, except when a player talks to them. The factionless, single mode and the easter eggs' lines are as before. `/companion chat factions all` lets every member talk.
- **Customising.** Create `config/botcraft-dialogue.json` in the same format as [`dialogue.json`](../src/main/resources/botcraft/dialogue.json); any key you define replaces that key's default lines. Placeholders include `{player}`, `{nick}`, `{name}`, `{friend}`, `{mob}`, `{item}`, `{count}`, `{plan}`, `{result}`, `{reason}`, `{biome}`, `{time}` and `{fav}`. Restart to reload.

### Easter eggs

Some names (any capitalisation) make a companion someone else. `easterEggs: false` makes them ordinary companions.

Underneath, each is an ordinary companion with its own touches on top: it still eats, fights (crits, shields and all), flees, finds its way and does its jobs like any other, so everything companions learn, the easter eggs learn too. (For developers: `brain/Persona.java` says how to add to one.)

**popbob**, a patient menace:
- Belongs to no faction (`/companion faction join` refuses it) and ignores every order and question.
- **Gears up first,** like anyone would: tools, then an iron sword (or a stone one and some armour). Until then it gets on with it and leaves people alone.
- **Then, turn and turn about:**
  - **raids** the nearest faction base it hasn't robbed in 10 minutes (up to 320 blocks away), taking the most valuable things from its chests ("Thanks for the 12 things, Red!");
  - **bothers** someone within 48 blocks for half a minute or so: tags along, takes a swipe every few seconds, taunts them;
  - and in between **wrecks** build blocks in reach (planks, wool, glass, stairs, fences, doors, torches, cobblestone, bricks...). Never containers or block entities, dirt or stone, or anything unbreakable.
- Hit it and, once armed, it fights back like any companion would (whoever just hit it is its enemy); before that it backs off. Companions only gang up on it once it's armed: armed ones from 12 blocks, unarmed ones within 4. Its kills put a bounty on it like anyone's.
- It can only hurt players and companions when PvP is on (`pvp=true` in `server.properties`; singleplayer has it on).

**Herobrine**:
- Silent: never says a word, ignores chat. Wears no armour (takes it off). Belongs to no faction.
- Monsters leave it alone (none will target it) and it leaves them alone.
- Never fights people, and nobody counts it an enemy (not even a faction at war with the colony) unless it's attacked them, which it doesn't.
- Picks someone to stalk within 96 blocks, real players before companions. At night it stands at the edge of sight (24-40 blocks) and stares, moving to a new spot every half a minute or so; by day it keeps further off, circling round. It's gone (60-110 blocks away) the moment anyone comes within 10 blocks (7 for companions), a player looks straight at it for 3 seconds, or someone hits it.
- By day it makes signs (gathering wood if it has none) and leaves them about, blank, and now and then strips the leaves off a tree.
- Wears Herobrine's skin (the `MHF_Herobrine` account, else `Herobrine`), fetched when it joins if it isn't on.

**Technoblade**:
- An ordinary companion (it joins factions, takes orders), but fearless: never flees or retreats, goes for enemy companions and players on sight whatever the warfare setting (short of off), and hunts pigs first.
- Every time it respawns: "Technoblade never dies!"
- Wears the Technoblade account's skin, fetched when it joins if it isn't on.

**Hausemaster**:
- Plays for itself: free mode, no faction, no orders, never a word.
- Passive to everyone, and nobody counts it an enemy unless it's been attacked by them. The one fight it'll have: popbob, once popbob has taken a swing at it.
- Can't be hurt (falling out of the world puts it back on solid ground).
- Wears the Hausemaster account's skin, fetched when it joins if it isn't on.

Skins are looked up only with `allowSkinLookup` on, once per session. Remove any of them with `/companion remove <name>`.

---

## Limitations

### Players and the server

- **They count as players** (but don't use player slots):
  - They appear in the tab list, the player count and `usercache.json`, and they earn stats and advancements.
  - For skipping the night, only companions in bed count. Awake ones (standing guard, no bed) are left out, like spectators. So the night skips once you (and every other real player) are asleep, whatever the companions are doing. With no real players online, it skips once all the companions are in bed.
- **Leave/join messages.** Dying and changing skins both make a companion leave and rejoin, so chat shows a leave/join pair.
- **Chat messages are system messages** formatted as `<Name> text`, because a fake player can't sign chat.
- **Spawn protection** on dedicated servers stops non-op companions from breaking blocks near spawn.
- **`/kick`.** A kicked companion stays registered and comes back on restart or with `/companion spawn`.
- **Chunk loading.** Each companion keeps the chunks around itself loaded, like a player, so they keep working with nobody online on a dedicated server (soak runs check this). Many companions spread far apart load correspondingly more of the world. A singleplayer world pauses when you leave it, so unattended play needs a dedicated server.
- **Skins.** A registered-account skin needs network access once per name. Without it, the companion falls back to a default skin.

### Griefing safety (`griefing: false`)

Vanilla doesn't record who placed a block. Botcraft records every block placed since it was installed and judges older blocks by what they are (see "Natural or built" under The base), and the rules are conservative:
- wood only comes from natural trees;
- pathing only digs natural terrain and companions' own throwaway blocks, never a block someone placed;
- containers and block entities are never broken, whatever the setting.

Away from home, pillars, bridges and staircases built while travelling are left in the world. So are a crafting table it couldn't get back to, and a furnace when it had no pickaxe to break it with. At home they are tidied up.

### Navigation

- **Not supported:** boats, elytra, water buckets, ender pearls, and parkour beyond 2-block gaps.
- **Search size.** A search stops at `pathPrimaryNodes` nodes once it has a partial path, at `maxPathNodes` otherwise. Longer trips use partial paths, planned on while walking.
- **Very large height differences** are slow. Staircases and digging straight up get there, but climbing from deep caves to a mountain top can take minutes.
- **Digging out needs the right tools and blocks.** It needs a pickaxe for stone and at least one throwaway block. A companion with an empty inventory deep underground is stuck, just as a player would be, until the teleport-to-owner fallback (follow mode) or the failsafe (free mode, after 90 seconds of digging by hand) brings it out.
- **Sealed underwater caves.** If no air is reachable within about 32 blocks of swimming, a companion can still drown. Paths keep breathing room, but currents can push a companion off its route.
- **No portals.** Companions don't use Nether or End portals on their own. They follow you between dimensions by teleporting.

### Tasks

- **Crafting** covers crafting-table and furnace recipes only: no smithing, brewing, enchanting, stonecutting, blast furnaces or smokers.
- **Farming is basic.** Only the base's farm zone is farmed, by daylight, within 64 blocks of the base. There's no water placement (soil away from water grows slowly), no bonemeal, and no sugar cane, melons, pumpkins or nether wart. Seeds come from grass. Turn free-mode farming off with `farming: false`.
- **Storage is basic.** There's one base, chests aren't sorted beyond "like with like", and nothing is reserved: two companions can head for the same items, and the second gets whatever's left. Companions don't organise chests you fill yourself, and they never use chests away from the base.
- **Lighting is basic.** Torches go on the floor (never on walls), only around the base and where a companion is underground. They aren't picked back up, and nobody lights paths between places.
- **Hunting** never takes the last two animals of a species nearby (the last one when starving), so a lone cow is always left alone. There's no breeding yet, so a heavily hunted area runs out of animals. Animals beyond about 96 blocks, or more than 48 blocks up or down, aren't noticed until the companion gets closer. Fish, crops and other food sources aren't used.
- **Mining** reads ore positions from the world, like an "x-ray" of the 24-block scan area. A player would have to dig to find buried ore.
- **Tool upgrades** stop at iron.

### Combat

- **Melee and shields:** no bows or crossbows yet. Shields block skeleton arrows, player arrows and creeper blasts, but not melee hits (a companion doesn't try to read a player's swing).
- **PvP is simple:** the same fighting as against mobs, with crits and the shield. No strafing around a player's shield, no axe to disable shields, no potions.
- **No fighting when weak:** below 4 melee damage a companion flees instead of fighting, even from weak mobs.

### Free mode

- **Progression stops at diamond gear.** The target ladder ends there: no enchanting, Nether or End yet. There's no base building (beyond chests, beds and the farm), trading or exploring for structures.
- **Factions are new.**
  - Raids take what's in chests but don't plan an approach or hold formation.
  - A split doesn't divide the old base's chests (leavers keep what they carry).
  - Diplomacy is war and peace only (no alliances on their own).
  - Bases don't move once founded.
- **A simple planner.** It plans one target at a time for one companion; work for the whole colony goes through the request board instead. Its time estimates start from rough guesses and improve with experience.
- **Unarmed nights** mean digging in, not fighting.

### Understanding chat

- **Pattern-based.** Understanding uses patterns, not a language model, so unusual phrasing gets "Sorry, what was that?". Common phrasings, typos and shorthand are covered, and the dialogue file makes the wording easy to extend. Teaching a word ("when I say X I mean Y") covers your own phrasings.
- **Follow-ups** only refer to your last order, and only for 90 seconds; "it" and "there" aren't understood yet.
- **Hidden orders.** Orders hidden inside long sentences or questions ("would it be possible for you to maybe…") may be missed.

---

## Configuration

`config/botcraft.json` is created on first launch. Missing keys are filled in with defaults.

| Key | Default | Meaning |
|---|---|---|
| `maxCompanions` | 0 | Server-wide limit (0 = unlimited). |
| `aiTickInterval` | 5 | Drives are re-scored every N ticks (body control runs every tick). |
| `companionBudgetMs` | 0.5 | AI time each companion has of its own per server tick, for heavy work (path search, block scans); halved while the server is falling behind (a tick over 40 ms), quartered over 50 ms. |
| `companionBurstMs` | 5.0 | Unused time a companion saves up, for one long search, up to this. |
| `tickBudgetMs` | 2.0 | AI time per server tick shared by all companions, on top of their own: borrowed once a companion's own is spent. Whatever companions have saved, AI work stops for the tick once a whole tick would go over 35 ms. |
| `maxTickBudgetMs` | 8.0 | When the rest of the server leaves room (a whole tick under 35 ms), the AI budget grows up to this. |
| `pathNodesPerTick` / `pathPrimaryNodes` / `maxPathNodes` | 600 / 20000 / 80000 | A* nodes per companion per tick; where a search stops once it has a partial path worth taking; where it stops in any case. |
| `chatEnabled` | true | Replies and unprompted remarks. |
| `factionChat` | leader | `leader`: in a faction only the one in charge talks, and members answer its orders and players; `all`: everyone. `/companion chat factions leader\|all` sets it in game. |
| `chatVerbosity` | all | `all`, or `quiet`: only answers and acknowledgements, what a companion starts on its own and what failed. `/companion chat all\|quiet\|off` sets it in game. |
| `griefing` | false | When off, navigation only breaks natural terrain and companions' own throwaway blocks, and only natural trees are chopped. Containers and block entities are never broken either way. |
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
| `nightWork` | true | Free mode keeps working near home at night, sleeping only when that skips the night. |
| `startFree` | false | New companions start in free mode instead of following. `/companion freemode on\|off` sets it (and `on` frees everyone now). |
| `farming` | true | Free mode tends the base's farm zone. Asking a companion to farm works either way. |
| `clearTrees` | true | Upkeep fells the trees standing in the home zone. |
| `expandFarms` | true | Farmers add plots next to the farm as the base grows, and water each plot from a hole in its middle. |
| `ceasefires` | true | Dynamic mode: leaders of two factions locked in an even, bloody war may parley and agree a ceasefire. |
| `taming` | true | Free companions with bones (theirs or the base's) try to tame a wolf they see, when nothing more pressing is on. |
| `fenceHome` | true | Upkeep puts a fence round the home zone (gates on the paths) once the base has wood to spare. |
| `fishing` | true | Curious or hungry companions who know of water make a rod and fish. |
| `factionMode` | single | `single` (one colony), `teams` (fixed teams), or `dynamic` (factions split, fight and raid on their own). |
| `warfare` | defend | `off`, `defend`, `pvp` or `raid` (see [Factions](#factions-standing-and-warfare)). Breaking in and looting also need `griefing`. Dynamic factions always raid. |
| `ownerAuthority` | absolute | `absolute`: your companions always obey you. `standing`: you're judged by how you treat them, like anyone else. |
| `ownerStartStanding` | 90 | Standing a faction starts at for its own people (owners of its companions, members). |
| `playerStartStanding` | 0 | Standing a faction starts at for anyone else. |
| `orderStanding` / `requestStanding` / `hostileStanding` | 60 / 20 / -30 | Standing needed to give orders, to ask favours, and at or below which a player is an enemy. |
| `dynamicBases` | true | Factions without a base (teams and dynamic mode) find a spot and found one. |
| `maxFactions` | 6 | Dynamic mode: splits stop at this many factions. |
| `factionGraceMinutes` | 15 | New factions start neutral and can't fall out past cool, or go to war, for this long. |
| `recruit` | true | Teams and dynamic mode: companions in no faction are taken in by one. |
| `territory` | true | Factions hold land round their base and spread out; wars and raids only between factions whose borders touch. Off: everyone can fight anyone, as before 0.13. |
| `bounties` | true | Kills of a faction's members put a price on the killer's head. |
| `easterEggs` | true | Easter eggs. Currently: a companion named "popbob" (see [Easter eggs](#easter-eggs)). |
| `verboseAiLogging` | false | Log AI decisions at INFO instead of DEBUG, so they show in `latest.log`. |

---

## How it works

```
com.botcraft
├─ Botcraft           entrypoint: config, lifecycle/tick/chat/command/event registration
├─ LegacyNames        moves files saved under the old name (MCFriends) on first run
├─ CompanionManager     all companions: spawn/remove, restore on start, death -> rejoin, autosave
├─ Companion            one companion: data + player + subsystems; per-tick pipeline
├─ entity/      CompanionPlayer (ServerPlayerEntity subclass), FakeClientConnection (drops packets),
│               FakePlayers (vanilla join/leave, saved-data repair), SafeSpot, SkinUtil,
│               CompanionData/Store (JSON registry)
├─ action/      ActionController (look easing, aim jitter, reaction delay, movement input,
│               survival mining, use/place, attack, eat, drop/give), Inventories (tool/weapon/food choice)
├─ nav/         Terrain (block classes, tick costs, hazards), PathFinder (time-sliced A*, Baritone-style
│               moves), Navigator (execution per step kind, string pulling, off-path checks,
│               digging out, stuck recovery), BreakPolicy, Goal
├─ perception/  Perception (threats, owner, time, inventory summary), Surroundings (rolling 8-block
│               picture), BlockScanner, CaveFinder
├─ colony/      Colony (a faction's base, its chests and their purposes, stock, wanted levels; colony.json and
│               colonies/<id>.json), Kit (what a companion keeps), Purpose, StockTargets (what the base should keep),
│               BaseMap, Zones, Upkeep, Builds, SiteSurvey (choosing where to settle)
├─ faction/     Factions (modes, membership, standing, relations, scoreboard teams; factions.json), Faction, Scope
│               (which faction's colony and board the running code uses), Standing, Authority (who may ask what),
│               Hostility (who's an enemy), Incidents (what players did), Raids, Fracture (splitting up)
├─ brain/plan/  Planner (hierarchical "get N of X" with costs), Targets (progression ladder), MethodStats (learned costs)
├─ brain/goal/  GoalTree (free mode's goals under roots, weighted by personality, with backoff)
├─ brain/       Brain (utility selection + interrupt stack), Drives, Objective, Personality, Mood, Relationship(s),
│               JobQueue + Jobs (persisted job specs, prerequisites, optional prep), Life (free mode),
│               Board (request board and bidding), Sharing, Claims (spreading out), Leftovers (own drops),
│               Watchdog (stalled jobs), Failsafe (free-mode rescue), Lighting, Popbob (easter egg)
│  └─ task/     Follow, Stay, Idle, GoTo, GatherLogs, Mine, Craft, Smelt, Hunt, Place, Deliver,
│               Await, Sleep, Shelter, Eat, Fight, Flee/Retreat, Breathe, Collect, Deposit, Withdraw, Dance, ToolPrep, Farm,
│               Found, Raid,
│               SearchTrips, Pickup, Spots
├─ knowledge/   OreKind, ItemNames, CraftPlanner, Smelting, Animals
├─ memory/      Memory (places, sightings, stats, people; per-companion JSON)
├─ chat/        IntentParser (clauses, intents, typos, amounts), ChatRouter (intents -> jobs/replies), Followups
│               ("make it 30"), Lexicon (+ LexiconStore: taught words, lexicon.json),
│               SmallTalk, Templates (+ dialogue.json), Voice, Speech, EventWatcher, Social, CommandTasks
├─ command/     /companion
├─ ops/         Soak (unattended soak runs), SoakStats (counters for the report)
├─ mixin/       PlayerManagerMixin (no player slots used), SleepManagerMixin (awake companions don't block the night skip)
└─ gametest/    headless GameTests
```

**Fake player.** `CompanionPlayer` joins through the vanilla `PlayerManager#onPlayerConnect`. Its connection has no channel and drops outgoing packets. Because the network handler is never ticked, the player calls `playerTick()` itself, which runs real server-side physics, and updates its chunk position itself. `fall()` is routed through `handleFall` so fall damage applies, and movement exhaustion is applied from server-side motion. There are two mixins, both MixinExtras `@WrapOperation`s. One in `PlayerManager#checkCanJoin` makes the "server is full" check count real players only. One in `SleepManager#update` leaves awake companions out of the count for skipping the night.

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
- `<world>/botcraft/colony.json`: the base position, its chests and their purposes, and the stock levels you set. Other factions' bases are in `colonies/<id>.json`.
- `<world>/botcraft/factions.json`: factions, their player members, what each thinks of players and of each other.
- `<world>/botcraft/lexicon.json`: words and routines players taught.
- `<world>/botcraft/companions.json`: name, UUID, owner, skin (including fetched textures), personality (traits, archetype), faction, mode, defend flag, the current job and the queue.
- `<world>/botcraft/memory/<name>.json`: memory, including how it feels about each person.
- Vanilla playerdata: everything else.
