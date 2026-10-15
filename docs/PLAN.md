# Botcraft feature plan

The plan for Botcraft's next major line of work, written against v0.11.2. Each section is one feature: **what it is**, **how to implement it**, **what it touches**, and **when it's done**. The status table below says how far 0.12.0 got.

## Ground rules

- **No LLMs.** Everything is algorithmic. Small, explainable machine learning is fine.
- **Server-side only.** Fabric API stays the only dependency.
- Keep within `tickBudgetMs`.
- **Old saves keep working.** A missing field means today's behaviour.
- **Every feature ships behind an `Experiments` flag.** It gets GameTests and an A/B soak (`tools/soak-ab.sh`) before becoming the default.
- **Typed objects above the job queue, job-spec strings below it.** Every existing Task keeps working unchanged.

## Decisions recorded

| Topic | Decision |
|---|---|
| Language | English only, understood much more broadly, with richer replies |
| Factions | One big colony, split groups, emergent fracture, or fixed red vs blue |
| Players | Players have a **standing with each faction**, which bots remember. Players other than the operator who spawned a bot need **good standing** to give it orders. |
| Owner authority | The spawning operator's orders always win by default. A **toggle** switches the owner to standing-based interaction like everyone else. |
| Faction bases | Bots choose, found, grow and move bases themselves, building on the existing zoning (`Zones`, `BaseMap`). Hand-set zones are never overridden. |
| Warfare | Default is `defend`. **Dynamic factions get `pvp` and `raid` automatically.** Raids break blocks or loot only between enemy factions with `griefing: true`. |
| ML | Allowed if it's small, explainable and pure Java |

---

## Status in 0.12.0

| § | Feature | Status |
|---|---|---|
| 1 | Scoped world state | **Done** in an ambient form: `faction/Scope`. The manager enters each companion's faction around its tick, and `Colony.get()`, `BaseMap`, `Upkeep` and the `Board` resolve per faction. Explicit context objects are still to do. |
| 2 | Goal tree | **First version.** `brain/goal/GoalTree` has roots, personality and mood weights, commitment, doubling backoff, and `/companion goals`. Free mode's rules offer goals into it. Still to do: data-driven tech tree in JSON, lazy sub-goal expansion, and the Nether and End. |
| 3 | Rules, constraints, triggers | Not yet. Taught routines (§14) cover simple macros. |
| 4 | Personality and values | **Traits, archetypes, drift: done.** Values (order/freedom...) not yet. |
| 5 | Mood | **Done.** |
| 6 | Relationships | **Done:** affinity, trust, debt, notes. They update from hits, kills, gifts, praise, insults, apologies, banter and help. |
| 7 | Player standing and authority | **Done.** Witnesses react at once; faction-wide gossip is the victim's chat call-out plus the shared standing. |
| 8 | Factions core | **Done.** |
| 9 | Modes single, teams, dynamic | **Done.** |
| 10 | Dynamic bases | **Founding: done** (`SiteSurvey`, `FoundTask`). Growing zones, outposts and relocation: not yet. |
| 11 | Fracture, merging, leadership | **Fracture and disbanding: done** (simple unhappiness). Merging, elections and dividing the chests: not yet. |
| 12 | Diplomacy | **War weariness and declarations of war: done** (dynamic mode), plus commands. Alliances, gifts and envoys: not yet. |
| 13 | Warfare | **off, defend, pvp and raid: done.** Raids go, fight, break in with griefing on, loot and return. Raid formations and a block cap: not yet. |
| 14 | English understanding | **Partly:** stack amounts, follow-ups, teaching words and routines, trust questions. The tagger, chunker and frames pipeline: not yet. |
| 15 | Conversation and context | **Partly:** last-order follow-ups. Coreference ("it", "there") and pending questions: not yet. |
| 16 | Responses | **Partly:** mood variants, refusals with reasons. Content planner and realiser: not yet. |
| 17 | Message bus | Not yet. |
| 18 | Roles | Not yet. Archetypes bias the same weights. |
| 19 | Jobs | **Farmer** (`FarmTask`) and **founding** done. Others not yet. |
| 20 | Combat | **Crits and shield: done.** Bows, buckets, potions and more: not yet. |
| 21 | Squads | Not yet. Raids send up to four together. |
| 22 | Machine learning | **Thompson sampling: done.** The rest not yet. |
| 23 | Configuration | **Done** for the above. |
| 24 | Observability | **Partly:** `/companion goals`, `faction info`, `standing`, `info`. Soak report sections: not yet. |

---

## 1. Scoped world state (groundwork)

**What.** Remove the global singletons so that more than one faction, base and request board can exist.

**How to implement.**
- Introduce a `World → Faction → Companion` context. Replace:
  - `Colony.get()` with `faction.home()` (a `Colony` instance per base).
  - the static `Board.REQUESTS`, `DONE`, `FAILED` and `PLANS` with a `Board` instance per faction.
  - the static maps in `Sharing` with fields on the faction, and helper-busy state with fields on the companion.
  - the static `CLAIMS` in `Claims` with an instance per faction. Cross-faction crowding still applies, as a lower-weight "someone's there" check.
  - `Life.darkCheckedAt` and `darkSpotsAtBase` with fields on `Colony`.
  - `Builds.LEDGER` with a per-world ledger. It stays global, since built-vs-natural is a world fact, but each entry is tagged with the **faction that built it** (needed for raids).
- Add `Faction.DEFAULT`, which holds every companion. With one faction, every call resolves exactly as it does today.
- Add a small `Ctx` record passed into the planner, the board and the goals: companion, faction, colony, world and time.

**Touches.** `colony/Colony`, `brain/Board`, `brain/Sharing`, `brain/Claims`, `brain/Life`, `colony/Builds`, `CompanionManager`, `command/CompanionCommand`.

**Done when.** All 74 GameTests pass, and a 30-minute soak matches v0.11.2 within noise (`soak-compare.py`).

---

## 2. Expanding goal tree

**What.** This replaces the fixed priority cascade in `Life.chooseFree` and the flat `Targets.LADDER` with a persisted tree of goals. Goals expand into sub-goals only when they're needed, and run all the way to the End.

**How to implement.**

*Engine (`brain/goal/`)*
```java
interface GoalDef {
  String id();                         // "progress.diamond_pickaxe"
  Kind kind();                         // ACHIEVE | MAINTAIN | PERFORM | ACQUIRE | BUILD | SEQUENCE | ANY
  boolean satisfied(Ctx c);
  float urgency(Ctx c);                // 0..1
  List<GoalRef> expand(Ctx c);         // children; empty for leaves
  @Nullable List<String> compile(Ctx c);// leaf → job specs (usually via Planner.acquire)
}
final class GoalNode { String def; Map<String,String> args; State st; int attempts;
                       long backoffUntil; String reason; List<GoalNode> children; Source source; }
```

*Goal types*
| Kind | Behaviour |
|---|---|
| `ACHIEVE` | Done once its condition holds. Re-opens only on a real loss (pickaxe broke, died and lost gear). |
| `MAINTAIN` | A **band**: it starts when the value drops below *low* and stops at *high*. Food might start below 8 and stop at 24. |
| `PERFORM` | A one-off activity (patrol, explore) with a cooldown afterwards. |
| `ACQUIRE(item,n)` | A generic leaf. `compile` calls the existing `Planner.acquire`. |
| `BUILD(blueprint,zone)` | ACQUIRE for the materials, then a build job (§19). |
| `SEQUENCE` / `ANY` | Children in order, or the first alternative that works. Food is `ANY`: farm, else breed, else hunt. |

