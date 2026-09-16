package com.randomblocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;

public final class RandomBlocksApplier {
	private static final int SILENT_FLAGS =
			Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

	private static List<Block> OVERWORLD_POOL = List.of();
	private static List<Block> NETHER_POOL = List.of();
	private static List<Block> END_POOL = List.of();
	private static boolean poolsReady;

	private RandomBlocksApplier() {
	}

	public static void ensurePools() {
		rebuildPools(false);
	}

	public static void rebuildPools() {
		rebuildPools(true);
	}

	private static synchronized void rebuildPools(boolean force) {
		if (poolsReady && !force) {
			return;
		}

		List<Block> overworld = new ArrayList<>();
		List<Block> nether = new ArrayList<>();
		List<Block> end = new ArrayList<>();

		for (Block block : BuiltInRegistries.BLOCK) {
			if (!isUsableTerrain(block)) {
				continue;
			}

			if (isEndBlock(block)) {
				end.add(block);
			} else if (isNetherBlock(block)) {
				nether.add(block);
			} else {
				overworld.add(block);
			}
		}

		for (Block shared : List.of(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN)) {
			if (!isUsableTerrain(shared)) {
				continue;
			}
			addUnique(overworld, shared);
			addUnique(nether, shared);
			addUnique(end, shared);
		}

		if (overworld.isEmpty()) {
			overworld.add(Blocks.STONE);
		}
		if (nether.isEmpty()) {
			nether.add(Blocks.NETHERRACK);
		}
		if (end.isEmpty()) {
			end.add(Blocks.END_STONE);
		}

		OVERWORLD_POOL = List.copyOf(overworld);
		NETHER_POOL = List.copyOf(nether);
		END_POOL = List.copyOf(end);
		poolsReady = true;

		RandomBlocksMod.LOGGER.info(
				"Random Blocks pools: overworld={}, nether={}, end={}",
				OVERWORLD_POOL.size(),
				NETHER_POOL.size(),
				END_POOL.size()
		);
	}

	public static List<Block> poolFor(ServerLevel level) {
		ensurePools();
		if (level.dimension() == Level.NETHER) {
			return NETHER_POOL;
		}
		if (level.dimension() == Level.END) {
			return END_POOL;
		}
		return OVERWORLD_POOL;
	}

	public static RandomSource chunkRandom(ServerLevel level, long chunkKey) {
		return RandomSource.create(level.getSeed() ^ chunkKey * 341873128712L ^ 0x51C1D5L);
	}

	public static int applyRadius(ServerLevel level, BlockPos center, int radius) {
		RandomBlocksState state = RandomBlocksState.get(level);
		List<Block> pool = poolFor(level);
		BlockPos min = center.offset(-radius, -radius, -radius);
		BlockPos max = center.offset(radius, radius, radius);

		for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
			clearDecoration(level, pos.immutable());
		}

