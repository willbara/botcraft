package com.botcraft.command;

import com.botcraft.Companion;
import com.botcraft.CompanionManager;
import com.botcraft.chat.CommandTasks;
import com.botcraft.config.CompanionConfig;
import com.botcraft.entity.SkinResolver;
import com.botcraft.entity.SkinUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/** /companion spawn|remove|list|stop|task. */
public final class CompanionCommand {
	private CompanionCommand() {
	}

	private static final SuggestionProvider<ServerCommandSource> COMPANION_NAMES = (ctx, builder) -> {
		CompanionManager m = CompanionManager.get();
		List<String> names = new ArrayList<>();
		if (m != null) {
			for (Companion c : m.all()) {
				names.add(c.name());
			}
		}
		return CommandSource.suggestMatching(names, builder);
	};

	private static final SuggestionProvider<ServerCommandSource> SKINS = (ctx, builder) -> {
		List<String> options = new ArrayList<>(SkinUtil.skinIds());
		for (ServerPlayerEntity p : ctx.getSource().getServer().getPlayerManager().getPlayerList()) {
			options.add(p.getGameProfile().getName());
		}
		return CommandSource.suggestMatching(options, builder);
	};

	private static final SuggestionProvider<ServerCommandSource> TASKS = (ctx, builder) ->
		CommandSource.suggestMatching(CommandTasks.EXAMPLES, builder);

