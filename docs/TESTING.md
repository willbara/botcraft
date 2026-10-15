# Testing Botcraft

## Automated

| Command | What it covers |
|---|---|
| `./gradlew build` | Compiles. Runs JUnit tests for `IntentParser` (addressing, direct address, chained clauses, append, every intent, item phrases and counts, stack amounts, trust and farming phrases), `Templates` (every key fills with no leftover placeholders), `SkinUtil` (each default skin maps to a stable v3 UUID), `Followups` ("make it 30", "and coal too", "no, I meant gold", "same again", "you too"), `Lexicon` (teaching words and routines), `Personality` (seeded traits, archetypes, bounded drift), `Mood` (half-lives, strongest feeling), `Relationship` (grudges, decay, caps), `Standing` (two hits make an enemy, hysteresis, healing over days), `GoalTree` (personality weights, commitment, doubling backoff, strict order) and `Thompson` (Beta draws). |
| `./gradlew runGametest` | Headless server, 103 GameTests (the table below lists the early ones, and the pathfinding symptom tests). Results go to `build/gametest/junit.xml`. Enable `verboseAiLogging` in `build/gametest/config/botcraft.json` to see AI traces. |
| `tools/navbench.sh <name> [workers] [minutes]` | The navigation bench: one companion per server walks its share of 100 fixed start/goal pairs (`tools/navbench-pairs.json`, from seed 12345: 50 across the surface, 25 from the surface down into a cave, 15 from a cave up, 10 cave to cave), carrying a netherite pickaxe with Efficiency V (like the manual Baritone runs it was compared with), 32 cobblestone and bread. Each trip records whether it arrived, ticks, blocks walked against the straight line, searches, nodes, ticks spent waiting on searches, stuck events, jumps, AI ms per server tick and server tick ms. `tools/navbench-compare.py <name> <name>...` puts runs side by side, with times and path lengths over the trips every run completed. The pair list can grow: add a pair where a companion was seen to fail. `tools/navbench-generate.sh` remakes it. `CLASSES_FROM=<dir>` runs an older build's classes. |
| `tools/bench.sh <name> [trials] [goal] [minutes] [seed]` | The bench: one companion with an empty inventory on a fixed-seed world (12345), told to get a diamond pickaxe (or `iron_pickaxe`, `stone_pickaxe`), from a different fixed start point per trial; trials run side by side. Each milestone (first log, crafting table, wooden pickaxe, first stone, stone pickaxe, coal, furnace, raw iron, iron ingot, iron pickaxe, below y 0, diamond, diamond pickaxe) is timed, with what went wrong in between: stuck events, failed searches, search nodes, failed jobs, deaths. Path search is limited by node counts, not the clock, so the machine's load doesn't change a run. Reports in `run/bench/<name>/t*/`; `tools/bench-watch.sh <name>` follows a run live (milestones, re-orders, exceptions per trial); `tools/bench-compare.py <name> <name>...` puts runs side by side (trials reaching each milestone, median times). The standard check for whether a change made companions better: the times should only go down. Needs one `./gradlew runSoak` first for the launch files. |

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
| `fellsWholeTallTree` | 7 | Asked for only 2 logs from a 9-log tree with an empty inventory, it digs dirt, towers up (the path finder plans the climb), fells the whole trunk and collects at least 7 logs. |
| `noHoppingUnderTheCanopy` | nav | Following its owner through trunks under a low canopy, it arrives without jumping once. |
| `digsOnlyAlongItsPath` | nav | Mining coal set in solid stone above head height, it only breaks blocks next to where it stands. |
| `minesTheNearerVein` | nav | Coal behind one block of stone and exposed coal 20 blocks down a corridor: it mines the near coal first. |
| `searchResultDoesNotDependOnTickSlicing` | nav | The same search at 600 and at 25 node expansions per tick ends the same way, with the same path. |
| `fellsTreeAmongFlowers` | trees | Towering up beside a tree in a field of poppies, it picks the flower it stands in first (a flower can't be placed over), then fells the tree. |
| `companionsSpreadOutOverTrees` | trees | With one companion at a tree, a second one leaves the tree 3 blocks away alone and takes one about 30 blocks off. |
| `noEndlessJumpingOnThinGround` | trees | In snow three layers deep, a tree on a 2-high stump: it clears the snow it stands in, towers up and fells the tree, in under 50 jumps (the old code jumped on the spot 80 times). |
| `toolPrepForAHuntAtTheBase` | base | Getting ready for a hunt at the base doesn't throw (it threw "duplicate element: sword" and stopped the companion). |

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
- [ ] Bob chops one tree **completely**, towering up on dirt for the high logs (the dirt tower stays; at home the upkeep clears it).
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
- [ ] "Bob, follow me" through a dense oak forest. He stays on the ground, breaking leaves at head height as he walks, cuts diagonally between trunks, and doesn't climb onto the canopy, pillar or dig up. While you keep walking he keeps to one way round each tree.

### 9b. Home and zones
- [ ] `/companion colony` lists a home zone around the other zones, an "Upkeep" line and the number of built blocks on record.
- [ ] Put a dirt pillar and a few blocks of dirt litter at home with a companion (or let one pillar up), dig a hole in the core yourself, and wait: a free companion takes "tidy up the base" from the board and takes the pillar down, but leaves your hole alone.
- [ ] Build a small log cabin next to a tree near home. Free mode chops the tree but leaves the cabin's logs.
- [ ] Place some dirt by hand at home: nobody digs it for pillars.
- [ ] `/companion colony zone home here 51` makes home bigger; planning doesn't change it back.

### 10. Mechanics
- [ ] Right-click Bob. A chest screen titled "Bob's inventory" shows his items (hotbar in row 4, armour and off hand in row 5). Take an item, give him one, shift-click a helmet onto him; dirt won't go into an armour slot. Walk more than 8 blocks away and the screen closes.
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

### 27. Personality, mood, goals (0.12)
- [ ] `/companion info Bob` shows twelve traits, a mood ("calm" at first), the faction, and (after some chat) feelings about people.
- [ ] `/companion archetype Bob hothead` then `/companion info Bob`: aggression 0.95, bravery 0.85, caution 0.1. `/companion trait Bob humor 1` sets one trait.
- [ ] Hit Bob (as a non-owner, or with `ownerAuthority: standing`): `/companion info Bob` shows fear and anger, and a lowered affinity for you.
- [ ] "Bob, do your own thing", wait a minute, `/companion goals Bob`: roots with scored goals, one marked `>`, others "not needed now". Make a goal fail (e.g. take away its axe mid-job): it shows "put aside N min" and "[failed 1x]".
- [ ] "Bob, how do you feel?" reflects its mood after a fight or a failure.

### 28. Chat follow-ups and teaching
- [ ] "Bob, get 10 logs", then "make it 20" (no name): Bob re-plans for 20. "and coal too" adds mining coal. "no, I meant birch" swaps the wood. "Ann, you too": Ann gets the same order.
- [ ] "Bob, get half a stack of cobblestone" asks for 32; "two stacks" 128.
- [ ] "Bob, when I say lumber I mean logs", then "Bob, get me 5 lumber" chops 5 logs. "Bob, morning routine means get food then light the base", then "Bob, morning routine" queues both. Restart: still known. "Bob, forget lumber" removes it.
- [ ] A player who isn't the owner can't teach words (Bob says it doesn't take orders from them).

### 29. Standing and authority
- [ ] With a second account (not the owner): "Bob, follow me" is refused; "Bob, what time is it?" is answered; "Bob, where are you?" is refused as private.
- [ ] `/companion standing colony <player> 30` (friendly): "Bob, give me a log" works, "Bob, follow me" is refused. At 70 (trusted): orders work.
- [ ] As that player, hit Bob once: a warning ("Hey! Watch it.") if they were friendly, else "<player> just attacked me!". Hit again: standing hostile; armed companions nearby fight them (defend mode: while they're at the base or right after the attack). "sorry" raises the standing; a second "sorry" within 5 minutes gets "Words are cheap".
- [ ] As that player, open a base chest: "Hey, that's our chest!" and -15 standing (once a minute). Right-click a companion while hostile: it won't open its inventory. Give a companion something through its inventory: thanks, and standing goes up.
- [ ] As a non-owner, kill a zombie that's attacking a companion: "Thanks for the save" and +5.
- [ ] Set `ownerAuthority: standing`, restart, hit your own companion three times: it stops taking your orders. Back to `absolute`: it obeys again.

### 30. Teams (red vs blue)
- [ ] Spawn 6 companions (`/companion batch 6`), set them free, then `/companion faction teams red blue`: chat lists the two teams; names show red and blue in the tab list and chat; teammates can't hurt each other.
- [ ] With no bases set, within a minute or two (daytime) one member of each team says it's founding a base and walks to a flat spot at least 160 blocks from the other team's base (walking on to look further if nothing in sight will do); `/companion faction info red` shows the base; `/companion colony` (default colony) is unaffected.
- [ ] Each team gets its own zones (farm, beds...), its own board and stock; companions only share with teammates.
- [ ] "red team, follow me" makes only red companions follow (you own them all).
- [ ] With `warfare: defend`, a red companion walking into blue's base gets attacked; with `/companion faction warfare pvp`, they fight on sight.
- [ ] `/companion faction ceasefire` stops the fighting; `/companion faction relation red blue allied` makes them share requests.

### 31. Raids and dynamic factions
- [ ] Teams with bases, armed companions: `/companion faction raid red` sends up to four red fighters ("Raid on Blue! Follow me!"). They walk to blue's base and fight. With `griefing: false` it's a skirmish and nothing is taken; with `griefing: true` they break through blue's walls (not doors, beds or gates), take diamonds, iron, gear and food from blue's chests, then go home and deposit.
- [ ] Raiders below 40% health pull out ("Falling back!").
- [ ] `/companion faction mode dynamic`: warfare becomes raid for every faction; raids start on their own (at most every 15 minutes per faction) when one side clearly outmatches the other.
- [ ] Dynamic mode at war: when one side has lost 6+ members in 20 minutes (twice what it took), it calls a truce ("Truce with Blue!"), and the fighting stops. Twenty minutes or more later, a bigger, aggressive faction may declare war again. `latest.log`: "sued ... for peace", "declared war on".
- [ ] A respawned companion doesn't go looking for enemies for a minute (it still hits back), and doesn't walk back to where an enemy killed it.
- [ ] `/companion faction member <faction> <companion> leader`: the companion joins that faction (its name takes the colour), says it's in charge, and founds the base if the faction has none (once in free mode).
- [ ] Dynamic mode with 8+ companions over a long run: unhappy companions grumble ("Nobody around here listens to me."); eventually a group splits off as "<Name>'s Band", hostile to the old faction, and founds its own base. Empty factions disband. Check `logs/latest.log` for `faction` lines.

### 32. Farming and combat
- [ ] At a base with a farm zone (`/companion colony` lists it), give Bob wheat seeds and a hoe: "Bob, farm" tills the farm, plants, and says what it did. Without seeds, it pulls up grass for some. Without a hoe (but with seeds and grass to till), it crafts a wooden hoe first.
- [ ] Grow wheat to ripeness (bonemeal): "Bob, harvest the wheat" harvests and replants. In free mode, with 6+ ripe crops, one companion goes farming at a time (not the whole base) and then leaves the farm alone for 3 minutes. It bakes bread with 3 wheat when short of food.
- [ ] Flowers in the farm zone get pulled up and the ground tilled.
- [ ] With Xaero's minimap or world map installed, companions saved in the world rejoin when it loads (they used to be dropped at once and stayed offline).
- [ ] Give Bob a shield: it moves to the off hand within 2 seconds. Against a skeleton, Bob raises it while the skeleton draws (arrows are blocked) and walks in. Against a hissing creeper, it backs off behind the shield.
- [ ] Fighting zombies, Bob often jumps and hits on the way down (critical hit particles). A hothead does it more often.

### 33. Upkeep, farms, land, bounties and fishing (0.13)
- [ ] **Upkeep.** Put a 3-high dirt or cobblestone pillar and a log stump (a log with no leaves) on flat ground just outside the home zone, and dig a one-block pit next to them. Within a minute `/companion colony` counts them, "tidy up the base" goes on the board, and a free companion takes the pillar and stump down and fills the pit. A pillar you put a torch or chest on top of is left alone, and so is anything you placed with a block you'd normally build with (planks, bricks...).
- [ ] Old companion dirt that has grown grass (a bridge or pillar from an earlier session) is taken down too.
- [ ] **Fence.** With 24+ logs in the base chests, upkeep adds fence posts round the home zone: the companion crafts fences (and gates) from the base's wood, puts them up along home's edge, and leaves gates where the paths to the farm and pen cross it and in the middle of each side. Companions walk through the gates. `fenceHome: false` stops it.
- [ ] **Lighting.** A dark corner of a big home zone (20+ blocks from the base, up or down a slope) gets a torch.
- [ ] **Farm water.** On a farm plot with no water within 4 blocks, give the farmer a bucket (or 3 iron in the chests): it fills the bucket at nearby water, digs out the plot's middle, pours it in, and the soil goes dark (wet). The middle is never tilled or planted, and upkeep doesn't fill it. `latest.log`: "farm: watered the plot".
- [ ] **More plots.** With 4+ members and the first plot three-quarters planted, a few minutes later `/companion colony` lists `farm2`, a block away from the first plot on the side with the most flat soil; it gets its own water and planting. Plots stop at one plus one per four members (four at most). `expandFarms: false` stops it.
- [ ] **Land.** Teams or dynamic mode with bases: `/companion faction info red` shows "land: 25 chunk(s)" at first and grows every minute and a half. When two factions' land first touches, `latest.log` shows "meet at the border"; already-enemies say so in chat; in dynamic mode neutral ones either declare war or agree a truce ("Truce with Blue. Stay on your side."). Factions whose land doesn't touch don't raid each other or declare war.
- [ ] **Occupation.** At war, stand enemy companions out of a border chunk and your faction's companions in it: within a couple of minutes the chunk changes hands ("took a chunk of ... land" in the log, "That land's ours now"). The 5x5 chunks round a base never change hands.
- [ ] **Peace terms.** When a losing faction sues for peace, the log says how many chunks it gave up (up to 3).
- [ ] **Splits** (dynamic, territory on): a new band starts on cool terms (not at war), settles 160+ blocks away, and the fight (or truce) starts when its land reaches the old faction's.
- [ ] **Bounties.** Kill three of a faction's companions (or let one enemy companion do it): the faction's spokesperson puts a price on the killer's head, and `/companion faction info <faction>` lists "WANTED". The faction's companions go for the wanted one on sight (from up to 32 blocks), before other enemies, as long as the two sides are enemies. When one of them kills the wanted one, it brags ("Got Red10! The bounty's mine.") and the bounty is gone. Without more kills, a bounty halves every 30 minutes and drops off the list.
- [ ] **Fishing.** Walk a curious companion (scout, `/companion archetype Bob scout`) past a lake by day with a fishing rod (or 2 string and some sticks) on it: it remembers the water, and later goes fishing there: it walks to the shore, casts, reels in when the bobber dips, and comes back with cod or salmon. "Bob, go fishing" / "catch 5 fish" does it on request. Without a rod it crafts one first. `fishing: false` stops the free-mode fishing.

### 34. Leadership, hunting, lighting, clearing and budget (0.14)
- [ ] **Leaders.** `/companion faction teams red blue` (no ranks given): within seconds each team announces a leader ("Looks like I'm leading Red now"), and the leader names one officer per four members ("X, I want you as my officer."). `/companion faction info red` shows "(leader)" and "(officer)" next to companion names.
- [ ] A rank given with `/companion faction member` stays put across reviews (every two minutes). Remove the leader companion (`/companion remove <name>`): the best officer takes over within two minutes.
- [ ] **Hunting.** With herds around, "Bob, hunt 3 sheep": it finds them (look within 40 blocks, herds up to 96), and a zombie interrupting the trip doesn't make it give up or forget the herd (`latest.log`: no new "trip N/8" right after "resume task hunt"). "Couldn't find any animals" should now be rare where there are herds.
- [ ] **Lighting.** On a fresh base with no coal, someone mines coal (or burns charcoal) and lights the base within the first in-game day, not at the end of it.
- [ ] **Woodcutting stall.** A tall tree at home with no dirt to climb: the companion leaves the top logs ("nothing to climb with") within a few seconds instead of standing still for a minute.
- [ ] **Clearing trees.** Found a base in the forest: "tidy up the base" includes the trees in the home zone; whoever takes it fells them all ("clearing the trees at home"), and the farm and pen zones get laid out once the ground is open (`/companion colony`).
- [ ] **Site choice.** With the clearing and forest around it, a founder near the clearing settles in it (log: "site survey: best ... open 9x%").
- [ ] **Getting gear back after PvP.** A companion killed by an enemy says nothing about its things at once; once the killer has left (40+ blocks from the spot), it goes back for them (log: "waiting for whoever killed me to leave", then "going back for my things").
- [ ] **Enemy land.** Hunters and woodcutters don't search, hunt or chop on enemy land (fewer deaths on search trips than in 0.13).
- [ ] **Colony command.** Standing on Red's land, `/companion colony` shows Red's base ("Red: base at ..."); `/companion colony of blue` shows Blue's.
- [ ] **Owner violence.** As the owner (default `ownerAuthority: absolute`), hit a companion: it complains ("what was that for?"); kill one in front of others: they say so. `/companion standing <faction> <you>` has dropped (a kill is -60), but they still obey and never attack you, and no bounty is posted. With `/companion faction authority standing` (or `ownerAuthority: standing`) the same spree turns them hostile, they fight back, and a bounty goes up.
- [ ] **Reaction speed.** Walk a zombie up to a group, or hit one companion: it and nearby faction-mates turn and fight (or back off) within a fraction of a second, not a second or two later.
- [ ] **Budget.** With 20 companions, `/debug start`, wait, `/debug stop`: the server keeps up; nobody stands frozen waiting for a path while one companion runs a long search.

### 35. Grace, getting on, leaders' orders, free mode, recruiting (0.14.1)
- [ ] `/companion batch 10`, `/companion freemode on`: everyone sets off on their own at once; new spawns start free too. `/companion freemode off`: new ones follow again.
- [ ] `/companion faction teams red blue`: chat says the teams start as strangers; `faction info` shows them at 0, not enemies; no fighting in the first 15 minutes even when they bump into each other.
- [ ] After the grace period, a hothead near the other team quarrels now and then ("Watch where you're going, X!"); `faction info` shows the relation dropping by 8 each time, and after four or so they're enemies and fight. Calm, sociable teams instead drift friendlier.
- [ ] With a base and open board requests, the leader orders idle members ("Bob, see to 16 logs for the base.") and they go straight to it. A poorly stocked faction hears a rallying call; a well stocked one, praise.
- [ ] In dynamic mode, spawn one more companion with no faction: within half a minute a faction takes it in ("Welcome to Red, X."). In teams mode it goes to the smaller team.

### 36. Night work and progression (0.14.2)
- [ ] Free mode, armed companions with a base, you awake (or in creative): at dusk they come home and keep busy (chests, torches, cooking, tidy/fence/clear jobs, the farm, "mining near home"), not standing about until morning. Go to bed yourself: they go to bed too and the night skips.
- [ ] Unarmed at home at night with planks in the chests: they make a wooden sword first.
- [ ] Over a long run, companions keep climbing the gear ladder (`/companion why <name>` shows a "next:" target most of the time), and the base's logs, coal and iron get topped up when nothing else is pressing.

### 37. Easter eggs (0.14.3)
- [ ] `/companion spawn popbob`: no faction (`faction join` refuses). It gears up quietly first (watch `/companion why popbob`), then raids a faction base ("Thanks for the N things..."; the base's chests lose their best items), follows and pesters someone, and wrecks build blocks. Hit it once geared: it fights back. Companions only fight it once it's armed.
- [ ] `/companion spawn Herobrine`: Herobrine's skin appears (with network); it says nothing and ignores chat; takes off any armour; at night stands 24-40 blocks from you staring; walk towards it and it vanishes. By day, blank signs turn up where it's been, and leaves go missing from trees.
- [ ] `/companion spawn Technoblade`: Technoblade's skin; never runs from a fight (even at low health); goes for pigs first when hunting; kill it and it comes back saying "Technoblade never dies!".
- [ ] `/companion spawn Hausemaster`: Hausemaster's skin; silent, ignores orders; `/kill Hausemaster` does nothing; attacks nobody (even with factions at war); when popbob hits it, it fights popbob back.
- [ ] Herobrine at night among zombies and skeletons: none of them go for it, and it doesn't fight them.
- [ ] Herobrine with factions at war around it: nobody attacks it. Walk up to it, or look straight at it for 3 seconds, or hit it: it's gone. At night it shifts to a new watching spot every half minute or so, not standing in one place.

### 38. No faction means no faction (0.14.6)
- [ ] Teams or dynamic mode with some companions left in the colony (`recruit: false`): `/companion faction` lists them as "no faction"; no faction declares war on them, raids them, borders them or posts a bounty; they don't fight faction members unless hit; and they don't fight each other as a group.

### Performance
- [ ] With 5 companions, `/debug start` … `/debug stop` shows no TPS drop. In the profiler, Botcraft work runs under the server tick and stays within `tickBudgetMs`.
