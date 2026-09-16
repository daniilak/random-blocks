package com.randomblocks;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.List;

public final class RandomBlocksApplier {
	/**
	 * Только стабильные полные кубы. Песок/гравий нельзя — сыпятся и ломают первый тик генерации.
	 */
	static final List<Block> WHITELIST = List.of(
			Blocks.STONE,
			Blocks.COBBLESTONE,
			Blocks.DIRT,
			Blocks.GRASS_BLOCK,
			Blocks.SANDSTONE,
			Blocks.GRANITE,
			Blocks.DIORITE,
			Blocks.ANDESITE,
			Blocks.DEEPSLATE,
			Blocks.TUFF,
			Blocks.CALCITE,
			Blocks.CLAY,
			Blocks.COAL_ORE,
			Blocks.IRON_ORE,
			Blocks.COPPER_ORE,
			Blocks.GOLD_ORE,
			Blocks.REDSTONE_ORE,
			Blocks.LAPIS_ORE,
			Blocks.DIAMOND_ORE,
			Blocks.EMERALD_ORE,
			Blocks.DEEPSLATE_COAL_ORE,
			Blocks.DEEPSLATE_IRON_ORE,
			Blocks.DEEPSLATE_COPPER_ORE,
			Blocks.NETHERRACK,
			Blocks.BLACKSTONE,
			Blocks.END_STONE,
			Blocks.OAK_LOG,
			Blocks.OAK_PLANKS
	);

	private static final int SILENT_FLAGS =
			Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

	private RandomBlocksApplier() {
	}

	public static RandomSource chunkRandom(ServerLevel level, long chunkKey) {
		return RandomSource.create(level.getSeed() ^ chunkKey * 341873128712L ^ 0x51C1D5L);
	}

	public static int applyRadius(ServerLevel level, BlockPos center, int radius) {
		RandomSource random = RandomSource.create(level.getSeed() ^ center.asLong() ^ radius);
		BlockPos min = center.offset(-radius, -radius, -radius);
		BlockPos max = center.offset(radius, radius, radius);

		for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
			clearDecoration(level, pos.immutable());
		}

		int replaced = 0;
		for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
			if (replaceTerrain(level, pos.immutable(), random)) {
				replaced++;
			}
		}
		return replaced;
	}

	public static int applyChunk(ServerLevel level, LevelChunk chunk) {
		RandomSource random = chunkRandom(level, chunk.getPos().toLong());
		forEachSolid(level, chunk, pos -> clearDecoration(level, pos));

		int[] replaced = {0};
		forEachSolid(level, chunk, pos -> {
			if (replaceTerrain(level, pos, random)) {
				replaced[0]++;
			}
		});
		return replaced[0];
	}

	private static void forEachSolid(ServerLevel level, LevelChunk chunk, java.util.function.Consumer<BlockPos> visitor) {
		var chunkPos = chunk.getPos();
		var sections = chunk.getSections();
		int minSectionY = chunk.getMinSection() << 4;

		for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
			var section = sections[sectionIndex];
			if (section.hasOnlyAir()) {
				continue;
			}

			int originY = minSectionY + (sectionIndex << 4);
			for (int lx = 0; lx < 16; lx++) {
				for (int ly = 0; ly < 16; ly++) {
					for (int lz = 0; lz < 16; lz++) {
						BlockState state = section.getBlockState(lx, ly, lz);
						if (state.isAir() || !state.getFluidState().isEmpty()) {
							continue;
						}

						visitor.accept(new BlockPos(
								chunkPos.getMinBlockX() + lx,
								originY + ly,
								chunkPos.getMinBlockZ() + lz
						));
					}
				}
			}
		}
	}

	private static void clearDecoration(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!isDecoration(level, pos, state)) {
			return;
		}
		level.setBlock(pos, Blocks.AIR.defaultBlockState(), SILENT_FLAGS);
	}

	private static boolean replaceTerrain(ServerLevel level, BlockPos pos, RandomSource random) {
		BlockState state = level.getBlockState(pos);
		if (shouldLeaveAlone(state) || isDecoration(level, pos, state)) {
			return false;
		}

		Block replacement = WHITELIST.get(random.nextInt(WHITELIST.size()));
		return level.setBlock(pos, replacement.defaultBlockState(), SILENT_FLAGS);
	}

	private static boolean shouldLeaveAlone(BlockState state) {
		if (state.isAir() || !state.getFluidState().isEmpty()) {
			return true;
		}

		Block block = state.getBlock();
		if (block == Blocks.BEDROCK || block == Blocks.BARRIER || block == Blocks.COMMAND_BLOCK
				|| block == Blocks.CHAIN_COMMAND_BLOCK || block == Blocks.REPEATING_COMMAND_BLOCK
				|| block == Blocks.NETHER_PORTAL || block == Blocks.END_PORTAL
				|| block == Blocks.END_PORTAL_FRAME || block == Blocks.END_GATEWAY) {
			return true;
		}

		return block instanceof EntityBlock;
	}

	/**
	 * Цветы, трава, двери, факелы, плиты, листва — не земля. Их нельзя оставлять:
	 * при смене блока под ними они выпадают предметами и лагают.
	 */
	private static boolean isDecoration(ServerLevel level, BlockPos pos, BlockState state) {
		if (shouldLeaveAlone(state)) {
			return false;
		}
		if (state.getBlock() instanceof FallingBlock) {
			return false;
		}
		return !state.isCollisionShapeFullBlock(level, pos);
	}
}