	public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("companion")
			.requires(src -> src.hasPermissionLevel(CompanionConfig.get().commandPermissionLevel))
			.then(CommandManager.literal("spawn")
				.then(CommandManager.argument("name", StringArgumentType.word())
					.executes(ctx -> spawn(ctx, null))
					.then(CommandManager.argument("skin", StringArgumentType.word())
						.suggests(SKINS)
						.executes(ctx -> spawn(ctx, StringArgumentType.getString(ctx, "skin"))))))
			.then(CommandManager.literal("batch")
				.then(CommandManager.argument("count", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, BATCH_MAX))
					.executes(CompanionCommand::batch)))
			.then(CommandManager.literal("skin")
				.then(CommandManager.argument("name", StringArgumentType.word())
					.suggests(COMPANION_NAMES)
					.then(CommandManager.argument("skin", StringArgumentType.word())
						.suggests(SKINS)
						.executes(CompanionCommand::skin))))
			.then(CommandManager.literal("remove")
				.then(CommandManager.argument("name", StringArgumentType.word())
					.suggests(COMPANION_NAMES)
					.executes(CompanionCommand::remove)))
			.then(CommandManager.literal("chat")
				.executes(CompanionCommand::chatShow)
				.then(CommandManager.argument("level", StringArgumentType.word())
					.suggests((ctx, b) -> CommandSource.suggestMatching(List.of("all", "quiet", "off"), b))
					.executes(CompanionCommand::chatSet)))
			.then(CommandManager.literal("removeall")
				.executes(CompanionCommand::removeAll))
			.then(CommandManager.literal("info")
				.then(CommandManager.argument("name", StringArgumentType.word())
					.suggests(COMPANION_NAMES)
					.executes(CompanionCommand::info)))
			.then(CommandManager.literal("list")
				.executes(CompanionCommand::list))
			.then(CommandManager.literal("colony")
				.executes(CompanionCommand::colony)
				.then(CommandManager.literal("want")
					.then(CommandManager.argument("item", StringArgumentType.word())
						.suggests((ctx, b) -> CommandSource.suggestMatching(com.botcraft.colony.StockTargets.DEFAULTS.stream()
							.map(com.botcraft.colony.StockTargets.StockTarget::key), b))
						.then(CommandManager.argument("count", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 100000))
							.executes(CompanionCommand::colonyWant))))
				.then(CommandManager.literal("zone")
					.then(CommandManager.argument("kind", StringArgumentType.word())
						.suggests((ctx, b) -> CommandSource.suggestMatching(com.botcraft.colony.Zones.KINDS, b))
						.then(CommandManager.literal("here")
							.executes(ctx -> colonyZone(ctx, -1))
							.then(CommandManager.argument("size", com.mojang.brigadier.arguments.IntegerArgumentType.integer(3, 129))
								.executes(ctx -> colonyZone(ctx, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "size")))))
						.then(CommandManager.literal("clear")
							.executes(CompanionCommand::colonyZoneClear)))))
			.then(CommandManager.literal("why")
				.then(CommandManager.argument("name", StringArgumentType.word())
					.suggests(COMPANION_NAMES)
					.executes(CompanionCommand::why)))
			.then(CommandManager.literal("stop")
				.then(CommandManager.argument("name", StringArgumentType.word())
					.suggests(COMPANION_NAMES)
					.executes(ctx -> task(ctx, "stay"))))
			.then(CommandManager.literal("task")
				.then(CommandManager.argument("name", StringArgumentType.word())
					.suggests(COMPANION_NAMES)
					.then(CommandManager.argument("task", StringArgumentType.greedyString())
						.suggests(TASKS)
						.executes(ctx -> task(ctx, StringArgumentType.getString(ctx, "task")))))));
	}

	private static CompanionManager manager(CommandContext<ServerCommandSource> ctx) {
		CompanionManager m = CompanionManager.get();
		if (m == null) {
			throw new IllegalStateException("Botcraft is not running on this server");
		}
		return m;
	}

	private static int spawn(CommandContext<ServerCommandSource> ctx, String skin) {
		ServerCommandSource src = ctx.getSource();
		String name = StringArgumentType.getString(ctx, "name");
		CompanionManager m = manager(ctx);
		ServerPlayerEntity owner = src.getPlayer();
		Vec3d pos = src.getPosition();
		float yaw = 0.0F;
		if (owner != null) {
			// Appear a couple of blocks in front of the owner, facing them.
			Vec3d look = Vec3d.fromPolar(0.0F, owner.getYaw()).multiply(2.0);
			pos = owner.getPos().add(look.x, 0.0, look.z);
			if (!owner.getWorld().isSpaceEmpty(owner.getDimensions(owner.getPose()).getBoxAt(pos))) {
				pos = owner.getPos();
			}
			yaw = owner.getYaw() + 180.0F;
		}
		Companion existing = m.byName(name);
		if (existing != null && existing.isOnline()) {
			src.sendError(Text.literal(name + " is already here"));
			return 0;
		}
		Vec3d at = pos;
		float facing = yaw;
		var world = src.getWorld();
		// Skins of registered accounts are looked up asynchronously; spawning happens in the callback.
		SkinResolver.resolve(src.getServer(), existing != null ? null : skin, name, result -> {
			if (result.note() != null) {
				src.sendFeedback(() -> Text.literal(result.note()).formatted(Formatting.GRAY), false);
			}
			String error = m.spawn(name, result.choice(), owner, world, at, facing);
			if (error != null) {
				src.sendError(Text.literal(error));
			} else {
				src.sendFeedback(() -> Text.literal("Spawned companion " + name + " (skin: " + m.byName(name).data.skin + ")"), true);
			}
		});
		return 1;
	}

	/** Most companions one /companion batch spawns. */
	private static final int BATCH_MAX = 64;

	/**
	 * /companion batch <count>: spawn that many companions at once, with random two-word names
	 * ("AgentCreeper") and random default skins, spread around the player who asked.
	 */
	private static int batch(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		int count = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "count");
		CompanionManager m = manager(ctx);
		ServerPlayerEntity owner = src.getPlayer();
		var world = src.getWorld();
		Vec3d center = owner != null ? owner.getPos() : src.getPosition();
		java.util.Random random = new java.util.Random();
		List<String> spawned = new ArrayList<>();
		String error = null;
		for (int i = 0; i < count; i++) {
			String name = com.botcraft.entity.BatchNames.next(random,
				n -> m.byName(n) != null || src.getServer().getPlayerManager().getPlayer(n) != null);
			// A ring around the player, a little further out for bigger batches, on free ground.
			double angle = random.nextDouble() * Math.PI * 2;
			double r = 2.0 + random.nextDouble() * Math.min(6.0, 1.0 + count / 8.0);
			Vec3d at = center.add(Math.cos(angle) * r, 0, Math.sin(angle) * r);
			if (owner != null && !world.isSpaceEmpty(owner.getDimensions(owner.getPose()).getBoxAt(at))) {
				at = center;
			}
			float yaw = (float) Math.toDegrees(Math.atan2(center.z - at.z, center.x - at.x)) - 90.0F;
			error = m.spawn(name, com.botcraft.entity.SkinResolver.SkinChoice.random(), owner, world, at, yaw);
			if (error != null) {
				break;
			}
			spawned.add(name);
		}
		if (!spawned.isEmpty()) {
			String names = String.join(", ", spawned);
			src.sendFeedback(() -> Text.literal("Spawned " + spawned.size() + " companion(s): " + names), true);
		}
		if (error != null) {
			String why = error;
			src.sendError(Text.literal("Stopped after " + spawned.size() + ": " + why));
		}
		return spawned.size();
	}

	private static int skin(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		String name = StringArgumentType.getString(ctx, "name");
		String skin = StringArgumentType.getString(ctx, "skin");
		CompanionManager m = manager(ctx);
		Companion c = m.byName(name);
		if (c == null) {
			src.sendError(Text.literal("No companion named " + name));
			return 0;
		}
		SkinResolver.resolve(src.getServer(), skin, name, result -> {
			if (result.note() != null) {
				src.sendFeedback(() -> Text.literal(result.note()).formatted(Formatting.GRAY), false);
				if (!result.choice().hasTextures() && result.choice().defaultIndex() < 0) {
					return; // lookup failed: keep the current skin rather than randomising it
				}
			}
			String error = m.changeSkin(c, result.choice());
			if (error != null) {
				src.sendError(Text.literal(error));
			} else {
				src.sendFeedback(() -> Text.literal(name + " is now wearing " + c.data.skin), true);
			}
		});
		return 1;
	}

	private static int remove(CommandContext<ServerCommandSource> ctx) {
		String name = StringArgumentType.getString(ctx, "name");
		if (!manager(ctx).remove(name)) {
			ctx.getSource().sendError(Text.literal("No companion named " + name));
			return 0;
		}
		ctx.getSource().sendFeedback(() -> Text.literal("Removed companion " + name), true);
		return 1;
	}

	/** /companion chat: how much companions say right now. */
	private static int chatShow(CommandContext<ServerCommandSource> ctx) {
		CompanionConfig cfg = CompanionConfig.get();
		String level = !cfg.chatEnabled ? "off" : cfg.chatVerbosity;
		ctx.getSource().sendFeedback(() -> Text.literal("Companion chat: " + level + " (/companion chat all|quiet|off)"), false);
		return 1;
	}

	/**
	 * /companion chat all|quiet|off: everything; only answers, what they start and what failed;
	 * or nothing. Saved in the config.
	 */
	private static int chatSet(CommandContext<ServerCommandSource> ctx) {
		String level = StringArgumentType.getString(ctx, "level");
		CompanionConfig cfg = CompanionConfig.get();
		switch (level) {
			case "all", "quiet" -> {
				cfg.chatEnabled = true;
				cfg.chatVerbosity = level;
			}
			case "off" -> cfg.chatEnabled = false;
			default -> {
				ctx.getSource().sendError(Text.literal("Chat is all, quiet or off"));
				return 0;
			}
		}
		CompanionConfig.save();
		String what = switch (level) {
			case "quiet" -> "quiet: answers, what they start and what failed";
			case "off" -> "off: companions don't talk";
			default -> "all";
		};
		ctx.getSource().sendFeedback(() -> Text.literal("Companion chat: " + what), true);
		return 1;
	}

	/** /companion removeall: dismiss every companion (like /companion remove for each). */
	private static int removeAll(CommandContext<ServerCommandSource> ctx) {
		CompanionManager m = manager(ctx);
		List<String> names = new ArrayList<>();
		for (Companion c : m.all()) {
			names.add(c.name());
		}
		int removed = 0;
		for (String name : names) {
			if (m.remove(name)) {
				removed++;
			}
		}
		int n = removed;
		ctx.getSource().sendFeedback(() -> Text.literal(n == 0 ? "No companions to remove" : "Removed " + n + " companion(s)"), true);
		return removed;
	}

	/** Why a companion is doing what it's doing: its goal, the plan, the alternatives, what it has learned. */
	private static int why(CommandContext<ServerCommandSource> ctx) {
		String name = StringArgumentType.getString(ctx, "name");
		Companion c = manager(ctx).byName(name);
		if (c == null) {
			ctx.getSource().sendError(Text.literal("No companion named " + name));
			return 0;
		}
		var src = ctx.getSource();
		src.sendFeedback(() -> Text.literal(c.name() + " (" + c.data.mode + " mode): " + c.describeActivity()).formatted(Formatting.AQUA), false);
		if (!c.jobs().pending().isEmpty()) {
			src.sendFeedback(() -> Text.literal("  queued: " + String.join(" | ", c.jobs().pending())).formatted(Formatting.GRAY), false);
		}
		var d = c.life().lastDecision();
		if (d == null) {
			src.sendFeedback(() -> Text.literal("  no progression plan yet (free mode plans its next target)").formatted(Formatting.GRAY), false);
		} else {
			src.sendFeedback(() -> Text.literal("  target " + c.life().lastTarget() + " -> " + d.chosen().method() + " ("
				+ (d.chosen().possible() ? Math.round(d.chosen().cost()) + "s" : "impossible") + ", " + d.chosen().note() + ")").formatted(Formatting.WHITE), false);
			if (!d.chosen().steps().isEmpty()) {
				src.sendFeedback(() -> Text.literal("    plan: " + String.join(", then ", d.chosen().steps())).formatted(Formatting.GRAY), false);
			}
			for (var o : d.considered()) {
				if (o != d.chosen()) {
					src.sendFeedback(() -> Text.literal("    also: " + o.method() + " " + (o.possible() ? Math.round(o.cost()) + "s" : "no")
						+ " (" + o.note() + ")").formatted(Formatting.DARK_GRAY), false);
				}
			}
		}
		var request = com.botcraft.brain.Board.holding(c.name());
		if (request != null) {
			var mine = request.bids.stream().filter(b -> b.who().equals(c.name())).findFirst();
			src.sendFeedback(() -> Text.literal("  board: #" + request.id + " " + request.describe() + " (" + request.note + ")"
				+ mine.map(b -> ", bid " + b.explain()).orElse("")).formatted(Formatting.WHITE), false);
		}
		var learned = com.botcraft.brain.plan.MethodStats.summary(c);
		if (!learned.isEmpty()) {
			src.sendFeedback(() -> Text.literal("  learned:").formatted(Formatting.GRAY), false);
			for (String line : learned) {
				src.sendFeedback(() -> Text.literal("    " + line).formatted(Formatting.DARK_GRAY), false);
			}
		}
		return 1;
	}

	/** The shared base: where it is, its chests, and what's stored. */
	private static int colony(CommandContext<ServerCommandSource> ctx) {
		var colony = com.botcraft.colony.Colony.get();
		if (colony == null || !colony.hasBase()) {
			ctx.getSource().sendFeedback(() -> Text.literal("No base yet. Stand somewhere and say \"everyone, this is home\"."), false);
			return 0;
		}
		var base = colony.base();
		ctx.getSource().sendFeedback(() -> Text.literal("Base at " + base.toShortString() + ", " + colony.chests().size() + " chest(s)")
			.formatted(Formatting.AQUA), false);
		for (String line : colony.describeChests()) {
			ctx.getSource().sendFeedback(() -> Text.literal("  chest " + line).formatted(Formatting.GRAY), false);
		}
		if (colony.zones().isEmpty()) {
			ctx.getSource().sendFeedback(() -> Text.literal("Zones: not planned yet (the base map needs a few seconds).").formatted(Formatting.GRAY), false);
		} else {
			ctx.getSource().sendFeedback(() -> Text.literal("Zones (/companion colony zone <kind> here|clear):").formatted(Formatting.AQUA), false);
			for (var z : colony.zones()) {
				ctx.getSource().sendFeedback(() -> Text.literal("  " + z).formatted(Formatting.GRAY), false);
			}
		}
		String upkeep = com.botcraft.colony.Upkeep.summary();
		ctx.getSource().sendFeedback(() -> Text.literal("Upkeep: " + (upkeep == null ? "nothing to tidy" : upkeep)
			+ "; " + com.botcraft.colony.Builds.recordedCount() + " built block(s) on record").formatted(Formatting.GRAY), false);
		var stock = new java.util.ArrayList<>(colony.stock().entrySet());
		stock.sort((a, b) -> b.getValue() - a.getValue());
		if (stock.isEmpty()) {
			ctx.getSource().sendFeedback(() -> Text.literal("Nothing stored yet.").formatted(Formatting.GRAY), false);
		}
		for (var e : stock.subList(0, Math.min(20, stock.size()))) {
			String line = e.getValue() + " x " + com.botcraft.knowledge.ItemNames.display(e.getKey());
			ctx.getSource().sendFeedback(() -> Text.literal("  " + line).formatted(Formatting.GRAY), false);
		}
		if (stock.size() > 20) {
			ctx.getSource().sendFeedback(() -> Text.literal("  ... and " + (stock.size() - 20) + " more").formatted(Formatting.GRAY), false);
		}
		// Who's where doing what (what the companions see of each other).
		var m = com.botcraft.CompanionManager.get();
		var everyone = com.botcraft.brain.Blackboard.all(m == null ? 0 : m.tickCount());
		if (!everyone.isEmpty()) {
			ctx.getSource().sendFeedback(() -> Text.literal("Companions:").formatted(Formatting.AQUA), false);
			for (var e : everyone) {
				int d = (int) Math.round(Math.sqrt(e.feet().getSquaredDistance(base)));
				String line = e.name() + ": " + e.doing() + " (" + (e.dim().equals(colony.world().getRegistryKey().getValue().toString())
					? d + " blocks from the base" : "in " + e.dim().replace("minecraft:", "")) + ")";
				ctx.getSource().sendFeedback(() -> Text.literal("  " + line).formatted(Formatting.GRAY), false);
			}
		}
		// What the base should keep, and the board: who's getting what, and why them.
		ctx.getSource().sendFeedback(() -> Text.literal("Stock targets (/companion colony want <item> <count>):").formatted(Formatting.AQUA), false);
		for (var t : com.botcraft.colony.StockTargets.active(colony)) {
			int want = t.want(colony);
			int have = colony.count(t.counts());
			String line = "  " + t.label() + ": " + have + "/" + want + (want == 0 ? " (not collected)" : have >= want ? " ok" : " short " + (want - have));
			ctx.getSource().sendFeedback(() -> Text.literal(line).formatted(have >= want ? Formatting.GRAY : Formatting.YELLOW), false);
		}
		var requests = com.botcraft.brain.Board.requests();
		ctx.getSource().sendFeedback(() -> Text.literal(requests.isEmpty() ? "Request board: empty" : "Request board:").formatted(Formatting.AQUA), false);
		for (var r : requests.subList(0, Math.min(12, requests.size()))) {
			String who = r.takenBy != null ? " -> " + r.takenBy : "";
			String line = "  #" + r.id + " " + r.describe() + " [" + r.state.name().toLowerCase() + who + "]" + (r.note.isEmpty() ? "" : " " + r.note);
			ctx.getSource().sendFeedback(() -> Text.literal(line).formatted(r.state == com.botcraft.brain.Board.State.TAKEN ? Formatting.WHITE
				: r.state == com.botcraft.brain.Board.State.OPEN ? Formatting.YELLOW : Formatting.DARK_GRAY), false);
			if (r.state == com.botcraft.brain.Board.State.TAKEN) {
				for (var b : r.bids.subList(0, Math.min(3, r.bids.size()))) {
					String bid = "      " + b.who() + " " + b.explain() + (b.method().isEmpty() ? "" : " by " + b.method());
					ctx.getSource().sendFeedback(() -> Text.literal(bid).formatted(Formatting.DARK_GRAY), false);
				}
			}
		}
		return 1;
	}

	/**
	 * /companion colony zone <kind> here [size]: set a base zone by hand, centred where the player
	 * stands (a square; the bed row is two deep along the way the player faces). Planning never
	 * replaces it.
	 */
	private static int colonyZone(CommandContext<ServerCommandSource> ctx, int size) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		var colony = com.botcraft.colony.Colony.get();
		String kind = StringArgumentType.getString(ctx, "kind");
		if (colony == null || !colony.hasBase()) {
			ctx.getSource().sendError(Text.literal("No base yet. Stand somewhere and say \"everyone, this is home\"."));
			return 0;
		}
		if (!com.botcraft.colony.Zones.KINDS.contains(kind)) {
			ctx.getSource().sendError(Text.literal("Zones are " + String.join(", ", com.botcraft.colony.Zones.KINDS)));
			return 0;
		}
		ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
		net.minecraft.util.math.BlockPos at = player.getBlockPos();
		int n = size > 0 ? size : kind.equals(com.botcraft.colony.Zones.CORE) ? 7 : kind.equals(com.botcraft.colony.Zones.BEDS) ? 11
			: kind.equals(com.botcraft.colony.Zones.HOME) ? 2 * com.botcraft.colony.Zones.HOME_MIN_HALF + 1 : 9;
		if (!kind.equals(com.botcraft.colony.Zones.HOME)) {
			n = Math.min(n, 31); // only home is big
		}
		int h = n / 2;
		com.botcraft.colony.Colony.Zone z;
		if (kind.equals(com.botcraft.colony.Zones.BEDS)) {
			boolean alongX = player.getHorizontalFacing().getAxis() == net.minecraft.util.math.Direction.Axis.X;
			z = alongX ? new com.botcraft.colony.Colony.Zone(kind, at.getX() - h, at.getZ(), at.getX() + h, at.getZ() + 1, at.getY(), true)
				: new com.botcraft.colony.Colony.Zone(kind, at.getX(), at.getZ() - h, at.getX() + 1, at.getZ() + h, at.getY(), true);
		} else {
			z = new com.botcraft.colony.Colony.Zone(kind, at.getX() - h, at.getZ() - h, at.getX() + h, at.getZ() + h, at.getY(), true);
		}
		colony.setZone(z);
		ctx.getSource().sendFeedback(() -> Text.literal("Zone set: " + z), true);
		return 1;
	}

	/** /companion colony zone <kind> clear: back to being planned. */
	private static int colonyZoneClear(CommandContext<ServerCommandSource> ctx) {
		var colony = com.botcraft.colony.Colony.get();
		String kind = StringArgumentType.getString(ctx, "kind");
		if (colony == null) {
			return 0;
		}
		colony.clearZone(kind);
		ctx.getSource().sendFeedback(() -> Text.literal("Zone " + kind + " cleared; it will be planned again."), true);
		return 1;
	}

	/** /companion colony want <item> <count>: what the base should keep (0 = stop collecting it). */
	private static int colonyWant(CommandContext<ServerCommandSource> ctx) {
		var colony = com.botcraft.colony.Colony.get();
		if (colony == null || !colony.hasBase()) {
			ctx.getSource().sendError(Text.literal("No base yet. Stand somewhere and say \"everyone, this is home\"."));
			return 0;
		}
		String item = StringArgumentType.getString(ctx, "item");
		int n = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "count");
		var t = com.botcraft.colony.StockTargets.forPhrase(item.replace('_', ' '));
		if (t == null) {
			ctx.getSource().sendError(Text.literal("Unknown item: " + item));
			return 0;
		}
		colony.setWant(t.key(), n);
		ctx.getSource().sendFeedback(() -> Text.literal("The base will keep " + n + " " + t.label() + (n == 0 ? " (no longer collected)" : "")), true);
		return 1;
	}

	private static int list(CommandContext<ServerCommandSource> ctx) {
		CompanionManager m = manager(ctx);
		if (m.all().isEmpty()) {
			ctx.getSource().sendFeedback(() -> Text.literal("No companions. Try /companion spawn <name> [skin]"), false);
			return 0;
		}
		for (Companion c : m.all()) {
			String where;
			if (c.player() != null) {
				where = c.player().getBlockPos().toShortString() + " hp " + Math.round(c.player().getHealth())
					+ " food " + c.player().getHungerManager().getFoodLevel();
			} else {
				where = "offline";
			}
			String owner = c.data.ownerName != null ? c.data.ownerName : "nobody";
			Text line = Text.literal(c.name()).formatted(Formatting.AQUA)
				.append(Text.literal(" (owner " + owner + ") " + where + " - " + c.describeActivity()).formatted(Formatting.GRAY));
			ctx.getSource().sendFeedback(() -> line, false);
		}
		return m.all().size();
	}

	/** Debug view: personality, orders, plan, memory highlights. */
	private static int info(CommandContext<ServerCommandSource> ctx) {
		String name = StringArgumentType.getString(ctx, "name");
		Companion c = manager(ctx).byName(name);
		if (c == null) {
			ctx.getSource().sendError(Text.literal("No companion named " + name));
			return 0;
		}
		var d = c.data;
		List<String> lines = new ArrayList<>();
		lines.add(name + " (skin " + d.skin + ", owner " + (d.ownerName == null ? "nobody" : d.ownerName) + ")");
		lines.add(String.format("personality: chatty %.2f, brave %.2f, curious %.2f, helpful %.2f", d.chattiness, d.bravery, d.curiosity, d.helpfulness));
		lines.add("mode: " + d.mode + (d.defendOwner ? " + defend" : "") + "; doing: " + c.describeActivity());
		lines.add("queued: " + (c.jobs().pending().isEmpty() ? "nothing" : String.join(" | ", c.jobs().pending())));
		lines.add("drives: " + c.brain().lastScores());
		lines.add("memory: places " + c.memory().places.keySet() + ", " + c.memory().sightings.size() + " sightings, stats " + c.memory().stats);
		for (String l : lines) {
			ctx.getSource().sendFeedback(() -> Text.literal(l).formatted(Formatting.GRAY), false);
		}
		return 1;
	}

	private static int task(CommandContext<ServerCommandSource> ctx, String spec) {
		String name = StringArgumentType.getString(ctx, "name");
		Companion c = manager(ctx).byName(name);
		if (c == null) {
			ctx.getSource().sendError(Text.literal("No companion named " + name));
			return 0;
		}
		String error = CommandTasks.apply(c, spec, ctx.getSource().getPlayer());
		if (error != null) {
			ctx.getSource().sendError(Text.literal(error));
			return 0;
		}
		String msg = spec.trim().equalsIgnoreCase("status") ? c.describeActivity() : "ok (" + spec.trim() + ")";
		ctx.getSource().sendFeedback(() -> Text.literal(name + ": " + msg), false);
		return 1;
	}
}
