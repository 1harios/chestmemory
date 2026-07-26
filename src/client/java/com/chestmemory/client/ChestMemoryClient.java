package com.chestmemory.client;

import com.chestmemory.ChestMemoryMod;
import com.chestmemory.client.data.ChestMemoryStorage;
import com.chestmemory.client.data.MultiworldTracker;
import com.chestmemory.client.gui.ChestMemoryScreen;
import com.chestmemory.client.highlight.ChestHighlighter;
import com.chestmemory.client.highlight.ChestItemIconOverlay;
import com.chestmemory.client.highlight.SlotHighlighter;
import com.chestmemory.client.data.ModSettings;
import com.chestmemory.client.litematica.BuildGatherHud;
import com.chestmemory.client.litematica.BuildGatherSession;
import com.chestmemory.client.scan.ContainerScanner;
import com.chestmemory.client.tooltip.EnderChestTooltip;
import com.chestmemory.client.util.ClientScreens;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class ChestMemoryClient implements ClientModInitializer {
	/**
	 * Default: key under Esc / left of 1 — on Russian layout this is often Ё.
	 */
	public static KeyMapping openPanelKey;
	public static KeyMapping clearHighlightKey;
	/** Next material in gather queue (skip current item). */
	public static KeyMapping nextItemKey;
	/** Toggle “open chests to mark as warehouse” pick mode. */
	public static KeyMapping toggleStagingKey;

	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(ChestMemoryMod.id("main"));

	@Override
	public void onInitializeClient() {
		openPanelKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.chestmemory.open_panel",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_GRAVE_ACCENT,
			CATEGORY
		));

		clearHighlightKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.chestmemory.clear_highlight",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_K,
			CATEGORY
		));

		nextItemKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.chestmemory.next_item",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_N,
			CATEGORY
		));

		toggleStagingKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.chestmemory.toggle_staging",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_P,
			CATEGORY
		));
		// P toggles warehouse pick mode (open chests to mark), not look-at

		ClientTickEvents.END_CLIENT_TICK.register(ChestMemoryClient::onEndTick);
		// Flush deferred settings AND chest memory on quit. onEndTick only persists once
		// client.player has become null, which takes one more tick after the disconnect —
		// quitting straight from a world (Quit Game / Alt+F4) never gets that tick, so up
		// to a full autosave interval of scans was lost.
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING
			.register(client -> {
				ModSettings.flushPending();
				ChestMemoryStorage.get().saveIfNeeded();
			});
		// Same reasoning when leaving a world/server: persist while the profile is still
		// loaded, and drop the stale interact position so a quick reconnect to a different
		// server cannot bind a menu to a block clicked on the previous one.
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT
			.register((handler, client) -> {
				ChestMemoryStorage.get().saveIfNeeded();
				com.chestmemory.client.scan.LastInteractTracker.clear();
				// Strike counts are per world: a position that looked empty here must not
				// count against a container at the same coordinates on the next server.
				com.chestmemory.client.scan.ContainerVerifier.reset();
				// A gather belongs to a build, not to a connection. A multiworld server sends
				// a full disconnect when you walk through a portal between its worlds, so
				// clearing here wiped the queue and the cached material list mid-build — the
				// gather list emptied and the «Сбор» button went dead. Park it instead, and
				// let it resume if we land back where the schematic is.
				BuildGatherSession.park();
				// Release our claims right away instead of leaving the clan waiting for
				// the hub's heartbeat timeout. Fires when leaving a server or quitting to
				// the menu — NOT on a dimension change, which keeps the same connection,
				// so a trip to the Nether does not drop what you reserved.
				com.chestmemory.client.clan.ClanSessionManager.releaseOnDisconnect();
			});
		EnderChestTooltip.register();
		BuildGatherHud.register();
		ChestItemIconOverlay.register();

		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			// Persist contents when a container screen closes
			ScreenEvents.remove(screen).register(closed -> ContainerScanner.onScreenClosed(client, closed));

			// Slot glow after background, before items/tooltips — so it never covers item description
			if (screen instanceof AbstractContainerScreen<?> containerScreen) {
				ScreenEvents.afterBackground(screen).register((s, graphics, mouseX, mouseY, tickProgress) -> {
					SlotHighlighter.render(containerScreen, graphics, mouseX, mouseY);
				});
			}
		});

		ChestMemoryMod.LOGGER.info("Chest Memory client ready");
	}

	private static void onEndTick(Minecraft client) {
		// Deferred settings write (runs in menus too, so pending changes always land)
		ModSettings.tick();

		if (client.player == null || client.level == null) {
			if (ChestMemoryStorage.get().liveWorldId() != null) {
				ChestMemoryStorage.get().unload();
				ChestHighlighter.clear();
			}
			return;
		}

		ChestMemoryStorage.get().ensureLoaded(client);
		MultiworldTracker.tick(client);
		ContainerScanner.tick(client);
		// Drop containers whose block is gone, so a chest someone broke stops counting
		// toward the item list and stops being highlighted.
		com.chestmemory.client.scan.ContainerVerifier.tick(client);
		ChestHighlighter.tick(client);
		BuildGatherSession.tick(client);
		com.chestmemory.client.clan.ClanSessionManager.tick(client);

		while (openPanelKey.consumeClick()) {
			var open = ClientScreens.get(client);
			if (open == null) {
				ClientScreens.set(client, new ChestMemoryScreen());
			} else if (open instanceof ChestMemoryScreen) {
				ClientScreens.set(client, null);
			}
		}

		while (clearHighlightKey.consumeClick()) {
			ChestHighlighter.clear();
			BuildGatherSession.clear();
			if (client.player != null) {
				client.player.sendSystemMessage(Component.translatable("message.chestmemory.highlight_cleared"));
			}
		}

		// Only when no GUI is open (so N/P don't fire in chat/inventory)
		if (ClientScreens.get(client) == null) {
			while (nextItemKey.consumeClick()) {
				BuildGatherSession.skipCurrentItem(client);
			}
			while (toggleStagingKey.consumeClick()) {
				com.chestmemory.client.data.StagingPickMode.toggle();
			}
		}
	}
}
