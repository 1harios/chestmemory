package com.chestmemory.client.scan;

import com.chestmemory.ChestMemoryMod;
import com.chestmemory.client.data.ChestMemoryStorage;
import com.chestmemory.client.data.ContainerRecord;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.util.ArrayList;
import java.util.List;

/**
 * Drops remembered containers whose block is gone from the world.
 * <p>
 * Memory was written on open and never revisited, so a chest that someone broke — or that
 * blew up, or that a griefer took — stayed in the list forever. Worse, it stayed as a
 * source of items: the panel counted its contents, the reverse index kept pointing at it,
 * and the highlight drew a glowing outline around empty air.
 * <p>
 * <b>Only removes what it can actually see.</b> An unloaded chunk reads as air on the
 * client, so "no block here" is only trustworthy when the chunk is loaded. Verification is
 * therefore limited to positions near the player, and a record is removed only when the
 * chunk is loaded and the block is genuinely something else. That means a chest broken on
 * the far side of the world survives until you next go near it — which is the safe
 * direction to fail: a stale entry is a small annoyance, deleting a real chest's contents
 * from memory is not recoverable.
 */
public final class ContainerVerifier {
	/**
	 * How far from the player to check, in blocks. Chunks are loaded well beyond this, but
	 * staying close keeps the scan cheap and keeps us clear of the edge of the loaded area,
	 * where a chunk can unload between the check and the removal.
	 */
	private static final int RADIUS = 48;
	private static final int RADIUS_SQ = RADIUS * RADIUS;

	/** Ticks between sweeps — 5s. The world does not change fast enough to need more. */
	private static final int INTERVAL_TICKS = 100;

	/**
	 * A record must look broken twice in a row before it is dropped. One sweep is enough to
	 * be fooled by a chunk that is mid-load, where blocks briefly read as air.
	 */
	private static final int STRIKES_NEEDED = 2;

	private static int tickCounter;
	/** Container keys that failed the last sweep, so a single bad reading is not enough. */
	private static final java.util.Map<String, Integer> strikes = new java.util.HashMap<>();

	private ContainerVerifier() {
	}

	public static void tick(Minecraft client) {
		if (client.player == null || client.level == null) {
			return;
		}
		if (++tickCounter % INTERVAL_TICKS != 0) {
			return;
		}
		sweep(client);
	}

	/** Forget the strike history — used when switching worlds, so counts never carry over. */
	public static void reset() {
		strikes.clear();
	}

	private static void sweep(Minecraft client) {
		Level level = client.level;
		if (level == null || client.player == null) {
			return;
		}
		String dimension = ChestMemoryStorage.dimensionId(level);
		BlockPos me = client.player.blockPosition();
		String here = com.chestmemory.client.data.WorldFingerprint.of(level);

		List<ContainerRecord> gone = new ArrayList<>();
		List<String> stillHere = new ArrayList<>();

		for (ContainerRecord record : ChestMemoryStorage.get().liveWorldBlockRecords()) {
			if (!dimension.equals(record.dimension())) {
				continue;
			}
			// A multiworld server reports the same dimension id for every world's Nether, so
			// a matching id is not proof we are in the world this chest was seen in. Standing
			// in the farm Nether must not delete a chest remembered in the build Nether.
			if (com.chestmemory.client.data.WorldFingerprint.provablyDifferent(here, record.worldTag())) {
				continue;
			}
			BlockPos pos = new BlockPos(record.x(), record.y(), record.z());
			if (me.distSqr(pos) > RADIUS_SQ) {
				continue;
			}
			// The client only knows what is in loaded chunks; elsewhere getBlockState lies.
			if (!level.isLoaded(pos)) {
				continue;
			}
			// Strike bookkeeping and removal both use the record's own storage key: with
			// per-world keys, rebuilding an untagged key here would miss tagged records —
			// and could delete the other world's record at the same coordinates.
			String key = record.positionKey();
			if (isContainerBlock(level.getBlockState(pos).getBlock())) {
				stillHere.add(key);
				continue;
			}
			// Double chest: the record is keyed on one half, and breaking that half leaves the
			// other standing. Spare the record only while the partner is still paired with THIS
			// position — a lone chest next door is a different container.
			//
			// Sparing it whenever the partner was any container at all was a leak with no exit.
			// The rewrite this used to promise never arrived: opening the surviving half runs as
			// a SINGLE chest, and the supersede path only clears keys at the position it scanned.
			// So the old double_chest record sat at the broken half's coordinates forever, every
			// sweep re-blessed it through this very check, its contents kept being counted on top
			// of the new single's, and the highlighter glowed on air.
			if (record.hasOtherHalf()) {
				BlockPos other = new BlockPos(record.otherX(), record.otherY(), record.otherZ());
				if (level.isLoaded(other) && stillPairedWith(level, other, pos)) {
					stillHere.add(key);
					continue;
				}
			}
			int n = strikes.merge(key, 1, Integer::sum);
			if (n >= STRIKES_NEEDED) {
				gone.add(record);
			}
		}

		// A container that reappeared (chunk finished loading, block replaced) clears its count.
		for (String key : stillHere) {
			strikes.remove(key);
		}

		for (ContainerRecord record : gone) {
			ChestMemoryStorage.get().forget(record.positionKey());
			strikes.remove(record.positionKey());
			ChestMemoryMod.LOGGER.debug(
				"Forgot missing container at {} {},{},{}",
				record.dimension(), record.x(), record.y(), record.z()
			);
		}
		if (!gone.isEmpty()) {
			ChestMemoryStorage.get().saveIfNeeded();
		}
	}

	/**
	 * True when the chest at {@code other} is still one half of a double chest whose partner
	 * is exactly {@code expected} — the only condition under which a record keyed on a broken
	 * half still describes something that exists.
	 */
	private static boolean stillPairedWith(Level level, BlockPos other, BlockPos expected) {
		BlockState state = level.getBlockState(other);
		if (!(state.getBlock() instanceof ChestBlock)) {
			return false;
		}
		if (state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
			return false;
		}
		return ChestBlock.getConnectedBlockPos(other, state).equals(expected);
	}

	private static boolean isContainerBlock(Block block) {
		return block instanceof ChestBlock
			|| block instanceof BarrelBlock
			|| block instanceof ShulkerBoxBlock
			|| block instanceof EnderChestBlock
			|| block instanceof HopperBlock
			|| block instanceof DispenserBlock;
	}
}
