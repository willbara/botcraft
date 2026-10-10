package com.mcfriends.command;

import com.mcfriends.Companion;
import com.mcfriends.CompanionManager;
import com.mcfriends.chat.CommandTasks;
import com.mcfriends.config.CompanionConfig;
import com.mcfriends.entity.SkinResolver;
import com.mcfriends.entity.SkinUtil;
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
			.then(CommandManager.literal("info")
				.then(CommandManager.argument("name", StringArgumentType.word())
					.suggests(COMPANION_NAMES)
					.executes(CompanionCommand::info)))
			.then(CommandManager.literal("list")
				.executes(CompanionCommand::list))
			.then(CommandManager.literal("colony")
				.executes(CompanionCommand::colony))
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
			throw new IllegalStateException("MCFriends is not running on this server");
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
		var learned = com.mcfriends.brain.plan.MethodStats.summary(c);
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
		var colony = com.mcfriends.colony.Colony.get();
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
		var stock = new java.util.ArrayList<>(colony.stock().entrySet());
		stock.sort((a, b) -> b.getValue() - a.getValue());
		if (stock.isEmpty()) {
			ctx.getSource().sendFeedback(() -> Text.literal("Nothing stored yet.").formatted(Formatting.GRAY), false);
		}
		for (var e : stock.subList(0, Math.min(20, stock.size()))) {
			String line = e.getValue() + " x " + com.mcfriends.knowledge.ItemNames.display(e.getKey());
			ctx.getSource().sendFeedback(() -> Text.literal("  " + line).formatted(Formatting.GRAY), false);
		}
		if (stock.size() > 20) {
			ctx.getSource().sendFeedback(() -> Text.literal("  ... and " + (stock.size() - 20) + " more").formatted(Formatting.GRAY), false);
		}
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
