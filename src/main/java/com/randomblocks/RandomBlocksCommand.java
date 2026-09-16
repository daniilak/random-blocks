package com.randomblocks;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.border.WorldBorder;

import java.util.List;
import java.util.Locale;

public final class RandomBlocksCommand {
	private static final int MAX_AROUND = 32;
	private static final int MAX_BORDER_CHUNKS = 512;
	private static final List<String> SCALE_OPTIONS = List.of("1", "2", "4", "8", "16", "chunk");
	private static final SuggestionProvider<CommandSourceStack> SCALE_SUGGESTIONS =
			(ctx, builder) -> SharedSuggestionProvider.suggest(SCALE_OPTIONS, builder);

	private RandomBlocksCommand() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(Commands.literal("randomblocks")
					.executes(ctx -> runWorld(ctx.getSource()))
					.then(Commands.literal("world")
							.executes(ctx -> runWorld(ctx.getSource())))
					.then(Commands.literal("around")
							.then(Commands.argument("radius", IntegerArgumentType.integer(1, MAX_AROUND))
									.executes(ctx -> runRadius(
											ctx.getSource(),
											IntegerArgumentType.getInteger(ctx, "radius")
									))))
					.then(Commands.literal("border")
							.then(Commands.argument("chunks", IntegerArgumentType.integer(1, MAX_BORDER_CHUNKS))
									.executes(ctx -> setBorder(
											ctx.getSource(),
											IntegerArgumentType.getInteger(ctx, "chunks")
									))))
					.then(Commands.literal("pregen")
							.executes(ctx -> startPregen(ctx.getSource(), borderRadiusChunks(ctx.getSource().getLevel())))
							.then(Commands.argument("chunks", IntegerArgumentType.integer(1, MAX_BORDER_CHUNKS))
									.executes(ctx -> startPregen(
											ctx.getSource(),
											IntegerArgumentType.getInteger(ctx, "chunks")
									))))
					.then(Commands.literal("scale")
							.executes(ctx -> showScale(ctx.getSource()))
							.then(Commands.argument("size", StringArgumentType.word())
									.suggests(SCALE_SUGGESTIONS)
									.executes(ctx -> setScale(
											ctx.getSource(),
											StringArgumentType.getString(ctx, "size")
									))))
					.then(Commands.literal("auto")
							.executes(ctx -> showAuto(ctx.getSource()))
							.then(Commands.argument("enabled", BoolArgumentType.bool())
									.executes(ctx -> setAuto(
											ctx.getSource(),
											BoolArgumentType.getBool(ctx, "enabled")
									)))));
		});
	}

	private static int runWorld(CommandSourceStack source) {
		ServerLevel level = source.getLevel();
		RandomBlocksState.get(level).setAutoEnabled(true);
		int queued = RandomBlocksMod.enqueueLoadedChunks(level, new ChunkPos(BlockPos.containing(source.getPosition())));
		source.sendSuccess(
				() -> Component.literal(
						"В очереди " + queued + " загруженных чанков. Новые — по мере исследования. Рамка: /randomblocks border 32. Преген: /randomblocks pregen"
				),
				true
		);
		return Command.SINGLE_SUCCESS;
	}

	private static int runRadius(CommandSourceStack source, int radius) {
		ServerLevel level = source.getLevel();
		BlockPos center = BlockPos.containing(source.getPosition());
		int replaced = RandomBlocksApplier.applyRadius(level, center, radius);
		source.sendSuccess(
				() -> Component.literal("Случайные блоки: заменено " + replaced + " в радиусе " + radius + "."),
				true
		);
		return Command.SINGLE_SUCCESS;
	}

	private static int setBorder(CommandSourceStack source, int chunkRadius) {
		ServerLevel level = source.getLevel();
		BlockPos spawn = level.getSharedSpawnPos();
		WorldBorder border = level.getWorldBorder();
		border.setCenter(spawn.getX() + 0.5, spawn.getZ() + 0.5);
		border.setSize(chunkRadius * 16.0 * 2.0);
		border.setWarningBlocks(8);
		int blocks = chunkRadius * 16;
		source.sendSuccess(
				() -> Component.literal(
						"Граница мира: радиус " + chunkRadius + " чанков (" + blocks
								+ " блоков от спавна). Это ванильный worldborder, отдельный мод-барьер не нужен. Преген: /randomblocks pregen " + chunkRadius
				),
				true
		);
		return Command.SINGLE_SUCCESS;
	}

	private static int startPregen(CommandSourceStack source, int chunkRadius) {
		int total = (2 * chunkRadius + 1) * (2 * chunkRadius + 1);
		int seconds = Math.max(1, total / 40);
		int queued = RandomBlocksMod.startPregen(source.getLevel(), chunkRadius);
		source.sendSuccess(
				() -> Component.literal(
						"Преген " + queued + " чанков (квадрат " + (2 * chunkRadius + 1) + "×" + (2 * chunkRadius + 1)
								+ "). Ориентир: ~" + formatDuration(seconds)
								+ ". 500 чанков радиуса ≈ миллион чанков и много часов. Играйте, очередь идёт в фоне."
				),
				true
		);
		return Command.SINGLE_SUCCESS;
	}

	private static int borderRadiusChunks(ServerLevel level) {
		double diameterBlocks = level.getWorldBorder().getSize();
		int radius = (int) Math.round(diameterBlocks / 32.0);
		return Math.max(1, Math.min(MAX_BORDER_CHUNKS, radius));
	}

	private static String formatDuration(int seconds) {
		if (seconds < 90) {
			return seconds + " сек";
		}
		if (seconds < 3600) {
			return (seconds / 60) + " мин";
		}
		return (seconds / 3600) + " ч " + ((seconds % 3600) / 60) + " мин";
	}

	private static int showAuto(CommandSourceStack source) {
		boolean on = RandomBlocksState.get(source.getLevel()).isAutoEnabled();
		source.sendSuccess(
				() -> Component.literal("Авторандом чанков: " + (on ? "включён" : "выключен") + ". Переключить: /randomblocks auto true|false"),
				false
		);
		return Command.SINGLE_SUCCESS;
	}

	private static int setAuto(CommandSourceStack source, boolean enabled) {
		RandomBlocksState.get(source.getLevel()).setAutoEnabled(enabled);
		source.sendSuccess(
				() -> Component.literal("Авторандом чанков: " + (enabled ? "включён" : "выключен") + "."),
				true
		);
		return Command.SINGLE_SUCCESS;
	}

	private static int showScale(CommandSourceStack source) {
		int scale = RandomBlocksState.get(source.getLevel()).getScale();
		source.sendSuccess(
				() -> Component.literal(
						"Масштаб замены: " + RandomBlocksState.formatScale(scale)
								+ ". Варианты: /randomblocks scale 1|2|4|8|16|chunk"
				),
				false
		);
		return Command.SINGLE_SUCCESS;
	}

	private static int setScale(CommandSourceStack source, String raw) {
		Integer parsed = parseScale(raw);
		if (parsed == null) {
			source.sendFailure(Component.literal("Неизвестный масштаб. Используй: 1, 2, 4, 8, 16 или chunk"));
			return 0;
		}

		RandomBlocksState state = RandomBlocksState.get(source.getLevel());
		state.setScale(parsed);
		source.sendSuccess(
				() -> Component.literal(
						"Масштаб замены: " + RandomBlocksState.formatScale(parsed)
								+ ". Уже обработанные чанки не меняются сами — снова /randomblocks или исследуй новые."
				),
				true
		);
		return Command.SINGLE_SUCCESS;
	}

	private static Integer parseScale(String raw) {
		String value = raw.toLowerCase(Locale.ROOT);
		if (value.equals("chunk") || value.equals("чанка") || value.equals("чанк")) {
			return RandomBlocksState.SCALE_CHUNK;
		}
		try {
			int n = Integer.parseInt(value);
			return switch (n) {
				case 1, 2, 4, 8, 16 -> n;
				default -> null;
			};
		} catch (NumberFormatException ignored) {
			return null;
		}
	}
}
