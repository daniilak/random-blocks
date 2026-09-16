package com.randomblocks;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public class RandomBlocksMod implements ModInitializer {
	public static final String MOD_ID = "randomblocks";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final ArrayDeque<QueuedChunk> QUEUE = new ArrayDeque<>();
	private static final LongSet IN_QUEUE = new LongOpenHashSet();
	private static final ArrayDeque<Long> PREGEN = new ArrayDeque<>();
	private static ResourceKey<Level> pregenDimension;
	private static int pregenTotal;
	private static int pregenDone;
	private static int pregenAnnounceEvery = 200;

	private record QueuedChunk(ResourceKey<Level> dimension, long chunkKey) {
	}

	@Override
	public void onInitialize() {
		RandomBlocksCommand.register();

		ServerChunkEvents.CHUNK_LOAD.register(RandomBlocksMod::onChunkLoad);
		ServerTickEvents.END_WORLD_TICK.register(RandomBlocksMod::onWorldTick);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			handler.player.sendSystemMessage(Component.literal(
					"Random Blocks: декор (цветы, двери) удаляется без предметов. /randomblocks border 32 — рамка, /randomblocks pregen — заранее просчитать."
			));
		});

		LOGGER.info("Random Blocks loaded");
	}

	static int enqueueLoadedChunks(ServerLevel level, ChunkPos extraCenter) {
		int view = Math.max(12, level.getServer().getPlayerList().getViewDistance() + 4);
		List<ChunkPos> centers = new ArrayList<>();
		centers.add(extraCenter);
		for (ServerPlayer player : level.players()) {
			centers.add(player.chunkPosition());
		}

		int queued = 0;
		RandomBlocksState state = RandomBlocksState.get(level);
		for (ChunkPos center : centers) {
			for (int x = center.x - view; x <= center.x + view; x++) {
				for (int z = center.z - view; z <= center.z + view; z++) {
					if (!level.hasChunk(x, z)) {
						continue;
					}
					if (enqueue(level.dimension(), ChunkPos.asLong(x, z))) {
						state.markProcessed(ChunkPos.asLong(x, z));
						queued++;
					}
				}
			}
		}
		return queued;
	}

	static int startPregen(ServerLevel level, int chunkRadius) {
		PREGEN.clear();
		pregenDimension = level.dimension();
		pregenDone = 0;
		ChunkPos spawn = new ChunkPos(level.getSharedSpawnPos());
		for (int x = spawn.x - chunkRadius; x <= spawn.x + chunkRadius; x++) {
			for (int z = spawn.z - chunkRadius; z <= spawn.z + chunkRadius; z++) {
				PREGEN.add(ChunkPos.asLong(x, z));
			}
		}
		pregenTotal = PREGEN.size();
		pregenAnnounceEvery = Math.max(50, pregenTotal / 20);
		RandomBlocksState.get(level).setAutoEnabled(true);
		return pregenTotal;
	}

	private static boolean enqueue(ResourceKey<Level> dimension, long key) {
		if (!IN_QUEUE.add(key)) {
			return false;
		}
		QUEUE.add(new QueuedChunk(dimension, key));
		return true;
	}

	private static void onChunkLoad(ServerLevel level, LevelChunk chunk) {
		RandomBlocksState state = RandomBlocksState.get(level);
		if (!state.isAutoEnabled()) {
			return;
		}

		long key = chunk.getPos().toLong();
		if (!state.markChunkIfNew(key)) {
			return;
		}

		enqueue(level.dimension(), key);
	}

	private static void onWorldTick(ServerLevel level) {
		tickPregen(level);
		tickApplyQueue(level);
	}

	private static void tickPregen(ServerLevel level) {
		if (PREGEN.isEmpty() || pregenDimension == null || !pregenDimension.equals(level.dimension())) {
			return;
		}

		int budget = PREGEN.size() > 10_000 ? 1 : 2;
		for (int i = 0; i < budget && !PREGEN.isEmpty(); i++) {
			long key = PREGEN.poll();
			level.getChunk(ChunkPos.getX(key), ChunkPos.getZ(key));
			pregenDone++;
		}

		if (pregenDone == pregenTotal || (pregenDone > 0 && pregenDone % pregenAnnounceEvery == 0)) {
			int percent = pregenTotal == 0 ? 100 : (int) (pregenDone * 100L / pregenTotal);
			Component msg = Component.literal("Random Blocks преген: " + pregenDone + "/" + pregenTotal + " (" + percent + "%)");
			for (ServerPlayer player : level.players()) {
				player.sendSystemMessage(msg);
			}
		}
	}

	private static void tickApplyQueue(ServerLevel level) {
		int budget = QUEUE.size() > 30 ? 6 : 3;
		int processed = 0;
		while (processed < budget && !QUEUE.isEmpty()) {
			QueuedChunk job = QUEUE.peek();
			if (!job.dimension().equals(level.dimension())) {
				return;
			}

			QUEUE.poll();
			IN_QUEUE.remove(job.chunkKey());
			processed++;
			if (!level.hasChunk(ChunkPos.getX(job.chunkKey()), ChunkPos.getZ(job.chunkKey()))) {
				continue;
			}

			LevelChunk chunk = level.getChunk(ChunkPos.getX(job.chunkKey()), ChunkPos.getZ(job.chunkKey()));
			RandomBlocksApplier.applyChunk(level, chunk);
		}
	}
}
