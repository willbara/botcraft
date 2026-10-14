package com.botcraft.command;

import com.botcraft.Companion;
import com.botcraft.CompanionManager;
import com.botcraft.brain.Personality;
import com.botcraft.colony.Colony;
import com.botcraft.config.CompanionConfig;
import com.botcraft.faction.Faction;
import com.botcraft.faction.Factions;
import com.botcraft.faction.Standing;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /companion faction ... (groups, teams, relations, warfare), /companion standing (what a faction
 * thinks of a player), /companion archetype and /companion trait (personality).
 */
public final class FactionCommand {
	private FactionCommand() {
	}

	private static final SuggestionProvider<ServerCommandSource> FACTIONS = (ctx, b) ->
		CommandSource.suggestMatching(Factions.ids(), b);
	private static final SuggestionProvider<ServerCommandSource> COMPANIONS = (ctx, b) -> {
		CompanionManager m = CompanionManager.get();
		List<String> names = new ArrayList<>();
		if (m != null) {
			for (Companion c : m.all()) {
				names.add(c.name());
			}
		}
		return CommandSource.suggestMatching(names, b);
	};
	private static final SuggestionProvider<ServerCommandSource> PLAYERS = (ctx, b) ->
		CommandSource.suggestMatching(ctx.getSource().getServer().getPlayerNames(), b);

	public static LiteralArgumentBuilder<ServerCommandSource> faction() {
		return CommandManager.literal("faction")
			.executes(FactionCommand::list)
			.then(CommandManager.literal("mode")
				.then(CommandManager.argument("mode", StringArgumentType.word())
					.suggests((ctx, b) -> CommandSource.suggestMatching(List.of("single", "teams", "dynamic"), b))
					.executes(FactionCommand::mode)))
			.then(CommandManager.literal("warfare")
				.then(CommandManager.argument("level", StringArgumentType.word())
					.suggests((ctx, b) -> CommandSource.suggestMatching(List.of("off", "defend", "pvp", "raid"), b))
					.executes(ctx -> warfare(ctx, null))
					.then(CommandManager.argument("faction", StringArgumentType.word())
						.suggests(FACTIONS)
						.executes(ctx -> warfare(ctx, StringArgumentType.getString(ctx, "faction"))))))
			.then(CommandManager.literal("teams")
				.then(CommandManager.argument("teams", StringArgumentType.greedyString())
					.suggests((ctx, b) -> CommandSource.suggestMatching(List.of("red blue", "red blue owner", "red blue green yellow"), b))
					.executes(FactionCommand::teams)))
			.then(CommandManager.literal("create")
				.then(CommandManager.argument("id", StringArgumentType.word())
					.executes(ctx -> create(ctx, ""))
					.then(CommandManager.argument("color", StringArgumentType.word())
						.suggests((ctx, b) -> CommandSource.suggestMatching(Factions.COLORS, b))
						.executes(ctx -> create(ctx, StringArgumentType.getString(ctx, "color"))))))
			.then(CommandManager.literal("remove")
				.then(CommandManager.argument("id", StringArgumentType.word())
					.suggests(FACTIONS)
					.executes(FactionCommand::remove)))
			.then(CommandManager.literal("join")
				.then(CommandManager.argument("companion", StringArgumentType.word())
					.suggests(COMPANIONS)
					.then(CommandManager.argument("faction", StringArgumentType.word())
						.suggests(FACTIONS)
						.executes(FactionCommand::join))))
			.then(CommandManager.literal("member")
				.then(CommandManager.argument("faction", StringArgumentType.word())
					.suggests(FACTIONS)
					.then(CommandManager.argument("player", StringArgumentType.word())
						.suggests(PLAYERS)
						.then(CommandManager.argument("rank", StringArgumentType.word())
							.suggests((ctx, b) -> CommandSource.suggestMatching(List.of("member", "officer", "leader", "remove"), b))
							.executes(FactionCommand::member)))))
			.then(CommandManager.literal("relation")
				.then(CommandManager.argument("a", StringArgumentType.word())
					.suggests(FACTIONS)
					.then(CommandManager.argument("b", StringArgumentType.word())
						.suggests(FACTIONS)
						.then(CommandManager.argument("value", StringArgumentType.word())
							.suggests((ctx, b) -> CommandSource.suggestMatching(List.of("allied", "neutral", "hostile", "-50", "0", "75"), b))
							.executes(FactionCommand::relation)))))
			.then(CommandManager.literal("ceasefire")
				.executes(FactionCommand::ceasefire))
			.then(CommandManager.literal("info")
				.then(CommandManager.argument("faction", StringArgumentType.word())
					.suggests(FACTIONS)
					.executes(FactionCommand::info)));
	}

