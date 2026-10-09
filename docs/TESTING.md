# Testing MCFriends

## Automated

| Command | What it covers |
|---|---|
| `./gradlew build` | Compiles. Runs JUnit tests for `IntentParser` (addressing, intent priority, counts and items), `Templates` (every key fills with no leftover placeholders) and `SkinUtil` (each default skin maps to a stable v3 UUID). |
| `./gradlew runGametest` | Headless server, 8 GameTests (below). Results go to `build/gametest/junit.xml`. Enable `verboseAiLogging` in `build/gametest/config/mcfriends.json` to see AI traces. |

| GameTest | Milestone | Passes when |
|---|---|---|
| `spawnFallAndRemove` | 1 | The companion is in the player list, falls onto the floor under server physics, and leaves the list on remove. |
| `astarThroughDoor` | 2 | A* finds a path that jumps a step and goes through a closed oak door. |
| `followThroughDoor` | 2 | The companion follows its owner over the step, opens the door and ends up within 4.5 blocks. |
| `gatherLogs` | 3 | The companion chops a small natural tree bare-handed and holds at least 3 logs. |
| `eatsWhenHungry` | 4 | At food 5 with bread, it eats to 14 or more. |
| `fightsZombie` | 4 | It switches to its iron sword and kills a helmeted zombie. Checked with its own kill stat. |
| `fleesCreeper` | 4 | It runs from a creeper to more than 7.5 blocks away. |
| `chatOrdersAndEvents` | 5 | "GtTalker, follow me!" is addressed, obeyed and acknowledged, and low health triggers an unprompted remark. |

## Manual checklist (Test profile, world "CompanionsTest", cheats ON)

Optional: set `"verboseAiLogging": true` in `config/mcfriends.json` and read `logs/latest.log`.

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

### Performance
- [ ] With 5 companions, `/debug start` … `/debug stop` shows no TPS drop. In the profiler, MCFriends work runs under the server tick and stays within `tickBudgetMs`.