*Roots*
| Root | Holds |
|---|---|
| `Obey` | Orders from people allowed to give them (§7) |
| `Survive` | food, health, gear durability, bed, light at home |
| `Progress` | the tech tree |
| `Faction` | Board requests this bot won |
| `Social` | owed favours, helping friends |
| `Curiosity` | exploring, scouting |

*Selection.* Every 10 s (`FREE_DECISION_GAP`), do a best-first descent:
```
utility = basePriority × urgency × traitWeight × roleWeight × sourceWeight
        + commitment(0.10 if on active branch) − failurePenalty − riskPenalty
```
- **Switching:** the current branch is only replaced when another one beats it by the commitment margin, the same idea as `Brain.HYSTERESIS`.
- **Lazy expansion:** children are created the first time a node is chosen.
- **Failure:**
  - Each failure adds an attempt and an exponential backoff.
  - Under `ANY`, a failure jumps straight to the next alternative.
  - Three failures mark the node `BLOCKED(reason)`. It is re-checked only on a relevant change: new gear, a new sighting, or a new day.
- **Risk:** a goal's death count (from `MethodStats.death`) is weighed against current armour and weapon tier.
- **Requirements:** `requires:` entries in the data are pulled in as sub-goals automatically.

*Tech tree as data* (`data/botcraft/goals/*.json`)
```json
{"id":"progress.nether","kind":"ACHIEVE","requires":["progress.diamond_pickaxe"],
 "done":{"advancement":"minecraft:story/enter_the_nether"},
 "children":[{"goal":"acquire","item":"obsidian","n":10},
             {"goal":"acquire","item":"flint_and_steel","n":1},
             {"goal":"build","blueprint":"nether_portal","zone":"portal"}]}
```
- **Condition vocabulary:** `have`, `wearing`, `toolTier`, `stock>=`, `advancement`, `flag`, `goalDone`, `zoneExists`.
- **Advancements as checks:** bots are real `ServerPlayerEntity`s, so vanilla advancements give free, persisted milestone checks.

*Migration*
1. Wrap each section of `chooseFree` as a `LegacyGoal` node, with fixed priorities in the same order. Run a parity soak.
2. Move `Targets.LADDER` into JSON and extend it to enchanting, the Nether, blaze rods, eyes of ender and the End.
3. Replace the fixed priorities with utility functions one root at a time, each behind its own flag.
4. Delete `Life`'s backoff map and `boardRest` toggle.

*Persistence.* An optional `goals` array in `companions.json`. If it's missing, rebuild from the roots: `satisfied()` re-derives progress from the world.

**Touches.** New `brain/goal/`. `brain/Life` becomes a thin adapter. `brain/plan/Targets` moves to data. `entity/CompanionData` gains `goals`. `command/CompanionCommand` gets the `why` tree.

**Done when.** The parity soak matches, then a native-utility soak reaches iron gear faster than the baseline, and `/companion why` prints the tree with scores and reasons.

---

## 3. Rules, constraints and triggers

**What.** Standing instructions that last beyond one job:
- "always keep 16 torches"
- "never mine near the base"
- "if you see diamonds, bring them to me"
- "when it gets dark, come home"

**How to implement.**
- **`Rules` on each companion** (persisted). Three types:
  - **Maintain rule:** creates a MAINTAIN goal ("keep torches 16").
  - **Constraint:** a filter checked by the Planner (removes methods), by `BreakPolicy` and `Zones` (removes positions), and by the goal selector (removes goals). Examples: "never mine_near base", "avoid zone enemy", "no_night_outdoors".
  - **Trigger:** an `event → goal` pair. Events come from Perception, sightings in `Memory.sight`, time of day, health and messages. "if you see diamonds bring them" becomes `on sight(diamond_ore) → ACQUIRE diamond, DELIVER owner`.
- **Faction-level rules** set by leaders apply to all members, for example "nobody leaves the walls at night during war".
- **Managing rules:** "what are your rules?" lists them, and "forget the torch rule" removes one.

**Touches.** New `brain/rules/`, plus `Planner`, `BreakPolicy`, `Zones` and `CompanionData`.

