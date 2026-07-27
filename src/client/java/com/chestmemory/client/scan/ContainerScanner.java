package com.chestmemory.client.scan;

import com.chestmemory.ChestMemoryMod;
import com.chestmemory.client.data.ChestMemoryStorage;
import com.chestmemory.client.data.ContainerKeys;
import com.chestmemory.client.data.ContainerRecord;
import com.chestmemory.client.util.ClientScreens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads open container menus and inventory shulkers; saves into the live world/server profile only.
 */
public final class ContainerScanner {
	private static final long INTERACT_MAX_AGE_MS = 30_000L;
	private static int tickCounter;
	/**
	 * Canonical staging key already handled for the currently open container GUI.
	 * Prevents warehouse pick from re-firing every client tick while the chest stays open.
	 */
	private static @Nullable String stagingHandledThisOpen;
	/**
	 * Menu instance currently being scanned, and whether it has ever reported contents.
	 * <p>
	 * A container menu exists before its ContainerSetContent packet arrives, so for the
	 * first tick(s) every slot reads empty. Writing that straight to memory wiped a
	 * chest's remembered contents whenever the player opened and closed it faster than
	 * the server replied — common on a laggy server.
	 */
	private static @Nullable AbstractContainerMenu trackedMenu;
	private static boolean trackedMenuHadContents;

	private ContainerScanner() {
	}

	public static void tick(Minecraft client) {
		if (client.player == null || client.level == null) {
			return;
		}

		ChestMemoryStorage.get().ensureLoaded(client);

		if (++tickCounter % 200 == 0) {
			ChestMemoryStorage.get().saveIfNeeded();
		}

		if (tickCounter % 40 == 0) {
			scanInventoryShulkers(client);
		}

		Screen screen = ClientScreens.get(client);
		if (screen instanceof AbstractContainerScreen<?> containerScreen) {
			scanOpenScreen(client, containerScreen);
		} else {
			// No container open — sticky ender flag no longer needed
			LastInteractTracker.clearEnderChestPending();
			stagingHandledThisOpen = null;
			trackedMenu = null;
			trackedMenuHadContents = false;
		}
	}

	public static void onScreenClosed(Minecraft client, Screen screen) {
		if (client.player == null || client.level == null) {
			LastInteractTracker.clearEnderChestPending();
			stagingHandledThisOpen = null;
			return;
		}
		if (screen instanceof AbstractContainerScreen<?> containerScreen) {
			scanOpenScreen(client, containerScreen);
			scanInventoryShulkers(client);
			ChestMemoryStorage.get().saveIfNeeded();
		}
		LastInteractTracker.clearEnderChestPending();
		stagingHandledThisOpen = null;
	}

