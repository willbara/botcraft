# Botcraft: long-term direction

## Goal
A handful of companions run unattended on a private Fabric 1.20.1 server and play
Minecraft autonomously, indefinitely: build a simple base, stockpile resources, grow
in power (iron -> diamond gear -> enchanting -> Nether -> Ender Dragon). Players can
join to watch, help, or give orders; player orders always win. Effective play matters;
believable play doesn't, but they should look like players.

## Current state
Low-level capability is strong: Baritone-style pathfinding, mining, crafting planner,
smelting, hunting, melee combat, persisted job queue, free-mode "Life" planner
(fixed goal list), memory, claims to avoid conflicts, pickup tracking, safe respawn.
See README for details.

## Main gaps (rough priority)
1. Chests and a home base: deposit/withdraw jobs, stock tracking.
2. Long-term planning: a milestone/tech-tree layer that emits jobs into JobQueue
   with persisted progress.
3. Sustainable food: farming and animal breeding.
4. Survival tooling: buckets (water/lava), shields, bows. Lava is the classic killer.
5. Smithing, brewing, enchanting, anvils, XP.
6. Nether, then End (portal, fortress, blazes, eyes of ender, dragon).
7. Ops: watchdog/stall detection, death recovery (retrieve lost gear, back off
   risky goals after repeated deaths), soak-test harness. Verify companions keep
   chunks loaded with no real player online.

## Cooperation layer (new `colony/` package)
Companions cooperate via a contract-net pattern. Structured messages in code are the
transport; chat is only the narrated view ("I'll hunt for food", "Can Bob go mine?").

- **Needs**: colony-wide shortfalls derived from stock (chests + inventories) and
  milestone goals, each with urgency 0..1 (food, coal, iron, beds...).
- **Board**: shared list of requests (`mine coal 16`) with requester, priority,
  expiry, status (open -> claimed -> done/failed/expired). Server tick is
  single-threaded, so claims are atomic.
- **Bidding**: each companion scores open requests:
  bid = urgency + skill (lifetime stats) + fit (tools carried, proximity)
        + personality + reciprocity - switch_cost - fatigue (contribution vs average)
  Highest bid above a threshold claims it; the claim becomes ordinary jobs in JobQueue.
- **Emergent roles**: a role is a sticky bias from past performance (with slow decay),
  giving natural specialization. A per-companion contribution score balances load
  and doubles as a debug metric.
- **Delegation**: "Can Bob go mine?" is a directed request. The target runs the same
  bid with a helpfulness bonus and accepts or declines in character; a decline lets
  the asker try someone else or do it themselves.
- **Integration**: Life planner becomes "check board, bid, else personal goals".
  Generalize the existing Sharing system (ask a friend for coal) instead of adding
  a parallel mechanism. Owner commands: "Bob, you're the miner now", "everyone,
  build a base", `/companion colony` (board, stock, scores).

## Pitfalls to design for
- Oscillation: commitment time plus hysteresis (like Brain).
- Deadlocks: every request expires, every claim times out.
- Dead or stale claimers: their claims revert to open.
- Chat spam: narrate only claims and completions, rate-limited.
- Risky bids: gate dangerous tasks on gear and death count.

## Suggested build order
1. Chests and stock tracking.
2. Board + bidding for basic needs (food, wood, coal, stone) with chat narration.
3. Specialization and contribution score.
4. Directed delegation with accept/decline dialogue.
5. Milestone needs from the tech tree (diamonds, obsidian, ...).

## Constraints
Server-side only, Fabric API as sole dependency, no LLMs or native libs. Keep to
the `tickBudgetMs` budget. Add GameTests for new behavior. Keep persistence
compatible with existing `companions.json` and memory files.
