package com.randomblocks;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

public final class RandomBlocksState extends SavedData {
	private boolean autoEnabled = true;
	private final LongSet processedChunks = new LongOpenHashSet();

	public static RandomBlocksState get(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(
				new SavedData.Factory<>(RandomBlocksState::new, RandomBlocksState::load, null),
				"randomblocks"
		);
	}

	public static RandomBlocksState load(CompoundTag tag, HolderLookup.Provider registries) {
		RandomBlocksState state = new RandomBlocksState();
		if (tag.contains("Auto")) {
			state.autoEnabled = tag.getBoolean("Auto");
		}
		for (long key : tag.getLongArray("Chunks")) {
			state.processedChunks.add(key);
		}
		return state;
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
		tag.putBoolean("Auto", autoEnabled);
		tag.putLongArray("Chunks", processedChunks.toLongArray());
		return tag;
	}

	public boolean isAutoEnabled() {
		return autoEnabled;
	}

	public void setAutoEnabled(boolean autoEnabled) {
		this.autoEnabled = autoEnabled;
		setDirty();
	}

	public boolean markChunkIfNew(long chunkKey) {
		if (!processedChunks.add(chunkKey)) {
			return false;
		}
		setDirty();
		return true;
	}

	public void markProcessed(long chunkKey) {
		if (processedChunks.add(chunkKey)) {
			setDirty();
		}
	}
}