	public static LiteralArgumentBuilder<ServerCommandSource> standing() {
		return CommandManager.literal("standing")
			.then(CommandManager.argument("faction", StringArgumentType.word())
				.suggests(FACTIONS)
				.executes(ctx -> standing(ctx, null, null))
				.then(CommandManager.argument("player", StringArgumentType.word())
					.suggests(PLAYERS)
					.executes(ctx -> standing(ctx, StringArgumentType.getString(ctx, "player"), null))
					.then(CommandManager.argument("value", IntegerArgumentType.integer(-100, 100))
						.executes(ctx -> standing(ctx, StringArgumentType.getString(ctx, "player"), IntegerArgumentType.getInteger(ctx, "value"))))));
	}

	public static LiteralArgumentBuilder<ServerCommandSource> archetype() {
		return CommandManager.literal("archetype")
			.then(CommandManager.argument("name", StringArgumentType.word())
				.suggests(COMPANIONS)
				.then(CommandManager.argument("archetype", StringArgumentType.word())
					.suggests((ctx, b) -> CommandSource.suggestMatching(Personality.ARCHETYPES.keySet(), b))
					.executes(FactionCommand::archetypeSet)));
	}

	public static LiteralArgumentBuilder<ServerCommandSource> trait() {
		return CommandManager.literal("trait")
			.then(CommandManager.argument("name", StringArgumentType.word())
				.suggests(COMPANIONS)
				.then(CommandManager.argument("trait", StringArgumentType.word())
					.suggests((ctx, b) -> {
						List<String> all = new ArrayList<>(List.of("chattiness", "bravery", "curiosity", "helpfulness"));
						all.addAll(Personality.EXTRA);
						return CommandSource.suggestMatching(all, b);
					})
					.then(CommandManager.argument("value", FloatArgumentType.floatArg(0.0F, 1.0F))
						.executes(FactionCommand::traitSet))));
	}

	// ---- handlers ---------------------------------------------------------------------------------

	private static void say(CommandContext<ServerCommandSource> ctx, String line, Formatting f) {
		ctx.getSource().sendFeedback(() -> Text.literal(line).formatted(f), false);
	}

	private static int list(CommandContext<ServerCommandSource> ctx) {
		say(ctx, "Factions (mode " + Factions.mode() + ", warfare " + CompanionConfig.get().warfare + ", owner authority "
			+ CompanionConfig.get().ownerAuthority + "):", Formatting.AQUA);
		for (Faction f : Factions.all()) {
			int n = Factions.members(f.id).size();
			say(ctx, "  " + f.id + " \"" + f.name + "\" (" + f.color + "): " + n + " companion(s), " + f.members.size() + " player member(s), warfare "
				+ Factions.warfare(f), Formatting.GRAY);
		}
		if (!Factions.enabled()) {
			say(ctx, "  Single mode: everyone is in one colony. /companion faction teams red blue splits them up.", Formatting.DARK_GRAY);
		}
		return 1;
	}

	private static int mode(CommandContext<ServerCommandSource> ctx) {
		String mode = StringArgumentType.getString(ctx, "mode").toLowerCase(Locale.ROOT);
		if (!List.of("single", "teams", "dynamic").contains(mode)) {
			ctx.getSource().sendError(Text.literal("Modes are single, teams and dynamic"));
			return 0;
		}
		CompanionConfig.get().factionMode = mode;
		CompanionConfig.save();
		Factions.syncTeams();
		reconsiderAll();
		say(ctx, "Faction mode: " + mode + (mode.equals("dynamic") ? " (factions fight and raid on their own)" : ""), Formatting.WHITE);
		return 1;
	}