**Done when.** GameTests pass for a constraint (a bot won't mine inside a forbidden zone), a trigger (a sighting causes a delivery) and a maintain rule.

---

## 4. Personality and values

**What.** Distinct, stable characters that change how a bot works, fights, talks and which group it sides with.

**How to implement.**

*Traits* (0..1, persisted; seeded from the UUID when missing, like `Voice.of`)

| Trait | Where it plugs in |
|---|---|
| bravery *(exists)* | `Drives.safety/health` thresholds, risk penalty, raid volunteering |
| curiosity *(exists)* | Curiosity root weight, wander radius, asking questions |
| helpfulness *(exists)* | Board `helpful` term, accepting requests, defending others |
| chattiness *(exists)* | speech budget, reply length |
| diligence | goal commitment margin, idle gaps, finishing plans |
| aggression | engage distance, chasing, starting PvP, war votes |
| caution | `Planner.risk` multiplier, retreat HP, night penalty |
| sociability | Social root, working near others, spreading gossip |
| generosity | `Sharing.spare` thresholds, fair share in splits, gifts |
| ambition | Progress weight, seeking leadership, starting splits |
| loyalty | resisting fracture, following the leader over self-interest |
| humor | joke and pun lines, playful replies to insults |

*Values* are four axes, each from −1 to 1:

| Axis | Low end (−1) | High end (+1) |
|---|---|---|
| order ↔ freedom | wants rules and roles | wants self-direction |
| community ↔ self | shares stock | keeps their own |
| peace ↔ conquest | defends only | attacks rivals |
| build ↔ explore | settles a base | roams |

They drive faction fit (§11), and the faction's stance is the rank-weighted average of its members.

*Archetypes.* `data/botcraft/archetypes.json` holds miner, farmer, guard, scout, builder, trader, leader, hothead and coward. Use them with `/companion spawn Bob guard` or `/companion batch 10 mixed`.

*Drift.* Experience shifts traits within ±0.2 of where they started:
- deaths lower bravery
- kills raise aggression
- generosity that gets repaid raises generosity further

*A single `Weights` helper* is the only place that turns traits into multipliers. That keeps it tunable (§23) and visible in `/companion info`.

**Touches.** `brain/Personality` becomes a full record. `entity/CompanionData` gains trait, value and archetype fields. Drives, `Board.bid`, `Sharing`, `Planner.risk`, `Voice` and `Social` all read from `Weights`.

**Done when.** Soak reports show measurable differences between archetypes: deaths, kills, items delivered, distance explored, chat lines.

---

## 5. Mood

**What.** Short-lived feelings that colour behaviour and speech.

**How to implement.** Six values decay towards 0 with a half-life:

| Mood | Raised by | Half-life | Effect |
|---|---|---|---|
| fear | damage, enemy count, darkness | 30 s | more fleeing, less fighting, short replies |
| joy | goal done, gift, praise, milestone | 5 min | more banter, generous with requests |
| frustration | failures, path stalls, refusals | 3 min | switches goals sooner, grumpy lines |
| fatigue | continuous work (reset by sleep) | — | less night work, wants bed |
| boredom | repeating the same job | — | Curiosity root goes up |
| grief | ally died nearby | 10 min | sombre lines, revenge bias for aggressive bots |

`Templates.pick` already prefers `.brave`/`.timid` variants. Generalise it to a priority list of suffixes, such as `.grumpy`, `.happy` and `.scared`.

**Touches.** New `social/Mood`, plus `EventWatcher`, Drives, `Templates` and `Speech`.

**Done when.** Unit tests cover decay and effects, and a manual check shows mood variants appearing in chat.

---

## 6. Relationships and memory of people

**What.** Each bot remembers how it feels about every player and every other bot.

**How to implement.** Extend `Memory.Person`. It is already persisted per companion.
```java
float affinity;      // -1..1 liking
float trust;         //  0..1 keeps promises / doesn't steal
float debt;          // favours: + they owe me, - I owe them
long lastInteraction;
Deque<Note> recent;  // last ~10 notable events, for "remember when…" and grudges
```

| Event | affinity | trust | debt |
|---|---|---|---|
| gift | +0.05 | | they +1 |
| helped me fight | +0.10 | | they +1 |
| thanks / praise | +0.02 | | |
| insult | −0.05 | | |
| hit me | −0.30 | −0.20 | |
| killed me | −0.60 | −0.50 | |
| stole from our chests | −0.30 | −0.40 | |
| abandoned a promised job | | −0.10 | |

Values decay slowly towards neutral. Relationships feed into:
- accepting requests
- tone of voice
- whom to defend
- leader votes
- who leaves with whom in a split
- faction standing, through gossip (§7)

**Touches.** `memory/Memory`, `chat/Social`, `brain/Sharing`, `Board.bid` (a reciprocity term), and a new damage hook (§7).

**Done when.** Unit tests cover the update table and decay, and a GameTest confirms that a bot hit by a player refuses that player's next request.

---

## 7. Player standing and authority

**What.** Each faction keeps a **standing** for every player, and its bots remember it. Standing decides whether a player is friend or enemy, and whether they may give orders. The operator who spawned a bot always has authority by default. A toggle makes the owner subject to standing like everyone else.

**How to implement.**

*Standing.* One score per (faction, player), from −100 to +100, saved in `factions.json`. New players start at `playerStartStanding` (default 0).

| Observed | Δ |
|---|---|
| hits a member (not splash or accidental) | −25. The first hit while standing is positive gets a warning line ("Hey! Watch it.") |
| kills a member | −60 |
| kills a mob that was attacking a member / helps in a fight | +5 |
| gives items to a member (dropped or through the inventory screen) | +2 per stack, capped per day |
| takes items from faction chests (non-member) | −15 per stack |
| breaks a block the faction built (`Builds` ledger, §1) | −5 per block |
| apologises ("sorry") after an incident | +5 to +15, scaled by the bot's trust |
| a day without incidents | decays towards the baseline by 10% |

*Bands* have hysteresis, so a player doesn't flicker between states:

| Band | Standing | Bots… |
|---|---|---|
| **Trusted** | ≥ +60 | take orders, share items, reveal base and chest info |
| **Friendly** | +20 … +60 | answer questions and accept *requests* (decided by a bid, so they may decline), help in fights |
| **Neutral** | −30 … +20 | stay polite, reveal no base locations or stock, refuse orders |
| **Hostile** | ≤ −30 | treat as an enemy: attack in territory, everywhere for aggressive bots or in `pvp` mode |

*Authority* is decided in this order:
1. **The owner (the operator who spawned the bot):** always obeyed when `ownerAuthority: absolute` (the default).
2. **Faction leader or officer** (a bot or a player member): faction-level orders.
3. **Other players:** orders need **Trusted** (`orderStanding`, default 60), and requests need **Friendly**.
4. **Anyone:** may ask questions. Answers are filtered by band.

- **The toggle:** with `ownerAuthority: standing`, the owner starts at `ownerStartStanding` (default +90) and moves like anyone else. Attack your own bots and they stop listening.
- **Scope:** the toggle is global in config, with an optional per-faction override.

*Knowledge spreads realistically.* Only the victim and **witnesses** in sight range (`Sight` already exists) update their standing at once. Everyone else learns from a faction-channel message ("Steve attacked me at the farm!", §17). A bot's personal relationship (§6) is separate from faction standing, so a bot can like a player its faction distrusts.

*Event hooks (Fabric API 0.92).*
- **Damage to companions** with its attacker: `ServerLivingEntityEvents.ALLOW_DAMAGE`. Confirm the exact event name in 0.92.
- **Deaths:** `ServerLivingEntityEvents.AFTER_DEATH`.
- **Chest opening:** `UseBlockCallback`. Compare `Colony.stock()` before and after to attribute what was taken.
- **Broken builds:** `PlayerBlockBreakEvents.AFTER` together with the `Builds` ledger.
- **Gifts:** item pickup from a player-thrown stack, or inventory-screen transfers through `InventoryView`.

*Commands.* `/companion standing <faction> [player] [set <n>]`, plus the chat questions "Bob, do you trust me?" and "what do you think of Steve?".

**Touches.** New `faction/Standing`. `chat/ChatRouter` currently checks `owner` only; it switches to an `Authority.check(speaker, companion, intent)` call. Also `perception/Perception` (enemies), `entity/InventoryView` and `Botcraft` (event registration).

**Done when.** GameTests cover four cases:
- a player hits a bot, and nearby bots turn hostile while distant ones only turn hostile after the gossip message;
- a Trusted non-owner's order is obeyed and a Neutral one's is refused;
- the toggle makes the owner subject to standing;
- an apology restores Neutral.

---

## 8. Factions core

**What.** Groups of bots, optionally with player members, each with its own base, board, goals and relations.

**How to implement.**
```java
class Faction {
  String id, name; Formatting color;
  Map<UUID, Member> members;          // Member{rank, joined, contribution, isPlayer}
  @Nullable UUID leader; Governance gov;  // OWNER_APPOINTED | ELECTED | STRONGEST | NONE
  Values stance;                      // rank-weighted mean (§4)
  Colony home; List<Colony> outposts; Territory territory;
  Board board; GoalForest goals; Rules rules;
  Map<String, Relation> factions;     // standing −100..100 with other factions
  Map<UUID, Standing> players;        // §7
  Warfare warfare;                    // effective mode (§13)
}
```
- **Persistence and migration:** saved in `<world>/botcraft/factions.json`. If the file is missing, create one faction, "Colony", with every companion, and attach the existing `colony.json` to it. That is exactly today's behaviour.
- **Vanilla scoreboard `Team` per faction:** coloured names and prefix in chat and the tab list, friendly fire off, entirely server-side.
- **Membership changes:**
  - "everyone" means the speaker's faction.
  - Sharing, Claims and the Board work within the faction.
  - `Perception.enemies()` adds hostile players and the bots of hostile factions to today's monster `threats()`.
- **Commands:** `/companion faction create|join|leave|kick|promote|leader|info|list|relation|ceasefire`.

**Touches.** New `faction/`, plus `perception/Perception`, `chat/ChatRouter`, `CompanionManager` and `command/CompanionCommand`.

**Done when.** Two factions coexist with separate stock, separate boards and coloured names, and no cross-faction sharing.

---

## 9. Faction modes: single, teams, dynamic

**What.** How factions are organised server-wide.

**How to implement.** A config key `factionMode`, also changeable by command.

| Mode | Behaviour | Default warfare |
|---|---|---|
| `single` *(default)* | One big colony, like today | `defend` |
| `teams` | Fixed teams (red vs blue, or N teams). `/companion faction teams red blue [even\|by-owner\|manual]` splits the bots. Each team founds its own base dynamically (§10), or uses a manual one. Teams don't split or merge. | `defend`, or `teamsWarfare` (e.g. `pvp`) |
| `dynamic` | Factions form, split, merge and ally on their own (§11, §12) | **`pvp` + `raid` automatically** |

Chat versions: "Bob, you're on the red team" and "red team, go home". Switching from `dynamic` back to `single` merges everything back. It asks for confirmation, because chests have to be re-pooled.

**Touches.** `config/CompanionConfig`, `faction/FactionManager` and commands.

**Done when.** A red vs blue soak runs stably for an hour: both teams found bases, progress, and fight according to their warfare mode.

---

## 10. Dynamic bases and zoning

**What.** Bots choose where to settle, found the base, grow it as the faction grows, add outposts, and move when a site turns out bad. This builds directly on the existing `BaseMap` and `Zones.plan`. Those already lay out core, beds, farm, pen, paths and home from a mapped area and never replace a hand-set zone.

**How to implement.**

*1. Site survey (finding candidates)*
- **`SiteSurvey`:** a sparse, cheap scan around a candidate centre. Unlike `BaseMap`, it doesn't run every tick. It samples a 64×64 area on a 4-block grid (256 columns) for ground height, ground kind, water, trees and lava. It reuses `BaseMap`'s column classification code.
- **Candidates come from memory:**
  - places scouts have seen (§19, the scout role)
  - forests and herds in `Memory` sightings
  - a ring of points at a configurable distance from other factions
- **Score:**
  ```
  site = 0.30·flatness(largest level area) + 0.15·waterNear + 0.15·treesNear + 0.10·animalsNear
       + 0.10·cavesOrOreNear + 0.10·distanceFromEnemies + 0.05·biomeSafety − 0.05·builtBlocksNearby(others' bases)
  ```
  `distanceFromEnemies` is scaled by the faction's peace ↔ conquest value. Peaceful factions settle far away; aggressive ones settle within raiding distance.
- **Exclusions:**
  - other factions' territory
  - player builds (from the `Builds` ledger)
  - villages, unless trade-minded
  - oceans and deserts below a score floor

*2. Founding (a BUILD goal in the faction tree)*
1. The leader (or the bot with the best ambition × leadership score) walks to the chosen site.
2. It calls `Colony.setBase` there. `BaseMap` maps it, and the existing `Zones.plan` lays out core, beds, farm, pen and home.
3. The first sub-goals are a chest in the core, beds for members, a crafting table and furnace in the core, and torches. These already exist as Life behaviours and move under this goal.

*3. Growth (zones that scale with the faction)*
- New zone kinds:

  | Zone | Holds |
  |---|---|
  | `storage` | a chest wall |
  | `workshop` | anvil, enchanting, brewing |
  | `tree_farm` | managed trees |
  | `mine` | mine entrance, so mining starts outside home |
  | `portal` | nether portal |
  | `wall` / `gate` | perimeter defences |
  | `post` | guard posts |
  | `outpost` | a remote base |

- **Faction MAINTAIN goals drive growth** and post it to the Board:
  - bed slots ≥ members, which extends the bed row or adds a second one
  - farm area ≥ 20 tiles per member, which adds farms
  - storage capacity ≥ 1.5× stock
  - walls once the faction has been raided or is at war
- **`Zones.plan` becomes incremental:** "place zone kind K of size S" searches the mapped area with the existing `field()` scoring (generalised to any size and shape), avoiding paths and other zones.
- **`BaseMap.RADIUS` grows with the home zone** (40 now; up to ~64 for big factions). Sweeps get slower, which is fine.
- **Manual zones stay sacred:** planning never moves a `manual` zone (as today). The owner can still use `/companion colony zone <kind> here` to override.

*4. Outposts.* When a valued resource is far away (diamond area, nether portal site, a big forest), the faction may found an outpost. An outpost is a small `Colony` with core and beds only, linked by a courier route. Its chests count towards faction stock, at a distance cost in the Planner.

*5. Relocation.* The leader re-scores the current site every in-game day:
- deaths near the base
- raids suffered
- resources nearby depleted (trees and animals gone from memory)
- a much better known site

Relocation happens only if a new site beats the current one by a large margin (30%) for two days in a row. Moving means founding the new base, then courier trips to move the chests, then releasing the old territory.

*6. Territory.* The home zone plus a buffer (8 blocks), plus outposts. It's used for trespass detection (§7, §13), patrol routes and the exclusions above.

**Touches.** `colony/Zones` (incremental planning, new kinds), `colony/BaseMap` (variable radius), new `colony/SiteSurvey` and `faction/Territory`, faction goals, `Upkeep` (zone kinds to keep level).

**Done when.** In a teams soak starting with no base set, each team picks a site, founds it, its zones are laid out, and they grow as members are added. A GameTest checks that site scoring prefers the flat, watered spot in a fixture.

---

## 11. Fracture, merging and leadership

**What.** In `dynamic` mode, unhappy groups break away, failing groups merge, and leaders emerge.

**How to implement.**

*Leadership.*
- **Owner-appointed** when set.
- **Otherwise elected:** each member votes for the member it scores highest on `affinity × 0.5 + contribution × 0.3 + ambition-of-candidate × 0.2`. Elections run every in-game week, or after the leader dies twice.
- **Leader duties:**
  - runs the faction goal tree
  - assigns roles
  - handles diplomacy
  - decides splitting the chests when a split happens

*Dissatisfaction* (per member, re-computed every few minutes):
```
d = 0.30·valuesDistance(member, stance) + 0.20·(1 − affinity(leader)) + 0.20·workloadUnfairness
  + 0.15·crowding + 0.15·recentGrievances − 0.25·loyalty
```
- **workloadUnfairness:** contribution (Board jobs done, items delivered) compared with reward (food and gear taken from stock).
- **crowding:** members per bed, food per member.

*Stages.*
1. **Grumbling.** Above 0.5, the bot complains in chat now and then. A good leader response ("I hear you, Bob") lowers it a little.
2. **Dissent.** Above 0.65 for two days, the bot becomes a dissident and talks with other dissidents (sociability).
3. **Split.** Triggered when dissidents reach a quorum: at least `minSplitSize` (3) **and** at least 25% of members.
   - **Who leaves:** label propagation over the affinity graph (deterministic with a seed). The cluster holding most dissidents leaves together. Its most ambitious member leads.
   - **Name and colour:** from a word list, or from the leader's name ("Bob's Band").
   - **Dividing the chests:** each side proposes a share weighted by contribution and generosity, and they settle at the midpoint. Leavers withdraw that much over a few trips. After that, the old chests count as **foreign**.
   - **New home:** §10 site selection, away from the old territory.
   - **Relations:** start at `−grievances` (an acrimonious split starts hostile).
4. **Merge.** Small or struggling factions (fewer than 3 members, starving, many deaths) look for an ALLIED faction with a close stance and ask to join. The leader accepts by bid.
5. **Defection.** A single bot whose dissatisfaction is high and whose affinity with another faction is higher may switch sides, at most once per in-game week.

*Limits:* `maxFactions`, `minFactionSize`, one split per faction per in-game week, and no splits during war.

**Touches.** New `faction/Fracture`, `faction/Leadership`, `faction/Clustering`, plus §10 and §12.

**Done when.** A dynamic soak with mismatched values produces a split that founds a new base, and a struggling faction merges, with no deadlocks or bots stuck without a faction. Unit tests cover the clustering and the share negotiation.

---

## 12. Diplomacy between factions

**What.** Relations between factions that change over time and are carried out by leaders.

**How to implement.**
- **Standing per faction pair,** −100..100: **Allied** ≥ +60, **Neutral** in between, **Hostile** ≤ −30, **At war** declared and sticky.
- **What moves it:**
  - attacks and kills
  - trespass
  - theft and raids
  - gifts, trades and joint fights against mobs
  - time without incidents
- **Leader goals** (in the faction tree):

  | Goal | Leader pursues it when… |
  |---|---|
  | **declare war** | aggression and grievance are high, and our strength beats theirs (§13) |
  | **offer peace** | war weariness (deaths and lost stock since the war began) outweighs the gains |
  | **propose alliance** | there's a shared enemy and close values |
  | **send gift** | relations need repair |

- **Diplomacy is visible:** the leader (or an envoy) walks to the other faction's territory edge and says it. The other leader answers with its own scoring. Accepted deals are announced on both faction channels.
- **Allies:** share sightings and may answer each other's Board requests (with a lower bid weight). They help in fights.
- **Owner override:** `/companion faction relation <a> <b> allied|neutral|hostile|war`.

**Touches.** New `faction/Diplomacy`, plus faction goals, the message bus (§17) and Board.

**Done when.** A soak shows a war declared after raids, then peace after losses, and allies helping each other in a fight.

---

## 13. Warfare: defence, PvP and raids

**What.** How hostile factions and hostile players are fought.

**How to implement.**

*Modes.* The effective mode is set per faction:

| Mode | Behaviour |
|---|---|
| `off` | never fight players or bots |
| `defend` *(default)* | fight hostile players or bots when attacked, or when they're inside our territory |
| `pvp` | fight hostile players or bots on sight anywhere |
| `raid` | `pvp` plus organised raids on enemy bases |

- `factionMode: dynamic` sets every faction to `raid`.
- **Breaking blocks or taking from chests during a raid also needs `griefing: true`.** Without it, a raid becomes a **skirmish**: an attack on the enemy's people at their territory edge, with no blocks broken and no chests looted.

*Defence (every mode except `off`).*
- Guards follow patrol routes along the territory edge, from the Guard role (§19); the night shift uses its schedule.
- **Alarm:** a WARN message on the faction channel ("Raiders at the farm!").
  - Armed members within 64 blocks come to the alarm spot.
  - Non-combatants (low gear, or timid with low bravery) go to the core or their beds.
  - The threat is passed to allies.
- After being raided, the faction goal tree adds wall, gate and post zones (§10) with BUILD goals.

*The raid goal* (a faction PERFORM goal):
1. **Intel:** a scout records the enemy base: chests seen, walls, guard posts, member count and gear.
2. **Assess:**
   - Strength is `Σ(weaponDamage × (1 + armour/20) × hp/20)` over the members we know about.
   - Raid when `ours / theirs ≥ 1.3 + caution(leader)·0.5 − aggression(leader)·0.3`.
3. **Muster:** a Board request with `slots: n` for armed members. Gather at a rally point outside their territory, with a timeout. Too few join → cancel.
4. **Approach:** move in formation (§21) and avoid known patrol paths.
5. **Breach (griefing on):** break only **blocks the enemy faction built** (`Builds` ledger tagged by faction, §1), meaning doors, fences and walls. Use the cheapest entry by path cost, through `BreakPolicy` with an explicit enemy-builds allowance. Never break natural terrain beyond what pathing needs.
6. **Loot (griefing on):** take from enemy chests by value (diamonds, then iron, gear and food) up to a carry limit, keeping free slots for the trip home.
7. **Withdraw:** when squad HP drops below 40%, when outnumbered, or after a time cap. The wounded leave first.
8. **Aftermath:** standing drops for both sides, loot gets deposited, and a short chat summary goes out.

*Safety rails (config)*
- `raidPlayerBuilds: false` by default. A real player's builds are only fair game if that player is a member of the enemy faction.
- Never break blocks in villages or structures.
- `maxBlocksPerRaid` (default 32).
- Raid cooldown per faction pair.
- `/companion faction ceasefire` stops all hostilities at once.

**Touches.** New `faction/Raid`, new `brain/task/RaidTask`, plus `PatrolTask`, `BreakPolicy`, `colony/Builds` and `perception/Perception`.

**Done when.** Four GameTests pass:
- defend mode only fights intruders
- the alarm brings defenders
- with griefing off, a raid breaks no blocks
- with griefing on, a raid opens a door and takes from a chest

A dynamic soak has raids that start, fight and end with nothing stuck.

---

## 14. English understanding (NLU)

**What.** Understand much more of what players type, compose orders, follow context and know when it didn't understand. This replaces the regex cascade in `IntentParser` gradually. The current regexes stay as a fast path until the new parser covers the golden test set.

**How to implement.** Messages go through a pure-Java pipeline in `nlu/`, all unit tested:

```
raw → Normalise → Tag → Chunk → Frames → Discourse → Interpret → Confidence → (act | echo | clarify)
```

1. **Normalise.**
   - Lowercase and handle unicode punctuation. Emoticons become tokens.
   - Squash stretched letters ("pleeease").
   - Expand contractions and slang (`gimme`, `wanna`, `can't`; extend `SLANG`).
   - Read amounts: "a stack" is 64, "half a stack" 32, "a couple" 2, "a few" 3–5, "some" uses a default per item, "all of it" is ALL, "2 and a half stacks" is 160.
   - Fix typos against the full lexicon plus names and item names, using the existing Damerau distance. Prefer the correction that produces a valid frame.
2. **Lexicon** (data: `data/botcraft/lexicon/en.json`, plus a per-world overlay from teaching, below).
   - Verbs with their possible meanings.
   - Nouns and aliases ("lumber" means logs, "diamond pick", "grub").
   - Places ("base", "the farm" maps to a zone, "the cave" to a sighting).
   - Pronouns, time words ("tonight", "before dark", "in the morning"), modal and polite words ("can you", "please"), negation ("don't", "never"), rule words ("always", "from now on").
   - Item names are generated once by a `tools/` script from vanilla `en_us.json`, then hand-curated.
3. **Tag** each token with a category: VERB, ITEM, QUANT, PLACE, PERSON, PREP, TIME, NEG, MODAL, WH, CONJ, NAME.
4. **Chunk** tokens into phrases:
   - **object:** `QUANT? ADJ* ITEM`
   - **location/recipient:** `PREP (PLACE|PERSON|"there"|"here")`
   - **time:** `TIME | until X | for N minutes`
   - **condition:** `if/when CLAUSE`
5. **Frames.** The verb's meaning is chosen by what its arguments are:
   | Verb | Argument | Meaning |
   |---|---|---|
   | get / grab / fetch | item | ACQUIRE |
   | get | "over here", place | MOVE |
   | bring / give | item + person | ACQUIRE then DELIVER |
   | make / build | craftable item | CRAFT |
   | make / build | structure ("a house", "walls") | BUILD |
   | kill / attack | mob / player / faction | FIGHT (PvP checked against §13) |
   | guard / protect | place / person | GUARD / DEFEND |
   | farm / plant / harvest | crop / zone | FARM |

   A `Frame` holds the verb sense, object, quantity, source, destination, recipient, time, condition, negation, politeness and modality.
6. **Clause logic.**
   - **Sequence:** "then" (exists today).
   - **Split between bots:** "Bob mine coal, Alice chop wood".
   - **Conditions** become triggers (§3).
   - **Durations:** "until morning", "for 5 minutes", "until you have 64".
   - **Negation** becomes a constraint, and "stop collecting X" removes a goal.
   - **Corrections** patch the last frame: "no, I meant birch".
   - **Quantities:** "everyone get 10" means 10 each; "get 10 between you" means 10 total, split through the Board.
7. **Questions** are answered from live state:
   | Question | Answer source |
   |---|---|
   | where is/are X | Memory sightings, colony chests, positions of people |
   | how many X | inventory, `Colony.stock()`, `Memory.stats` |
   | what are you doing / why | `Brain.describe()` + the goal-tree reason chain |
   | can you make/get X | `Planner.acquire` dry run: "Yes, about 4 minutes. I'd need 3 iron first." |
   | what do you need | open MAINTAIN deficits, next Progress leaf |
   | when will you be done | remaining plan cost |
   | who's in your faction / are we at war / do you trust X | factions, standing, relationships, filtered by the asker's band |
   | what happened / anything new | the bot's recent event log |
8. **Confidence.** Each parse is scored from lexicon coverage, typo fixes, frame completeness and competing readings:
   - **High:** act.
   - **Medium:** act and echo the reading ("Okay, 20 *oak* logs.").
   - **One slot missing:** ask for it.
   - **Low:** offer the top two readings ("Did you mean *get logs* or *go to the logs*?").
   - Expensive or destructive orders always confirm first: raids, giving away diamonds, breaking builds.
9. **Teaching.**
   - "when I say lumber I mean logs" adds to a per-world lexicon overlay.
   - "*morning routine* means get food then light the base" stores a named plan.
   - Corrections are saved as (text, right frame) pairs, used to train the fallback classifier (§22) and logged for the test corpus.

**Touches.** New `nlu/`. `chat/IntentParser` becomes a thin facade; `chat/Intent` stays as the compatibility output. `chat/ChatRouter`.

**Done when.**
- The golden corpus (`src/test/resources/nlu/*.tsv`, several hundred lines, plus thousands of paraphrases from a test-only generator) passes at ≥ 95%.
- The **wrong-action rate** is below 1%. The thresholds lean towards asking rather than guessing.
- The existing `IntentParserTest` still passes.

---

## 15. Conversation and context

**What.** Bots keep track of each conversation, so short replies, pronouns and follow-ups work.

**How to implement.**
- **A `Conversation` per (bot, partner):**
  - the last topic
  - a **referent stack** for items, places and people
  - a pending question
  - an expiry (about 60 s of silence)
- **Coreference:**
  - "it" and "them" mean the last item or object.
  - "there" means the last place mentioned. Otherwise it's a **deictic** raycast from the speaker's eyes, the way "this chest" already works for `CHEST_SET`.
  - "him", "her" and "they" mean the last person mentioned.
- **Ellipsis:**
  - "and coal" repeats the frame with a new item.
  - "make it 30" changes the quantity.
  - "you too, Alice" copies the order to Alice.
  - "same for everyone" broadcasts it.
- **Pending questions:**
  - After "Oak or birch?", the answers "birch", "the second one", "either" and "whatever" all fill the slot.
  - After "Should I make a pickaxe first?", a yes or no resolves it.
- **Group manners:**
  - Broadcast questions get staggered replies, sorted by relevance.
  - A spokesperson (the leader, or the chattiest bot) may summarise for the group.
  - Others don't repeat the same answer.
- **Memory-aware small talk:**
  - Topics come from real events: "How did the mining go?", "We got the portal working!", "Remember when you fell in lava?".
  - Remembered preferences: "I like birch" leads to "Used birch, like you like."

**Touches.** New `comm/Conversation`, plus `chat/ChatRouter`, `chat/SmallTalk` and `memory/Memory` (preferences, event log).

**Done when.** Unit tests cover every coreference and ellipsis case above, and a GameTest checks a yes/no follow-up.

---

## 16. Responses (natural language generation)

**What.** Replies that are informative, varied, in character and not repetitive.

**How to implement.** Replies are built in three steps:
1. **Content plan.** Decide the facts, the act (inform, acknowledge, refuse, ask, warn) and the stance (confidence, mood, relationship, the listener's band).
2. **Realiser.** Templates from `dialogue.json` plus grammar helpers:
   - **Aggregation:** "12 coal, 3 iron and some logs".
   - Plurals, articles and number words ("a stack", "a couple").
   - **Referring expressions:** the first mention is "the chest by the furnace", later ones are "it".
   - **Hedging** from sighting age or plan confidence: "I *think* there were diamonds by the ravine".
   - **Because-clauses** from the goal reason chain, trimmed by chattiness.
   - **Echoing the player's own words:** reuse "lumber" if they said lumber.
   - **Refusals with reasons:** not authorised, too dangerous for current gear, against a rule, hostile, busy. Each has a counter-offer where possible: "I can't get diamonds yet; I need an iron pickaxe. Should I make one?"
3. **Voice** (exists): casual or proper, energy, fillers and emotes, plus a mood and relationship tone (§5, §6).

*Speech budget.* Each bot has a talking budget per minute, scaled by chattiness and `chatVerbosity`. Lines are sent in priority order:
1. warnings
2. requests for help
3. progress on orders ("halfway, 10/20 iron")
4. discoveries
5. social chatter

In combat, only tier 1 gets through.

**Touches.** New `comm/ContentPlanner` and `comm/Realiser`, plus `chat/Templates` (suffix priority list), `chat/Speech` (budget) and `chat/Voice`.

**Done when.** Realiser unit tests pass (aggregation, plurals, references), the manual chat checklist in `TESTING.md` is updated, and soak logs show no line repeated within N minutes.

---

## 17. Message bus (aware communication)

**What.** All speech, from bots and from players, becomes structured messages that are **physically heard**. Bots react only to what they could actually hear, and chat text is just how a message is displayed.

**How to implement.**
```java
record Utterance(UUID from, Audience to, Act act, Topic topic, Map<String,Object> slots,
                 Vec3d where, Range range, long at, @Nullable UUID replyTo) {}
enum Act { INFORM, REQUEST, OFFER, ACCEPT, REFUSE, QUERY, ANSWER, WARN, GREET, THANK, INSULT, PROMISE, ORDER }
enum Range { WHISPER(4), SAY(24), SHOUT(64), FACTION(∞) }
```
- **Hearing:** a message reaches listeners within range in the same dimension. WHISPER also needs line of sight. FACTION reaches the faction channel, shown in chat with the Team prefix.
- **Lookups:** delivery uses a spatial bucket by chunk, so it's O(listeners nearby).
- **One path for all speech:** Social banter, `Sharing` asks, Board narration and `EventWatcher` remarks all post messages instead of calling `Speech` directly. Player chat is parsed (§14) into messages too.
- **Listeners update their state:**
  - A sighting INFORM updates `Memory` (partly done in Social today).
  - An attack INFORM updates standing (§7).
  - A REQUEST is answered with a bid (accept or refuse).
  - A PROMISE is tracked, and breaking it costs trust.
  - A WARN can trigger the alarm (§13).
- **Bot-to-bot negotiation is visible chat:**
  - "Bob, can you spare coal?"
  - Bob replies "8 coal coming" or "sorry, I need it for smelting".

**Touches.** New `comm/MessageBus` and `comm/Utterance`, plus `chat/Social`, `brain/Sharing`, `brain/Board`, `chat/EventWatcher` and `chat/Speech`.

**Done when.** A GameTest shows a bot out of range does **not** react to a WARN while one in range does. The soak chat log shows a request → accept → delivery chain between bots.

---

## 18. Roles

**What.** Lasting specialisations that weight what a bot chooses to do, without locking it out of anything.

**How to implement.**
- **A role is a weight vector** over goal categories: food, wood, ore, build, guard, scout, trade, craft.
- **Assignment comes from:**
  - the owner or leader ("Bob, you're the farmer")
  - the archetype
  - skill that emerges from `MethodStats` successes per category (sticky, slow decay)
  - the leader rebalancing when a faction MAINTAIN goal keeps failing (for example, food stock keeps dropping, so another farmer is assigned)
- **Schedules** are data per role, for day, dusk and night. A guard patrols at night while a farmer sleeps.
- **Workplaces:** a farmer's farm zone, a guard's post, a smith's workshop zone (§10).

**Touches.** New `brain/Roles`, plus `Weights`, `Board.bid` and faction leadership.

**Done when.** A soak with a "farmer" archetype shows it spends most of its time farming but still eats, flees and sleeps normally.

---

## 19. Expanded jobs

**What.** New things bots can do. Each one follows the existing pattern:
- a Task class
- a `Jobs.create` spec
- a `Jobs.describe` phrase
- a Planner method with a cost prior and `MethodStats` learning
- a Board request kind where it makes sense
- a GameTest

**How to implement (in priority order).**

| Job | Task(s) | Planner / Board | Notes |
|---|---|---|---|
| **Farmer** | `FarmTask`: till, plant, harvest by crop age, replant, bonemeal, check water | wheat, carrot, potato, beetroot and seeds get a "farm" method; bread is crafted | The biggest food win. The FARM zone already exists. |
| **Rancher** | `BreedTask`: hold the right food, lure into the pen, breed pairs, cap the herd, cull extras. `ShearTask`. | meat, leather and wool get a "ranch" method | Needs a BUILD for the pen fence and gate. The PEN zone exists. |
| **Forester** | replant saplings after `GatherLogsTask`; tree-farm zone | lower log cost near base over time | |
| **Fisher** | `FishTask`: cast, watch for the bobber, reel in | fish as a food fallback | Easy and low risk |
| **Cook** | smoker or campfire; keep cooked food stocked | MAINTAIN cooked food | Extends the existing smelt and cook logic |
| **Builder** | `BuildTask` from blueprints: bottom-up order, scaffolding, material list fed to ACQUIRE | BUILD goal kind | Blueprints are vanilla Structure Block `.nbt` exports, or JSON layers. Start with house, wall segment, gate, nether portal, farm plot and pen. |
| **Guard** | `PatrolTask` (route along the territory edge), `PostTask` (stand at a gate and scan), `RespondTask` (alarm) | Board GUARD kind with shifts | §13 |
| **Scout** | `ScoutTask`: spiral or frontier exploration recording biomes, villages, structures, enemy bases, ore exposures and base sites | improves Planner distances and §10 site candidates | Feeds intel and raids |
| **Smith** | anvil repair, smithing table (netherite) | "repair" method; gear durability as a MAINTAIN goal | |
| **Enchanter** | enchanting table plus bookshelves (BUILD), lapis, XP from smelting and kills | enchanted gear goals in the tech tree | |
| **Trader** | villager trading through the merchant screen handler | ACQUIRE by "trade" (emeralds, books, food) | |
| **Courier** | carry stock between home and outposts or allies | DELIVER between colonies | §10 outposts |
| **Brewer** | brewing stand, nether wart farm, blaze powder | potions for combat | After the Nether |

**Touches.** New tasks in `brain/task/`, plus `brain/Jobs`, `brain/plan/Planner`, `brain/Board`, `colony/Zones` and new blueprint data.

**Done when.**
- Each job has a GameTest.
- In a 2-hour soak with farming on, **food never runs out**.
- An unattended run reaches enchanted iron gear and a built nether portal.

---

## 20. Combat controller and abilities

**What.** Smarter fighting against mobs, players and enemy bots, with real techniques.

**How to implement.** Replace the single loop in `FightTask` with a `CombatController` (`combat/`):
- **Target selection.** Score every enemy from `Perception.enemies()`:
  ```
  threat = dps × proximity × (targetingMe 1.5 | targetingAlly 1.3 | 1) × (lowHp 1.2) × (creeperFusing 3) × (squadFocus 1.4)
  ```
  Per-entity data in `data/botcraft/combat/mobs.json`: dps, reach, ranged?, explodes?, best counter, avoid in water. A player's danger comes from **observed** armour, held weapon and hit rate.
- **Stance**, re-chosen every few ticks: `ENGAGE`, `KITE`, `HOLD` (shield), `RETREAT` or `REGROUP`.
- **Abilities** are pluggable: `usable(ctx)`, `utility(ctx)`, `tick(ctx)` and a cooldown. The bot picks the highest utility, with a little personality randomness.

| Ability | How it works | What `ActionController` needs |
|---|---|---|
| Crit | jump, then hit while falling with full cooldown | timed jump then attack |
| Sprint-knockback (W-tap) | sprint, hit, release sprint, sprint again | sprint toggle each tick |
| Strafe | circle at reach distance, changing direction at random | sideways movement input |
| Shield | shield in offhand; hold it up when a skeleton draws, a creeper fuses or a player swings; drop it to hit back | **hold-use** (continuous use + `setCurrentHand`), offhand equip |
| Axe vs shield | switch to an axe when the target is blocking (disables the shield for 5 s) | hotbar swap |
| Bow / crossbow | charge, aim with gravity and lead (below), keep distance | **release-use** (`stopUsingItem`) |
| Water bucket | catch a long fall (MLG), put out fire, block lava, push mobs | place and pick up fluid |
| Heal | golden apple and potions at an HP threshold scaled by caution; eat when there's distance | drink and throw; eating exists |
| Pillar / wall | when outnumbered by melee: pillar up 2–3 blocks or place a block between | place under self (exists for bridging) |
| Choke point | fall back to a 1-wide gap or doorway | `Terrain` query |
| Ender pearl / totem | late game: escape or engage; totem in offhand when risk is high | throw, offhand swap |

- **Bow aim.**
  - The arrow starts at speed v = 3.0 blocks/tick at full charge. Gravity is 0.05 per tick² and drag 0.99 per tick.
  - Solve the pitch for the horizontal distance and height difference with a few Newton steps. Cache by (distance, height) bucket.
  - Lead the target by its velocity × flight time.
  - Add the existing aim jitter so the shots look human.

**Touches.** New `combat/`. `brain/task/FightTask` becomes a wrapper around the controller. `action/ActionController` gets hold-use, release-use and offhand. Also `action/Inventories` and `perception/Perception`.

**Done when.**
- GameTest arenas pass:
  - 1v1 zombie
  - skeleton vs shield
  - creeper fuse
  - 3 zombies vs 2 bots
  - bot vs bot from two factions
  - bow vs zombie at 15 blocks
- A new `ops/CombatBench` (like `NavBench`) shows a better win rate and less damage taken than the current FightTask.

---

## 21. Squad tactics

**What.** Faction members fighting together, not just side by side.

**How to implement.**
- **Squad:** same-faction bots fighting within 16 blocks. The squad leader is the highest rank, or the most experienced bot.
- **Focus fire:** the leader's target gets the `squadFocus` bonus and is announced over the bus ("Focus the skeleton!").
- **Roles from gear:** shield bearers in front, bows behind, and the wounded rotate out.
- **Formation:** a wedge or line relative to the leader's facing, held with ordinary Navigator goals. It's used for raids and for advancing on intruders.
- **Group retreat:** the whole squad falls back to the rally point (the core or a gate) when total HP is below 40% or it's outnumbered 2:1.
- **Protect important people:** the owner, the faction leader and non-combatants count as `targetingAlly` with a higher weight.

**Touches.** New `combat/Squad`, plus the message bus and `nav/Goal`.

**Done when.** CombatBench squad scenarios (3v3 bots, 3 bots vs a mob wave) beat uncoordinated fighting on win rate and survivors.

---

## 22. Machine learning (small and explainable)

**What.** Learning where it clearly helps, without LLMs, neural text generation or native libraries.

**How to implement (in order of value for effort).**

1. **Thompson sampling for method choice (very cheap).**
   - `MethodStats` already keeps successes and attempts.
   - Instead of always choosing the lowest expected cost, sample a success rate from Beta(ok+1, fail+1) for each option and choose by sampled cost.
   - Bots then retry alternatives that were unlucky early, instead of dropping them for good. About 30 lines in `Planner.risk`.
2. **Fallback intent classifier.**
   - Logistic regression (or naive Bayes) over word uni- and bigrams, character 3-grams and lexicon tags.
   - It runs **only when rule confidence is low**. It suggests a verb sense, which still goes through frame checks, and it never acts on its own.
   - Trained offline from the golden corpus and paraphrases; the weights ship as JSON.
   - It learns online per world from confirmed clarifications and corrections.
   - Pure Java, a few hundred lines, deterministic in tests.
3. **Contextual bandits** for a few live choices: which combat ability to open with, and Board bid weights.
   - The context is a discretised situation.
   - The algorithm is LinUCB or ε-greedy, rewarded by the outcome.
   - Bounded, logged, and switchable with `Experiments`.
4. **Offline weight tuning with the existing harness.**
   - `tools/tune.py` runs CMA-ES or a simple genetic algorithm over the tunable weights in `Weights`: drive scores, bid terms, dissatisfaction weights, combat thresholds.
   - Each candidate is scored with `soak-ab.sh` and CombatBench metrics: deaths per hour, progress tier, items delivered, win rate.
   - The best weights ship as `data/botcraft/weights.json`. Runtime stays rule-based and readable; only the numbers are learned.
5. **Tabular Q-learning for combat micro (optional, last).**
   - The state is distance, HP, cooldown, shield and target type.
   - The actions are the abilities.
   - It's trained in GameTest arenas and shipped as a table. Only worth it if hand-tuned abilities plateau.
6. **Unsupervised helpers.**
   - Label propagation for faction splits (§11).
   - Clustering ore sightings into "mining areas" for the Planner.

**Touches.** New `learn/`, plus `brain/plan/Planner`, `nlu/`, `combat/` and `tools/tune.py`.

**Done when.** Each piece shows a measurable gain over its rule-only baseline in an A/B soak or bench, or it isn't turned on by default.

---

## 23. Configuration

New keys in `config/botcraft.json`, all with backward-compatible defaults:

| Key | Default | Meaning |
|---|---|---|
| `factionMode` | `single` | `single` / `teams` / `dynamic` |
| `warfare` | `defend` | `off` / `defend` / `pvp` / `raid` (overridden to `raid` in dynamic mode) |
| `teamsWarfare` | `defend` | warfare used in teams mode |
| `ownerAuthority` | `absolute` | `absolute` or `standing` (§7 toggle) |
| `ownerStartStanding` | 90 | owner's starting standing in `standing` mode |
| `playerStartStanding` | 0 | new players' starting standing |
| `orderStanding` / `requestStanding` | 60 / 20 | thresholds for orders and requests |
| `raidPlayerBuilds` | false | allow raiding real players' builds (they must be members of an enemy faction) |
| `maxBlocksPerRaid` | 32 | raid griefing cap |
| `maxFactions` / `minFactionSize` | 6 / 2 | limits on dynamic factions |
| `dynamicBases` | true | bots pick, found and grow bases themselves (manual zones still win) |
| `speechBudget` | 1.0 | multiplier on the talking budget |
| `learning` | `on` | turns online learning (bandits, classifier updates) on or off |

---

## 24. Observability and commands

- **`/companion why <name>`:** the goal tree with utilities, the active branch, blocked nodes and reasons.
- **`/companion info <name>`:** traits, values, mood, role, rules, top relationships.
- **`/companion faction info <name>`:** members and ranks, stance, leader, dissatisfaction per member, standing tables, warfare mode, recent events.
- **`/companion standing <faction> [player] [set n]`:** view or set a player's standing.
- **`/companion colony`:** existing, extended with zones by faction, territory and outposts.
- **Soak report sections:**
  - goals completed per root
  - splits, merges and wars
  - raids and blocks broken
  - player standing changes
  - NLU clarification rate
  - chat lines per tier
  - time per subsystem from `TickBudget`

---

## 25. Persistence and migration

| File | Change |
|---|---|
| `companions.json` | Optional fields: traits, values, archetype, role, goals, rules. Missing fields mean seeded defaults. |
| `memory/<name>.json` | `Person` gains affinity, trust, debt and recent notes; adds conversation preferences and an event log |
| `colony.json` | Becomes the "home" colony of the default faction; new zone kinds are added as plain entries |
| `factions.json` *(new)* | Factions, members, standing, relations, territory, outposts. If missing, one faction holding everyone. |
| `built.dat` | Entries gain a faction tag; old entries count as "no faction" (treated as player builds) |
| `lexicon-overlay.json` *(new, per world)* | Words, routines and corrections taught by players |

Bump `configVersion` with a migration step. Each new file loads independently, so a broken or missing file falls back to defaults instead of breaking the world.

---

## 26. Testing strategy

| Layer | How |
|---|---|
| NLU, realiser, standing math, dissatisfaction, clustering, share negotiation, ballistics, site scoring | Plain JUnit (pure Java, no Minecraft) |
| Goal-tree selection | JUnit with a fake `Ctx` |
| Tasks: farm, breed, build, patrol, shield, bow, raid | GameTests |
| Factions: standing from hits, gossip range, authority, teams, raids with griefing on and off | GameTests with two factions in one structure |
| Long-run behaviour | `runSoak` with the new report sections; A/B per flag; `CombatBench` for combat |
| Chat feel | The manual checklist in `docs/TESTING.md`, extended |

---

## 27. Build order

| # | Milestone | Depends on |
|---|---|---|
| M0 | §1 Scoped state + event hooks (damage, chest, block-break) | — |
| M1 | §2 Goal tree at parity, §24 `why` tree | M0 |
| M2 | §2 Native utilities and tech tree in JSON, §3 rules, §22.1 Thompson sampling | M1 |
| M3 | §14 NLU, §15 conversation | M1 |
| M4 | §4–6 Personality, mood, relationships | M1 |
| M5 | §16 Responses, §17 message bus | M3, M4 |
| M6 | §19 Farmer, rancher, forester, fisher, cook; §20 crit, strafe, shield, bow, bucket; CombatBench | M2 |
| M7 | §7 Player standing and authority, §8 factions core, §9 single and teams modes | M0, M4, M5 |
| M8 | §10 Dynamic bases and zoning, §18 roles, §19 scout, builder and guard | M6, M7 |
| M9 | §11 Fracture and leadership, §12 diplomacy, §13 warfare and raids, §21 squads | M8 |
| M10 | §19 smith, enchanter, trader, courier, brewer; Nether to End goals | M8 |
| M11 | §22.2–22.5 Classifier, bandits, offline tuning | M3, M6 |

After M1 there are three parallel tracks:
- **Language:** M3 → M5
- **Social:** M4 → M7 → M9
- **Abilities:** M6 → M8 → M10