		int replaced = 0;
		for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
			if (replaceTerrain(level, pos.immutable(), pool, state, null)) {
				replaced++;
			}
		}
		return replaced;
	}

	public static int applyChunk(ServerLevel level, LevelChunk chunk) {
		RandomBlocksState state = RandomBlocksState.get(level);
		List<Block> pool = poolFor(level);
		Block chunkWide = null;
		if (state.getScale() == RandomBlocksState.SCALE_CHUNK) {
			RandomSource random = chunkRandom(level, chunk.getPos().toLong());
			chunkWide = pool.get(random.nextInt(pool.size()));
		}

		forEachSolid(level, chunk, pos -> clearDecoration(level, pos));

		Block fixed = chunkWide;
		int[] replaced = {0};
		forEachSolid(level, chunk, pos -> {
			if (replaceTerrain(level, pos, pool, state, fixed)) {
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

	private static boolean replaceTerrain(
			ServerLevel level,
			BlockPos pos,
			List<Block> pool,
			RandomBlocksState settings,
			Block chunkWide
	) {
		BlockState state = level.getBlockState(pos);
		if (shouldLeaveAlone(state) || isDecoration(level, pos, state)) {
			return false;
		}

		Block replacement = chunkWide != null ? chunkWide : pickForCell(level, pos, pool, settings.getScale());
		return level.setBlock(pos, replacement.defaultBlockState(), SILENT_FLAGS);
	}

	/**
	 * scale 1/2/4/8/16 — кубики S×S×S в мировых координатах (границы чанков стыкуются).
	 * SCALE_CHUNK обрабатывается отдельно одним блоком на весь чанк.
	 */
	private static Block pickForCell(ServerLevel level, BlockPos pos, List<Block> pool, int scale) {
		int safeScale = RandomBlocksState.normalizeScale(scale);
		if (safeScale == RandomBlocksState.SCALE_CHUNK) {
			RandomSource random = chunkRandom(level, ChunkPos.asLong(pos));
			return pool.get(random.nextInt(pool.size()));
		}

		int cellX = Math.floorDiv(pos.getX(), safeScale);
		int cellY = Math.floorDiv(pos.getY(), safeScale);
		int cellZ = Math.floorDiv(pos.getZ(), safeScale);
		long seed = level.getSeed()
				^ (long) cellX * 73428767L
				^ (long) cellY * 19349663L
				^ (long) cellZ * 83492791L
				^ (long) safeScale * 0x9E3779B97F4A7C15L;
		return pool.get(RandomSource.create(seed).nextInt(pool.size()));
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

	private static boolean isUsableTerrain(Block block) {
		BlockState state = block.defaultBlockState();
		if (state.isAir() || !state.getFluidState().isEmpty()) {
			return false;
		}
		if (block instanceof FallingBlock || block instanceof EntityBlock) {
			return false;
		}
		if (block == Blocks.BEDROCK || block == Blocks.BARRIER || block == Blocks.COMMAND_BLOCK
				|| block == Blocks.CHAIN_COMMAND_BLOCK || block == Blocks.REPEATING_COMMAND_BLOCK
				|| block == Blocks.NETHER_PORTAL || block == Blocks.END_PORTAL
				|| block == Blocks.END_PORTAL_FRAME || block == Blocks.END_GATEWAY
				|| block == Blocks.STRUCTURE_VOID || block == Blocks.STRUCTURE_BLOCK
				|| block == Blocks.JIGSAW || block == Blocks.LIGHT) {
			return false;
		}
		return state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
	}

	private static boolean isNetherBlock(Block block) {
		var holder = block.builtInRegistryHolder();
		if (holder.is(BlockTags.BASE_STONE_NETHER)
				|| holder.is(BlockTags.NYLIUM)
				|| holder.is(BlockTags.WART_BLOCKS)
				|| holder.is(BlockTags.SOUL_FIRE_BASE_BLOCKS)
				|| holder.is(BlockTags.INFINIBURN_NETHER)) {
			return true;
		}

		return block == Blocks.NETHERRACK
				|| block == Blocks.MAGMA_BLOCK
				|| block == Blocks.GLOWSTONE
				|| block == Blocks.SHROOMLIGHT
				|| block == Blocks.NETHER_BRICKS
				|| block == Blocks.RED_NETHER_BRICKS
				|| block == Blocks.CRACKED_NETHER_BRICKS
				|| block == Blocks.CHISELED_NETHER_BRICKS
				|| block == Blocks.NETHER_GOLD_ORE
				|| block == Blocks.NETHER_QUARTZ_ORE
				|| block == Blocks.ANCIENT_DEBRIS
				|| block == Blocks.BLACKSTONE
				|| block == Blocks.GILDED_BLACKSTONE
				|| block == Blocks.POLISHED_BLACKSTONE
				|| block == Blocks.POLISHED_BLACKSTONE_BRICKS
				|| block == Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS
				|| block == Blocks.CHISELED_POLISHED_BLACKSTONE
				|| block == Blocks.BASALT
				|| block == Blocks.SMOOTH_BASALT
				|| block == Blocks.POLISHED_BASALT
				|| block == Blocks.CRIMSON_NYLIUM
				|| block == Blocks.WARPED_NYLIUM
				|| block == Blocks.CRIMSON_STEM
				|| block == Blocks.WARPED_STEM
				|| block == Blocks.STRIPPED_CRIMSON_STEM
				|| block == Blocks.STRIPPED_WARPED_STEM
				|| block == Blocks.CRIMSON_HYPHAE
				|| block == Blocks.WARPED_HYPHAE
				|| block == Blocks.STRIPPED_CRIMSON_HYPHAE
				|| block == Blocks.STRIPPED_WARPED_HYPHAE
				|| block == Blocks.NETHER_WART_BLOCK
				|| block == Blocks.WARPED_WART_BLOCK
				|| block == Blocks.SOUL_SOIL
				|| block == Blocks.BONE_BLOCK
				|| block == Blocks.QUARTZ_BLOCK
				|| block == Blocks.SMOOTH_QUARTZ
				|| block == Blocks.CHISELED_QUARTZ_BLOCK
				|| block == Blocks.QUARTZ_BRICKS
				|| block == Blocks.QUARTZ_PILLAR;
	}

	private static boolean isEndBlock(Block block) {
		return block == Blocks.END_STONE
				|| block == Blocks.END_STONE_BRICKS
				|| block == Blocks.PURPUR_BLOCK
				|| block == Blocks.PURPUR_PILLAR;
	}

	private static void addUnique(List<Block> list, Block block) {
		if (!list.contains(block)) {
			list.add(block);
		}
	}
}