	private static void scanOpenScreen(Minecraft client, AbstractContainerScreen<?> screen) {
		if (!(screen instanceof MenuAccess<?> access)) {
			return;
		}

		AbstractContainerMenu menu = access.getMenu();
		ScanTarget target = resolveTarget(client, menu, screen);
		if (target == null) {
			return;
		}

		if (menu != trackedMenu) {
			trackedMenu = menu;
			trackedMenuHadContents = false;
		}

		Map<String, Integer> items = readSlots(menu, target.containerSlots());

		if (!items.isEmpty()) {
			trackedMenuHadContents = true;
		} else if (!trackedMenuHadContents && menu.getStateId() == 0) {
			// Empty, nothing seen yet, and the server has not synced this menu once
			// (stateId is bumped on every sync and starts at 0): the ContainerSetContent
			// packet has not arrived. Recording now would replace a real chest's contents
			// with nothing, which is what happened when a laggy server was slower than
			// the player's open/close.
			//
			// A genuinely empty chest still gets recorded, because its sync bumps stateId
			// even with no items; emptying one by hand also works, since contents were
			// seen earlier in the same menu.
			return;
		}

		// Ender chest: single virtual entry for this server/world profile
		if (target.virtual() && "ender_chest".equals(target.virtualId())) {
			saveEnderChest(client, target, items);
			// Live highlight follows memory immediately (item taken → glow off that chest)
			return;
		}

		if (target.virtual()) {
			ContainerRecord record = ContainerRecord.virtual(target.type(), target.virtualId(), target.dimension());
			record.setItems(items);
			record.setLastSeenMillis(System.currentTimeMillis());
			if (target.displayName() != null) {
				record.setDisplayName(target.displayName());
			}
			ChestMemoryStorage.get().remember(record);
			return;
		}

		// Prefer sticky last-interact only. Do NOT fall back to crosshair hit while a GUI is open —
		// looking around would rebind the open menu to random blocks (and spam warehouse chat).
		BlockPos pos = target.blockPos();
		if (pos == null) {
			pos = LastInteractTracker.getRecent(INTERACT_MAX_AGE_MS);
		}
		if (pos == null || client.level == null) {
			return;
		}
		// Guard against binding a server-side GUI to whatever block was clicked last.
		if (!isTrackedContainerBlock(client, pos)) {
			return;
		}

		String dimension = ChestMemoryStorage.dimensionId(client.level);
		String type = refineTypeFromBlock(client, pos, target.type());
		// Never store ender as a world block chest
		if ("ender_chest".equals(type) || client.level.getBlockState(pos).getBlock() instanceof EnderChestBlock) {
			saveEnderChest(client, new ScanTarget(
				"ender_chest", target.containerSlots(), true, "ender_chest", dimension, pos, "Ender Chest"
			), items);
			return;
		}
		if (target.containerSlots() >= 54 || ContainerKeys.isDoubleChest(client.level, pos)) {
			type = "double_chest";
		}

		// Rewrite memory from the open menu so taking the last of an item removes this chest
		// from the highlight on the next tick. Returns false when nothing changed — the
		// scanner runs every tick while the GUI is open, and an idle open chest used to
		// re-dirty the profile (and below, re-ping the clan hub) twenty times a second.
		boolean changed = ChestMemoryStorage.get().rememberBlockContainer(client.level, dimension, pos, type, items);

		// If this chest is the clan's warehouse, tell the hub what is in it now. The periodic
		// push runs every ~10s, so dropping a stack off and looking at the panel showed
		// nothing counted yet — which reads as "it did not register". Deliveries are the one
		// thing that has to land immediately, because the whole clan is watching that number.
		if (changed
			&& com.chestmemory.client.clan.ClanSessionManager.isInSession()
			&& ChestMemoryStorage.get().isStagingKey(
				ContainerKeys.blockKey(dimension, ContainerKeys.canonicalPos(client.level, pos)))) {
			com.chestmemory.client.clan.ClanSessionManager.pushStagingProgress(client);
		}

		// Warehouse pick: once per open GUI (scanner runs every tick while chest is open)
		BlockPos canonical = ContainerKeys.canonicalPos(client.level, pos);
		String stagingKey = ContainerKeys.blockKey(dimension, canonical);
		if (!stagingKey.equals(stagingHandledThisOpen)) {
			stagingHandledThisOpen = stagingKey;
			com.chestmemory.client.data.StagingPickMode.onWorldChestOpened(client.level, dimension, pos);
		}
	}

	private static void saveEnderChest(Minecraft client, ScanTarget target, Map<String, Integer> items) {
		BlockPos p = target.blockPos() != null ? target.blockPos() : LastInteractTracker.enderChestPos();

		// The scanner reaches this every tick while the ender chest stays open, and each
		// call used to remember + flush to disk. Skip when memory already says exactly this.
		ContainerRecord prev = ChestMemoryStorage.get().findLiveByKey("virtual|ender_chest");
		if (prev != null && prev.items().equals(items)) {
			boolean sameHighlight = p == null
				|| (prev.hasHighlightPos()
					&& prev.highlightX() == p.getX()
					&& prev.highlightY() == p.getY()
					&& prev.highlightZ() == p.getZ());
			if (sameHighlight) {
				prev.setLastSeenMillis(System.currentTimeMillis());
				return;
			}
		}

		ContainerRecord record = ContainerRecord.virtual("ender_chest", "ender_chest", target.dimension());
		record.setType("ender_chest");
		record.setItems(items);
		record.setDisplayName("Ender Chest");
		record.setLastSeenMillis(System.currentTimeMillis());
		if (p != null) {
			record.setHighlightPos(p.getX(), p.getY(), p.getZ());
			ChestMemoryStorage.get().forgetAt(target.dimension(), p);
		}
		ChestMemoryStorage.get().remember(record);
		// Flush immediately so inventory hover sees data right away
		ChestMemoryStorage.get().saveIfNeeded();
		ChestMemoryMod.LOGGER.debug(
			"Scanned ender chest: {} item types, {} total",
			items.size(),
			items.values().stream().mapToInt(Integer::intValue).sum()
		);
	}

