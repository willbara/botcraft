# Testing Botcraft

## Automated

| Command | What it covers |
|---|---|
| `./gradlew build` | Compiles. Runs JUnit tests for `IntentParser` (addressing, direct address, chained clauses, append, every intent, item phrases and counts), `Templates` (every key fills with no leftover placeholders) and `SkinUtil` (each default skin maps to a stable v3 UUID). |
| `./gradlew runGametest` | Headless server, 74 GameTests (the table below lists the early ones). Results go to `build/gametest/junit.xml`. Enable `verboseAiLogging` in `build/gametest/config/botcraft.json` to see AI traces. |

| GameTest | Milestone | Passes when |
|---|---|---|
| `spawnFallAndRemove` | 1 | The companion is in the player list, falls onto the floor under server physics, and leaves the list on remove. |
| `astarThroughDoor` | 2 | A* finds a path that jumps a step and goes through a closed oak door. |
| `followThroughDoor` | 2 | The companion follows its owner over the step, opens the door and ends up within 4.5 blocks. |
| `gatherLogs` | 3 | The companion chops a small natural tree bare-handed and holds at least 3 logs. |
| `eatsWhenHungry` | 4 | At food 5 with bread, it eats to 14 or more. |
| `fightsZombie` | 4/6 | A companion with bravery 0 switches to its iron sword and kills a helmeted zombie. Checked with its own kill stat. |
| `fleesCreeper` | 4 | It runs from a creeper to more than 7.5 blocks away. |
| `chatOrdersAndEvents` | 5 | "GtTalker, follow me!" is addressed, obeyed and acknowledged, and low health triggers an unprompted remark. |
| `armedFightsCreeper` | 6 | A companion with a stone sword kills a creeper (its AI is disabled, so it can't explode). |
| `wearsArmor` | 6 | Iron chestplate and gold helmet in the inventory get worn; the leather chestplate doesn't. |
| `craftsPickaxeFromLogs` | 7 | From 3 birch logs it makes planks, sticks and a crafting table, places the table, crafts a wooden pickaxe and picks the table back up. |
| `minesStoneAfterMakingPickaxe` | 7 | Told "mine 3 stone" with only logs, it crafts a wooden pickaxe first, then mines 3 cobblestone. |
| `tunnelsToCoal` | 7 | "mine 1 coal and then craft a torch" (one chat message) tunnels through natural stone to buried coal, then crafts a torch. |
| `escapesShaftByDigging` | 8 | Stuck at the bottom of a stone shaft, it digs a staircase out to its owner. |
| `escapesShaftByPillaring` | 8 | In a brick shaft it may not dig, it pillars up on dirt. |
| `climbsLadder` | 8 | It climbs a ladder to reach its owner on a wall. |
| `jumpsGap` | 8 | It sprint-jumps a 2-block gap between platforms. |
| `smeltsIronForPickaxe` | 9 | "craft an iron pickaxe" with raw iron: it crafts and places a furnace, smelts, crafts a table and the pickaxe. |
| `huntsAndCooks` | 9 | "kill 1 cow and then cook the beef" (four cows present): it hunts, places a furnace and cooks. |
| `freeModeArmsItself` | 11 | Told "do your own thing" with nothing, it chops a tree and crafts itself a sword. |
| `digsShelter` | 11 | It digs three blocks down and roofs itself in. |
| `orderEndsFreeMode` | 13 | Giving any order ends free mode and drops the companion's own jobs. |
| `friendSharesLogs` | 13 | Missing wood for a crafting table, a companion asks a friend, who brings logs. |
| `givesToFriendOnLedge` | 13 | "X, give Y your logs" reaches Y even though Y is on a ledge. |
| `companionsBanterAndShare` | 12 | Two companions banter, and the one told about an iron sighting remembers it. |
| `fellsWholeTallTree` | 7 | Asked for only 2 logs from a 9-log tree with an empty inventory, it digs dirt, pillars up, fells the whole trunk, digs the pillar down and collects at least 7 logs. |

## Manual checklist (Test profile, world "CompanionsTest", cheats ON)

Optional: set `"verboseAiLogging": true` in `config/botcraft.json` and read `logs/latest.log`.

### 1. Spawn / remove / persist
- [ ] `/companion spawn Bob kai` makes Bob appear 2 blocks in front of you, facing you. He has the Kai skin and a name tag, he's in the tab list, and chat shows "Bob joined the game" and a hello.
- [ ] `/companion spawn Ann alex_slim` and `/companion spawn Cy <yourname>`. Cy should wear your skin.
- [ ] `/companion list` shows all three, with owner, position, health and food.
- [ ] Give Bob items (drop them at his feet; he picks them up). Save and quit, reopen the world. Bob is back at the same spot with the same items.
- [ ] `/kill Bob` shows the death message. Bob leaves, and about 5 s later rejoins next to you with full health.
- [ ] `/companion remove Ann` removes her. `/companion list` no longer shows her.

### 2. Follow
- [ ] Walk away. Bob follows at about 3.5 blocks, sprints when far, and turns his head smoothly with no snapping.
- [ ] Stand still. Bob stops, glances at you now and then, and looks back when you look at him.
- [ ] Walk through a wooden door into a house. Bob pauses at the door, opens it, walks through and closes it behind him.
- [ ] Go up stairs, slabs and 1-block steps. Bob jumps or steps up. Drop down 2–3 blocks; Bob follows without taking damage.
- [ ] Run more than 48 blocks away, or go through a portal. Bob teleports to you.
- [ ] Go behind an iron door. Bob waits outside, facing you.

### 3. Gather logs
- [ ] Stand within 32 blocks of a forest and say `Bob, gather 10 logs`. Bob acknowledges, walks to a tree, swings at logs with the break animation, picks up the drops and moves to the next tree.
- [ ] With an axe in his inventory he switches to it and chops faster.
- [ ] Ask `Bob, what are you doing?` while he works. The reply is like "Chopping wood (4/10). Health 20/20, food 18/20."
- [ ] When done he says something like "Done! Got 10 logs." `Bob, give me 5 logs` (stand close) tosses them to you.
- [ ] He leaves log-cabin walls alone (griefing off: no natural leaves means it's not a tree).
- [ ] Quit mid-job and reload. The job resumes with the remaining count.

### 4. Survive
- [ ] Set his food low (`/effect give Bob hunger 30 50`) and give him food. He eats when food drops below 14.
- [ ] At night, give him a sword and let a zombie approach. A brave companion switches to the sword and fights (hits on cooldown, backs off between hits). A timid one flees toward you.
- [ ] Spawn a creeper near him (`/summon creeper ~ ~ ~3`). He says "Creeper!" and runs.
- [ ] Hurt him below 35% health with mobs around. He retreats toward you, then eats.
- [ ] `Bob, defend me` makes him attack mobs that target you.

### 5. Chat
- [ ] `Bob follow me`, `bob stop`, `Bob come here`, `everyone follow me`. Each gets an acknowledgement and the right behaviour.
- [ ] With only Bob near you, `follow me` (no name) works.
- [ ] A second player giving Bob orders gets "Sorry, I only take orders from …".
- [ ] Unprompted: nightfall remark, creeper warning, low-health remark, job-done remark.
- [ ] `chatEnabled: false` in the config means no messages at all. Orders still work.

### 6. Skins, addressing, combat
- [ ] `/companion spawn herobrine herobrine` gives a companion named herobrine wearing that account's skin. `/companion skin herobrine Notch` makes it rejoin wearing Notch's skin.
- [ ] `/companion spawn Zed nosuchname123xyz` spawns with a default skin and a grey note explaining why.
- [ ] With Bob and herobrine both spawned, "bob, go mine logs" makes only Bob answer. Plain "follow me" makes nobody answer.
- [ ] Give a timid companion a sword (check personality with `/companion info`). It fights zombies and creepers instead of running, and backs off while a creeper hisses.
- [ ] Take the sword away and it runs from mobs instead ("I've got no weapon, running!").
- [ ] Drop iron armour at its feet. It puts it on within a couple of seconds.

### 7. Orders, jobs, crafting, mining
- [ ] "Bob, go get logs and then mine for coal" gets "Okay: chop 16 logs, then mine 8 coal." It reports after each step, and `/companion info Bob` shows the queue.
- [ ] "Bob, also craft a stone pickaxe" is appended to the plan rather than replacing it.
- [ ] "Bob, craft a wooden pickaxe" with only logs: planks, sticks and a table are placed, used and picked back up.
- [ ] "Bob, mine 5 iron" with no pickaxe: it makes a wooden pickaxe, mines cobblestone, makes a stone pickaxe, then digs (natural stone only) to iron.
- [ ] "Bob, make an iron pickaxe" fails with a reason mentioning smelting.
- [ ] "Bob, bring me 10 logs": it chops, walks back and tosses them to you.
- [ ] Bob chops one tree **completely**, pillaring up on dirt for the high logs, then removes the pillar.
- [ ] Questions: "Bob, where are you?" (coordinates plus "N blocks east of you"), "what do you have?", "what have you found?", "how many logs have you chopped?", "what can you do?".
- [ ] "Bob, never mind" drops the plan.
- [ ] Quit mid-plan and reload. The remaining jobs continue.

### 8. Memory, home, sleep, initiative
- [ ] "Bob, this is home". Walk far away, then "Bob, go home".
- [ ] At night, get into bed with a spare bed nearby. Bob walks to it and sleeps, and the night skips. With no spare bed, he says he'll keep watch.
- [ ] Leave Bob idle near you in daytime with no tools. Within a few minutes he announces he'll make himself a sword or pickaxe, and does it.
- [ ] Log out and back in. Bob welcomes you back.
- [ ] `/companion info Bob` lists remembered places, sightings and stats. The file is `<world>/botcraft/memory/bob.json`.

### 9. Pathfinding v2
- [ ] "Bob, mine 10 stone" in a hillside, then "Bob, follow me" from the surface. He digs or pillars his way out instead of getting stuck.
- [ ] Walk up a ladder or across a 1–2 block gap. Bob follows (ladder climbing, parkour).
- [ ] Watch him walk across a field. He goes straight rather than zig-zagging.

### 10. Mechanics
- [ ] "Bob, craft an iron pickaxe" from nothing. He gets wood, makes tools, mines stone and iron, crafts and places a furnace, smelts, then crafts the pickaxe.
- [ ] "Bob, get us some food" with cows nearby. He hunts and cooks. Named, leashed or fenced animals are left alone.

### 11. Free mode
- [ ] "Bob, do your own thing". Over the next minutes he announces and pursues goals (sword, pickaxe, wood, stone tools, a bed, a farm). Check `/companion info Bob`.
- [ ] At night with no bed and no sword, he digs in. With a bed nearby, he sleeps.
- [ ] "Bob, follow me" brings him back to normal.

### 12. Speech and social
- [ ] Ask several companions the same thing ("tell me a joke", "what's your favourite food?", "do you like creepers?"). Answers differ between companions but stay consistent per companion, and lines don't repeat back to back.
- [ ] Leave two idle companions near each other for a few minutes. They greet and banter, and sometimes one tells the other where it found ore.
- [ ] Hurt a companion with a mob while another armed one is nearby. The second comes to help ("Hang on Bob, I'm coming!").
- [ ] Put a `config/botcraft-dialogue.json` with `{"greeting": ["Ahoy {player}!"]}` and restart. Companions greet with your line.

### 13. Targets, sharing, robustness
- [ ] "Bob, mine some iron" with no ore in sight. He looks for a cave first ("looking in a cave" in his status) instead of digging straight down.
- [ ] Two companions, one with logs, one without. Tell the second to craft a crafting table. It asks the first, who brings logs over.
- [ ] "alex go to steve", "bob follow alex", "steve, give alex your logs".
- [ ] Typos and shorthand: "bob folow me", "bob mien some coal", "bob where r u".
- [ ] "Bob, do your own thing", then any order. He stops his own plans and follows.

### 14. Spreading out, persistence, tools
- [ ] Three companions near one small wood: "everyone get 8 logs". Each takes a different tree where it can.
- [ ] Stand by an exposed ore vein, then tell a companion to mine that ore. It prefers another vein if there is one.
- [ ] "Bob, mine 3 diamonds" from the surface with an iron pickaxe. He heads for a deep cave (or staircases down), strip-mines at depth and keeps looking for well over the old 5 minutes.
- [ ] "Bob, hunt 3 cows" with no cows nearby. He says nothing's here, walks off in one direction, and keeps looking before giving up.
- [ ] Give Bob a wooden pickaxe, 3 cobblestone and 2 sticks, then "mine some coal". He makes a stone pickaxe first.
- [ ] Give Bob a stone pickaxe, 3 raw iron, coal and a furnace, then "mine some iron". He smelts the iron and makes an iron pickaxe first.
- [ ] Give Bob 3 diamonds and sticks, then "get 8 logs". He does **not** make a diamond axe.
- [ ] Put a companion on a tree top with a furnace to place. It climbs down to solid ground instead of trying to place it on leaves.
- [ ] A path down through deep water: the companion dives instead of bobbing at the surface.

### 15. Tidiness and getting back
- [ ] Watch a companion chop a tall tree. Afterwards, logs that fell away from the trunk are picked up (`collect: picked up ...` in the log).
- [ ] Order something that needs a crafting table. The table it puts down is picked back up, even if it drops into a hole.
- [ ] Drop an item next to a companion's work spot. It does **not** take it.
- [ ] Switch to spectator while companions follow you, far from home. When idle or after a failed job, they walk home ("I'll head back home").
- [ ] Send a companion mining deep, then "come home". It digs or pillars its way out instead of staying down there.
- [ ] "bob, make an iron pickaxe and then come home" goes home at the end, not to where you stood.
- [ ] Eight companions near one small wood: "everyone get 8 logs". None says "no trees nearby".

### 16. Awareness, water, respawning, phrasing
- [ ] Free mode in a cave system: companions finish each vein they start, including diagonal blocks, before moving on, and mine ore they pass within reach.
- [ ] A vein behind a waterfall next to a dry one: the dry one gets mined.
- [ ] Push a companion into deep water and hold it under. Below half air it stops and swims up to the nearest air.
- [ ] Let a companion die (in water if you like). It comes back at its bed or home, on dry ground, with no red "dying" animation.
- [ ] Phrasings: "bob what's your task", "bob what you doing", "bob I need some iron" (he brings it), "bob we're out of food" (he hunts and cooks), "bob wood", "bob iron pickaxe please", "bob grab 10 logs", "bob after you get logs make a crafting table".

### 17. Hunting and crafting fixes
- [ ] Clear a companion's inventory and say "bob, hunt 2 cows" near a herd. He makes a wooden sword first and fights with it.
- [ ] Put a herd of 4+ cows 50–90 blocks from a companion in open view. "hunt 2 cows" sends him straight toward them ("spotted animals over at …").
- [ ] Shrink the world border to 100–200 blocks. Search trips turn back at the border instead of pushing into it.
- [ ] A companion with 1 log and 2 planks, no table nearby: "craft a wooden pickaxe". He gathers enough wood once instead of going round in circles.
- [ ] Ore set into a cave wall at an angle is mined without "can't get at … in this vein".

### 18. Unattended running
- [ ] `./gradlew runSoak -PsoakMinutes=30 -PsoakCompanions=6` finishes on its own and writes `run/soak/soak-report.md`. Compare deaths per companion-hour, stalls and failed jobs between versions. The Navigation section shows how long companions waited on path searches, what got them stuck, and how often a path was re-planned because the world changed.
- [ ] `./gradlew runGametest -PbotcraftOff=lavaEscape` fails `lava_escape`. The `-Dbotcraft.off=name,...` switch (see `util/Experiments`) turns a feature off, to check that a test really covers it or to compare soaks with and without it.
- [ ] Let a companion die with items on it (not in lava or water). After respawning it says it's going back for its stuff and collects it.
- [ ] Watch for "watchdog: no progress on …" in the log. Each one is a bug worth a look.

### 19. Food
- [ ] Walk a companion past a herd of cows while it does something else, then later say "bob, get some food". He heads straight for the herd.
- [ ] Free mode with no food but full hunger: within a few minutes he says he'll stock up on food, hunts and cooks.
- [ ] Follow mode, hungry and with nothing to eat: he goes hunting nearby and comes back.
- [ ] Only two cows around: a fed companion leaves them; a starving one (3 drumsticks or less, no food) takes one.

### 20. Base and storage
- [ ] "everyone, this is home", then `/companion colony` shows the base. Put a chest down, look at it, and say "this is our chest". The colony view lists it.
- [ ] Give a companion junk (logs, spare tools, 64 cobblestone, 30 food), then "bob, put your stuff away". He keeps his best tools, 32 blocks and 16 food, and the rest goes into the chest.
- [ ] Remove all chests. "put your stuff away" with planks on him: he crafts a chest and puts it at the base.
- [ ] Put 3 iron ingots and 2 sticks in the base chest, then "bob, craft an iron pickaxe". He takes them from the chest (no mining). A chest of your own elsewhere with iron in it stays untouched.
- [ ] "bob, what's in the chests?" lists the stock (one answer when you ask everyone).
- [ ] Free mode for a day: companions come home to unload, and the stock grows. At night, armed ones at home smelt, cook and craft instead of standing around.

### 21. Chest purposes, planning, dancing
- [ ] Look at a chest: "everyone, this chest is for ores". Another: "food goes in this chest". `/companion colony` lists the purposes. Storing puts raw ores and ingots in the first, food in the second, everything else in a general chest.
- [ ] Free mode with stone tools: `/companion why bob` shows "iron tools: an iron pickaxe" with a plan (mine, smelt, craft) and the alternatives with costs.
- [ ] Put 3 iron ingots in a base chest: the next `/companion why` plan takes them from the chest instead of mining.
- [ ] After a companion fails or dies mining, `/companion why` lists it under "learned", and mining costs go up.
- [ ] "everyone dance": they dance for about 12 seconds, then carry on. Free mode keeps going afterwards.

### 22. Torches
- [ ] Give a companion torches and send it mining in a cave: torches appear along its way, every six or seven blocks.
- [ ] Set home somewhere dark at night (or in a roofed area). "everyone, light up the base": the dark ground around it gets torches. With coal and sticks in the chest and no torches on hand, it makes some first.
- [ ] Free mode with a dark base and torches: they light it without being asked.

### 23. Popbob
- [ ] `/companion spawn popbob` near you and another companion: it comes after both of you and taunts. The other companion fights it.
- [ ] Walk away: it breaks planks, glass, doors and torches nearby, but never chests or furnaces.
- [ ] "popbob, follow me": it refuses rudely and keeps doing what it was doing.
- [ ] Set `easterEggs: false` and restart: popbob is an ordinary companion.

### 24. Scatter
- [ ] "everyone run away": each says goodbye and walks off in a different direction, roughly 40-64 blocks, then gets on with free mode.

### 25. Beds and the night
- [ ] Free mode, give each companion 3 white wool (or a bed): at dusk they craft or put down a bed near home and sleep in it.
- [ ] Free mode in daytime with stone tools and no bed: they hunt sheep for wool and set a bed down at home, one each.
- [ ] Sleep in your own bed while some companions are still awake (guarding, no bed): the night is skipped anyway.

### 26. Base stock and the request board
- [ ] Set a base with a chest, put 3+ companions in free mode. `/companion colony`: stock targets show have/want, and requests appear for what's short (e.g. "#3 8 coal for the base [open]").
- [ ] Within a few minutes requests get taken: the winner says so ("I'll get 8 coal for the base, it's quickest for me"), `/companion colony` shows the top bids with their terms, and the batch ends up in the chest.
- [ ] Give one companion 40 coal: it should win the coal request ("I've got spare already") and drop 8 off without mining.
- [ ] "the base needs 20 torches" (one companion answers), `/companion colony want glass 8`: the targets update; "we have enough cobblestone" sets it to 0 and the cobblestone request disappears.
- [ ] Give a companion holding a request an order ("follow me"): the request goes back to open within a few seconds.
- [ ] `/companion why <name>` on a companion working a request shows the request and its bid.

### Performance
- [ ] With 5 companions, `/debug start` … `/debug stop` shows no TPS drop. In the profiler, Botcraft work runs under the server tick and stays within `tickBudgetMs`.