	private static int warfare(CommandContext<ServerCommandSource> ctx, String factionId) {
		String level = StringArgumentType.getString(ctx, "level").toLowerCase(Locale.ROOT);
		if (!List.of("off", "defend", "pvp", "raid").contains(level)) {
			ctx.getSource().sendError(Text.literal("Warfare is off, defend, pvp or raid"));
			return 0;
		}
		if (factionId == null) {
			CompanionConfig.get().warfare = level;
			CompanionConfig.save();
			say(ctx, "Warfare for everyone: " + level + (Factions.dynamic() ? " (dynamic factions still raid)" : ""), Formatting.WHITE);
		} else {
			Faction f = Factions.find(factionId);
			if (f == null) {
				ctx.getSource().sendError(Text.literal("No faction " + factionId));
				return 0;
			}
			f.warfare = level;
			Factions.markDirty();
			say(ctx, "Warfare for " + f.name + ": " + level, Formatting.WHITE);
		}
		return 1;
	}

	private static int teams(CommandContext<ServerCommandSource> ctx) {
		List<String> words = new ArrayList<>(Arrays.asList(StringArgumentType.getString(ctx, "teams").trim().toLowerCase(Locale.ROOT).split("\\s+")));
		String how = "even";
		if (!words.isEmpty() && (words.get(words.size() - 1).equals("owner") || words.get(words.size() - 1).equals("even"))) {
			how = words.remove(words.size() - 1);
		}
		words.removeIf(w -> !w.matches("[a-z0-9_]{1,12}") || w.equals(Factions.DEFAULT));
		if (words.size() < 2) {
			ctx.getSource().sendError(Text.literal("Name at least two teams, e.g. /companion faction teams red blue"));
			return 0;
		}
		if (!Factions.enabled()) {
			CompanionConfig.get().factionMode = "teams";
			CompanionConfig.save();
		}
		List<String> ids = Factions.makeTeams(words, how, ctx.getSource().getServer().getOverworld().getTime());
		reconsiderAll();
		for (String id : ids) {
			List<String> names = Factions.members(id).stream().map(Companion::name).toList();
			say(ctx, "Team " + id + ": " + (names.isEmpty() ? "nobody yet" : String.join(", ", names)), Formatting.WHITE);
		}
		say(ctx, "Teams are enemies (warfare " + CompanionConfig.get().warfare + "). Each needs a base: tell a member \"this is home\", or let them find one.",
			Formatting.GRAY);
		return ids.size();
	}

	private static int create(CommandContext<ServerCommandSource> ctx, String color) {
		String id = StringArgumentType.getString(ctx, "id");
		Faction f = Factions.create(id, Character.toUpperCase(id.charAt(0)) + id.substring(1), color.isEmpty() ? "" : color,
			ctx.getSource().getServer().getOverworld().getTime());
		if (f == null) {
			ctx.getSource().sendError(Text.literal("Faction ids are 1-12 letters, digits or _, and must be new"));
			return 0;
		}
		if (!Factions.enabled()) {
			say(ctx, "Note: faction mode is single, so it has no effect until /companion faction mode teams (or dynamic).", Formatting.YELLOW);
		}
		Factions.syncTeams();
		say(ctx, "Created faction " + f.id + " (" + f.color + ")", Formatting.WHITE);
		return 1;
	}

	private static int remove(CommandContext<ServerCommandSource> ctx) {
		String id = StringArgumentType.getString(ctx, "id");
		if (!Factions.remove(id)) {
			ctx.getSource().sendError(Text.literal("No faction " + id + " (the default colony can't be removed)"));
			return 0;
		}
		Colony.drop(id.toLowerCase(Locale.ROOT));
		reconsiderAll();
		say(ctx, "Removed faction " + id + "; its companions are back in the colony", Formatting.WHITE);
		return 1;
	}

	private static int join(CommandContext<ServerCommandSource> ctx) {
		String name = StringArgumentType.getString(ctx, "companion");
		Faction f = Factions.find(StringArgumentType.getString(ctx, "faction"));
		CompanionManager m = CompanionManager.get();
		Companion c = m == null ? null : m.byName(name);
		if (c == null || f == null) {
			ctx.getSource().sendError(Text.literal(c == null ? "No companion named " + name : "No such faction"));
			return 0;
		}
		Factions.join(c, f.id);
		Factions.markDirty();
		say(ctx, c.name() + " is now in " + f.name + (Factions.enabled() ? "" : " (takes effect outside single mode)"), Formatting.WHITE);
		return 1;
	}