	private static void scanInventoryShulkers(Minecraft client) {
		if (client.player == null || client.level == null) {
			return;
		}
		Inventory inv = client.player.getInventory();
		String dimension = ChestMemoryStorage.dimensionId(client.level);

		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty() || !isShulkerStack(stack)) {
				// Slot holds no shulker now. Any record left from a previous scan is stale:
				// moving a shulker between slots used to leave a duplicate behind, and
				// emptying the slot left a phantom whose contents kept being counted.
				ChestMemoryStorage.get().forget("virtual|inv_shulker_" + i);
				continue;
			}
			ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
			if (contents == null) {
				ChestMemoryStorage.get().forget("virtual|inv_shulker_" + i);
				continue;
			}

			Map<String, Integer> items = new LinkedHashMap<>();
			contents.nonEmptyItemCopyStream().forEach(inner -> {
				String key = com.chestmemory.client.data.ItemStackKeys.keyOf(inner);
				if (!"minecraft:air".equals(key)) {
					items.merge(key, inner.getCount(), Integer::sum);
				}
			});

			String virtualId = "inv_shulker_" + i;
			String displayName = stack.getHoverName().getString() + " [inv #" + i + "]";

			// This scan repeats every 2s; an unchanged shulker used to re-dirty the profile
			// and invalidate the snapshot cache each time.
			ContainerRecord prev = ChestMemoryStorage.get().findLiveByKey("virtual|" + virtualId);
			if (prev != null && prev.items().equals(items)
				&& displayName.equals(prev.displayName())
				&& dimension.equals(prev.dimension())) {
				prev.setLastSeenMillis(System.currentTimeMillis());
				continue;
			}

