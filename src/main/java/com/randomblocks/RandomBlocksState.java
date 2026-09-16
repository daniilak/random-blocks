package com.randomblocks;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

public final class RandomBlocksState extends SavedData {
	/** Специальный масштаб: весь чанк — один случайный блок. */
	public static final int SCALE_CHUNK = 0;

	private boolean autoEnabled = true;
	/** 1, 2, 4, 8, 16 или {@link #SCALE_CHUNK}. */
	private int scale = 1;
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
		if (tag.contains("Scale")) {
			state.scale = normalizeScale(tag.getInt("Scale"));
		}
		for (long key : tag.getLongArray("Chunks")) {
			state.processedChunks.add(key);
		}
		return state;
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
		tag.putBoolean("Auto", autoEnabled);
		tag.putInt("Scale", scale);
		tag.putLongArray("Chunks", processedChunks.toLongArray());
		return tag;
	}

	public static int normalizeScale(int scale) {
		return switch (scale) {
			case SCALE_CHUNK, 1, 2, 4, 8, 16 -> scale;
			default -> 1;
		};
	}

	public static String formatScale(int scale) {
		int safe = normalizeScale(scale);
		if (safe == SCALE_CHUNK) {
			return "chunk (весь чанк = 1 блок)";
		}
		return safe + "×" + safe + "×" + safe;
	}

	public boolean isAutoEnabled() {
		return autoEnabled;
	}

	public void setAutoEnabled(boolean autoEnabled) {
		this.autoEnabled = autoEnabled;
		setDirty();
	}

	public int getScale() {
		return scale;
	}

	public void setScale(int scale) {
		this.scale = normalizeScale(scale);
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