	private static int member(CommandContext<ServerCommandSource> ctx) {
		Faction f = Factions.find(StringArgumentType.getString(ctx, "faction"));
		String playerName = StringArgumentType.getString(ctx, "player");
		String rank = StringArgumentType.getString(ctx, "rank").toLowerCase(Locale.ROOT);
		ServerPlayerEntity player = ctx.getSource().getServer().getPlayerManager().getPlayer(playerName);
		if (f == null || player == null) {
			ctx.getSource().sendError(Text.literal(f == null ? "No such faction" : "No player " + playerName + " online"));
			return 0;
		}
		String uuid = player.getUuidAsString();
		if (rank.equals("remove")) {
			f.members.remove(uuid);
		} else {
			Faction.Rank r;
			try {
				r = Faction.Rank.valueOf(rank.toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				ctx.getSource().sendError(Text.literal("Ranks are member, officer and leader (or remove)"));
				return 0;
			}
			// One faction at a time.
			for (Faction o : Factions.all()) {
				o.members.remove(uuid);
			}
			f.members.put(uuid, r);
			Factions.standing(f, player).set(Math.max(Factions.standing(f, player).value, CompanionConfig.get().orderStanding), Factions.thresholds());
		}
		Factions.markDirty();
		Factions.syncTeams();
		say(ctx, playerName + (rank.equals("remove") ? " left " : " is now " + rank + " of ") + f.name, Formatting.WHITE);
		return 1;
	}

	private static int relation(CommandContext<ServerCommandSource> ctx) {
		Faction a = Factions.find(StringArgumentType.getString(ctx, "a"));
		Faction b = Factions.find(StringArgumentType.getString(ctx, "b"));
		String v = StringArgumentType.getString(ctx, "value").toLowerCase(Locale.ROOT);
		if (a == null || b == null || a == b) {
			ctx.getSource().sendError(Text.literal("Name two different factions"));
			return 0;
		}
		int value;
		switch (v) {
			case "allied", "ally", "allies" -> value = 75;
			case "neutral", "peace" -> value = 0;
			case "hostile", "enemy", "enemies", "war" -> value = -60;
			default -> {
				try {
					value = Integer.parseInt(v);
				} catch (NumberFormatException e) {
					ctx.getSource().sendError(Text.literal("Use allied, neutral, hostile or a number from -100 to 100"));
					return 0;
				}
			}
		}
		Factions.setRelation(a.id, b.id, value);
		say(ctx, a.name + " and " + b.name + ": " + value + (Factions.hostile(a.id, b.id) ? " (enemies)" : Factions.friendly(a.id, b.id) ? " (allies)" : ""),
			Formatting.WHITE);
		return 1;
	}

	private static int ceasefire(CommandContext<ServerCommandSource> ctx) {
		List<String> ids = Factions.ids();
		for (int i = 0; i < ids.size(); i++) {
			for (int j = i + 1; j < ids.size(); j++) {
				if (Factions.hostile(ids.get(i), ids.get(j))) {
					Factions.setRelation(ids.get(i), ids.get(j), 0);
				}
			}
		}
		for (Faction f : Factions.all()) {
			for (Standing s : f.players.values()) {
				if (s.band == Standing.Band.HOSTILE) {
					s.set(0, Factions.thresholds());
				}
			}
		}
		Factions.markDirty();
		CompanionManager m = CompanionManager.get();
		if (m != null) {
			for (Companion c : m.all()) {
				c.brain().clearAll();
			}
		}
		say(ctx, "Ceasefire: every faction and player is back to neutral", Formatting.WHITE);
		return 1;
	}

	private static int info(CommandContext<ServerCommandSource> ctx) {
		Faction f = Factions.find(StringArgumentType.getString(ctx, "faction"));
		if (f == null) {
			ctx.getSource().sendError(Text.literal("No such faction"));
			return 0;
		}
		say(ctx, f.name + " (" + f.id + ", " + f.color + "), warfare " + Factions.warfare(f), Formatting.AQUA);
		List<String> names = Factions.members(f.id).stream().map(Companion::name).toList();
		say(ctx, "  companions: " + (names.isEmpty() ? "none" : String.join(", ", names)), Formatting.GRAY);
		if (!f.members.isEmpty()) {
			List<String> ps = new ArrayList<>();
			for (Map.Entry<String, Faction.Rank> e : f.members.entrySet()) {
				Standing s = f.players.get(e.getKey());
				ps.add((s == null ? e.getKey().substring(0, 8) : s.name) + " (" + e.getValue().word() + ")");
			}
			say(ctx, "  players: " + String.join(", ", ps), Formatting.GRAY);
		}
		Colony colony = Colony.of(f.id);
		say(ctx, "  base: " + (colony == null || !colony.hasBase() ? "none yet" : colony.base().toShortString() + ", " + colony.chests().size() + " chest(s)"),
			Formatting.GRAY);
		for (Faction o : Factions.all()) {
			if (o != f && (f.relation(o.id) != 0 || o.relation(f.id) != 0)) {
				say(ctx, "  " + o.name + ": " + f.relation(o.id) + (Factions.hostile(f.id, o.id) ? " (enemies)" : Factions.friendly(f.id, o.id) ? " (allies)" : ""),
					Formatting.GRAY);
			}
		}
		if (!f.players.isEmpty()) {
			say(ctx, "  standing:", Formatting.GRAY);
			for (Standing s : f.players.values()) {
				say(ctx, "    " + s.summary(), s.band == Standing.Band.HOSTILE ? Formatting.RED : Formatting.DARK_GRAY);
			}
		}
		return 1;
	}

	private static int standing(CommandContext<ServerCommandSource> ctx, String playerName, Integer value) {
		Faction f = Factions.find(StringArgumentType.getString(ctx, "faction"));
		if (f == null) {
			ctx.getSource().sendError(Text.literal("No such faction"));
			return 0;
		}
		if (playerName == null) {
			if (f.players.isEmpty()) {
				say(ctx, f.name + " hasn't met anyone yet", Formatting.GRAY);
			}
			for (Standing s : f.players.values()) {
				say(ctx, s.summary(), s.band == Standing.Band.HOSTILE ? Formatting.RED : Formatting.GRAY);
			}
			return f.players.size();
		}
		ServerPlayerEntity player = ctx.getSource().getServer().getPlayerManager().getPlayer(playerName);
		if (player == null) {
			for (Standing s : f.players.values()) {
				if (s.name.equalsIgnoreCase(playerName)) {
					if (value != null) {
						s.set(value, Factions.thresholds());
						Factions.markDirty();
					}
					say(ctx, f.name + ": " + s.summary(), Formatting.WHITE);
					return 1;
				}
			}
			ctx.getSource().sendError(Text.literal(f.name + " doesn't know " + playerName));
			return 0;
		}
		Standing s = Factions.standing(f, player);
		if (value != null) {
			s.set(value, Factions.thresholds());
			Factions.markDirty();
		}
		say(ctx, f.name + ": " + s.summary(), Formatting.WHITE);
		return 1;
	}

	private static int archetypeSet(CommandContext<ServerCommandSource> ctx) {
		String name = StringArgumentType.getString(ctx, "name");
		String type = StringArgumentType.getString(ctx, "archetype");
		CompanionManager m = CompanionManager.get();
		Companion c = m == null ? null : m.byName(name);
		if (c == null) {
			ctx.getSource().sendError(Text.literal("No companion named " + name));
			return 0;
		}
		if (!Personality.applyArchetype(c.data, type)) {
			ctx.getSource().sendError(Text.literal("Archetypes: " + String.join(", ", Personality.ARCHETYPES.keySet())));
			return 0;
		}
		c.brain().reconsider();
		say(ctx, c.name() + " is now a " + type + ": " + c.brain().personality().summary(), Formatting.WHITE);
		return 1;
	}

	private static int traitSet(CommandContext<ServerCommandSource> ctx) {
		String name = StringArgumentType.getString(ctx, "name");
		String trait = StringArgumentType.getString(ctx, "trait").toLowerCase(Locale.ROOT);
		float value = FloatArgumentType.getFloat(ctx, "value");
		CompanionManager m = CompanionManager.get();
		Companion c = m == null ? null : m.byName(name);
		if (c == null) {
			ctx.getSource().sendError(Text.literal("No companion named " + name));
			return 0;
		}
		if (!Personality.set(c.data, trait, value)) {
			ctx.getSource().sendError(Text.literal("No trait " + trait));
			return 0;
		}
		say(ctx, c.name() + ": " + c.brain().personality().summary(), Formatting.WHITE);
		return 1;
	}

	private static void reconsiderAll() {
		CompanionManager m = CompanionManager.get();
		if (m != null) {
			for (Companion c : m.all()) {
				c.brain().reconsider();
			}
		}
	}
}
