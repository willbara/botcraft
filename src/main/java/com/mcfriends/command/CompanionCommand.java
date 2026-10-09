package com.mcfriends.command;

import com.mcfriends.Companion;
import com.mcfriends.CompanionManager;
import com.mcfriends.chat.CommandTasks;
import com.mcfriends.config.CompanionConfig;
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
			.then(CommandManager.literal("remove")
				.then(CommandManager.argument("name", StringArgumentType.word())
					.suggests(COMPANION_NAMES)
					.executes(CompanionCommand::remove)))
			.then(CommandManager.literal("list")
				.executes(CompanionCommand::list))
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
		String error = manager(ctx).spawn(name, skin, owner, src.getWorld(), pos, yaw);
		if (error != null) {
			src.sendError(Text.literal(error));
			return 0;
		}
		src.sendFeedback(() -> Text.literal("Spawned companion " + name), true);
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
