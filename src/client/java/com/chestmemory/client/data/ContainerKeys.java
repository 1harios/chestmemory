package com.chestmemory.client.data;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.jspecify.annotations.Nullable;

/**
 * Normalizes container positions so a double chest is always one logical container.
 */
public final class ContainerKeys {
	private ContainerKeys() {
	}

	/**
	 * Canonical block position for storage keys.
	 * For double chests both halves resolve to the same (lexicographically smaller) position.
	 */
	public static BlockPos canonicalPos(@Nullable BlockGetter level, BlockPos pos) {
		if (level == null) {
			return pos.immutable();
		}
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof ChestBlock)) {
			return pos.immutable();
		}
		ChestType type = state.getValue(ChestBlock.TYPE);
		if (type == ChestType.SINGLE) {
			return pos.immutable();
		}
		BlockPos other = ChestBlock.getConnectedBlockPos(pos, state);
		return minPos(pos, other);
	}

	public static boolean isDoubleChest(@Nullable BlockGetter level, BlockPos pos) {
		if (level == null) {
			return false;
		}
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof ChestBlock)) {
			return false;
		}
		return state.getValue(ChestBlock.TYPE) != ChestType.SINGLE;
	}

	public static @Nullable BlockPos otherHalf(@Nullable BlockGetter level, BlockPos pos) {
		if (level == null) {
			return null;
		}
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof ChestBlock)) {
			return null;
		}
		if (state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
			return null;
		}
		return ChestBlock.getConnectedBlockPos(pos, state);
	}

	public static BlockPos minPos(BlockPos a, BlockPos b) {
		int cmp = Integer.compare(a.getX(), b.getX());
		if (cmp != 0) {
			return cmp < 0 ? a.immutable() : b.immutable();
		}
		cmp = Integer.compare(a.getY(), b.getY());
		if (cmp != 0) {
			return cmp < 0 ? a.immutable() : b.immutable();
		}
		cmp = Integer.compare(a.getZ(), b.getZ());
		return cmp <= 0 ? a.immutable() : b.immutable();
	}

	public static String blockKey(String dimension, BlockPos pos) {
		return ContainerRecord.makeKey(dimension, pos.getX(), pos.getY(), pos.getZ());
	}
}