			ContainerRecord record = ContainerRecord.virtual("inventory_shulker", virtualId, dimension);
			record.setItems(items);
			record.setDisplayName(displayName);
			record.setLastSeenMillis(System.currentTimeMillis());
			ChestMemoryStorage.get().remember(record);
		}
	}

	private static boolean isShulkerStack(ItemStack stack) {
		if (stack.getItem() instanceof BlockItem blockItem) {
			return blockItem.getBlock() instanceof ShulkerBoxBlock;
		}
		Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
		return id != null && id.getPath().contains("shulker_box") && stack.has(DataComponents.CONTAINER);
	}

	private static Map<String, Integer> readSlots(AbstractContainerMenu menu, int containerSlots) {
		Map<String, Integer> items = new LinkedHashMap<>();
		int limit = Math.min(containerSlots, menu.slots.size());
		for (int i = 0; i < limit; i++) {
			Slot slot = menu.slots.get(i);
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) {
				continue;
			}
			String key = com.chestmemory.client.data.ItemStackKeys.keyOf(stack);
			if (!"minecraft:air".equals(key)) {
				items.merge(key, stack.getCount(), Integer::sum);
			}

			if (isShulkerStack(stack)) {
				ItemContainerContents nested = stack.get(DataComponents.CONTAINER);
				if (nested != null) {
					nested.nonEmptyItemCopyStream().forEach(inner -> {
						String innerKey = com.chestmemory.client.data.ItemStackKeys.keyOf(inner);
						if (!"minecraft:air".equals(innerKey)) {
							items.merge(innerKey, inner.getCount(), Integer::sum);
						}
					});
				}
			}
		}
		return items;
	}

	/**
	 * True when the block really is a container we track.
	 * <p>
	 * The open menu is matched to a block by "last block right-clicked", which says
	 * nothing about whether that click actually opened this menu. Servers routinely
	 * open plain {@code ChestMenu}s for shops, kits and custom UIs, and those would
	 * otherwise be written to memory as a chest standing at the last clicked block —
	 * inventing containers on signs and doors, or overwriting a real chest's contents
	 * with a shop's stock. An unloaded chunk reads as air and is rejected too, which is
	 * the conservative outcome we want.
	 */
	private static boolean isTrackedContainerBlock(Minecraft client, BlockPos pos) {
		if (client.level == null) {
			return false;
		}
		Block block = client.level.getBlockState(pos).getBlock();
		return block instanceof ChestBlock
			|| block instanceof BarrelBlock
			|| block instanceof ShulkerBoxBlock
			|| block instanceof EnderChestBlock
			|| block instanceof HopperBlock
			|| block instanceof DispenserBlock;
	}

	private static String refineTypeFromBlock(Minecraft client, BlockPos pos, String fallback) {
		if (client.level == null) {
			return fallback;
		}
		BlockState state = client.level.getBlockState(pos);
		Block block = state.getBlock();
		if (block instanceof EnderChestBlock) {
			return "ender_chest";
		}
		if (block instanceof BarrelBlock) {
			return "barrel";
		}
		if (block instanceof ShulkerBoxBlock) {
			return "shulker_box";
		}
		if (block instanceof ChestBlock) {
			return "chest";
		}
		return fallback;
	}

	private static boolean isEnderBlock(Minecraft client, BlockPos pos) {
		return pos != null && client.level != null
			&& client.level.getBlockState(pos).getBlock() instanceof EnderChestBlock;
	}

	private static ScanTarget resolveTarget(Minecraft client, AbstractContainerMenu menu, AbstractContainerScreen<?> screen) {
		String dimension = client.level != null ? ChestMemoryStorage.dimensionId(client.level) : "minecraft:overworld";
		BlockPos last = LastInteractTracker.getForScan(INTERACT_MAX_AGE_MS);
		BlockPos stickyEnder = LastInteractTracker.enderChestPos();

		if (menu instanceof ChestMenu chest) {
			boolean enderByContainer = chest.getContainer() instanceof PlayerEnderChestContainer;
			boolean enderBySticky = LastInteractTracker.isEnderChestPending();
			boolean enderByBlock = isEnderBlock(client, last) || isEnderBlock(client, stickyEnder);
			// Normal ender = 3 rows. Sticky/block signals are reliable on multiplayer too
			// (there the container is a plain SimpleContainer, so enderByContainer is false).
			boolean threeRows = chest.getRowCount() == 3;
			// The title alone is not proof: a server GUI called "Ender Upgrades", or a chest
			// renamed "Ender stuff" on an anvil, would otherwise overwrite the profile's only
			// ender-chest record (and delete the real block entry at that position). It now
			// only reinforces a real signal — the clicked block or the sticky ender flag.
			boolean enderByPosition = enderBySticky || enderByBlock;
			boolean ender = enderByContainer || (threeRows && enderByPosition);

			if (ender) {
				BlockPos pos = stickyEnder != null ? stickyEnder : last;
				return new ScanTarget(
					"ender_chest",
					Math.max(27, chest.getRowCount() * 9),
					true,
					"ender_chest",
					dimension,
					pos,
					"Ender Chest"
				);
			}

			int rows = chest.getRowCount();
			String type = rows >= 6 ? "double_chest" : "chest_or_barrel";
			if (last != null && client.level != null) {
				type = refineTypeFromBlock(client, last, type);
				if ("ender_chest".equals(type)) {
					return new ScanTarget("ender_chest", rows * 9, true, "ender_chest", dimension, last, "Ender Chest");
				}
				if (rows >= 6 || ContainerKeys.isDoubleChest(client.level, last)) {
					type = "double_chest";
				}
			}
			return new ScanTarget(type, rows * 9, false, null, dimension, last, null);
		}
		if (menu instanceof ShulkerBoxMenu) {
			return new ScanTarget("shulker_box", 27, false, null, dimension, last, null);
		}
		if (menu instanceof HopperMenu) {
			return new ScanTarget("hopper", HopperMenu.CONTAINER_SIZE, false, null, dimension, last, null);
		}
		if (menu instanceof DispenserMenu) {
			return new ScanTarget("dispenser_or_dropper", 9, false, null, dimension, last, null);
		}
		return null;
	}

	private record ScanTarget(
		String type,
		int containerSlots,
		boolean virtual,
		String virtualId,
		String dimension,
		BlockPos blockPos,
		String displayName
	) {
	}
}
