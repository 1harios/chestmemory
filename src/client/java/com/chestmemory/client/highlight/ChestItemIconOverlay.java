package com.chestmemory.client.highlight;

import com.chestmemory.ChestMemoryMod;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.List;

/**
 * Draws the searched item icon on highlighted chests (screen-space billboard).
 */
public final class ChestItemIconOverlay {
	private static final int ICON = 16;
	private static final int HALF = ICON / 2;
	/** World Y of icon center relative to block origin — sits on the chest lid (~1 block tall). */
	private static final double ICON_Y_OFFSET = 0.92;

	private ChestItemIconOverlay() {
	}

	public static void register() {
		HudElementRegistry.addLast(ChestMemoryMod.id("chest_item_icons"), ChestItemIconOverlay::render);
	}

	private static void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		if (!ChestHighlighter.isActive()) {
			return;
		}
		if (!com.chestmemory.client.data.ModSettings.get().showChestItemIcons()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.player == null || client.level == null) {
			return;
		}
		// Don't draw over GUIs
		if (com.chestmemory.client.util.ClientScreens.get(client) != null) {
			return;
		}

		String itemId = ChestHighlighter.getHighlightedItemId();
		if (itemId == null) {
			return;
		}
		ItemStack stack = resolveStack(itemId);
		if (stack.isEmpty()) {
			return;
		}

		List<ChestHighlighter.IconMarker> markers = ChestHighlighter.iconMarkers();
		if (markers.isEmpty()) {
			return;
		}

		Camera camera = getMainCamera(client);
		if (camera == null || !camera.isInitialized()) {
			return;
		}

		int screenW = client.getWindow().getGuiScaledWidth();
		int screenH = client.getWindow().getGuiScaledHeight();
		Matrix4f matrix = camera.getViewRotationProjectionMatrix(new Matrix4f());
		Vec3 camPos = camera.position();

		int drawn = 0;
		for (ChestHighlighter.IconMarker marker : markers) {
			if (drawn >= 16) {
				break;
			}
			BlockPos pos = marker.pos();
			// Anchor on the top face of the chest (not floating high above)
			double wx = pos.getX() + 0.5;
			double wy = pos.getY() + ICON_Y_OFFSET;
			double wz = pos.getZ() + 0.5;

			int[] screen = project(matrix, camPos, wx, wy, wz, screenW, screenH);
			if (screen == null) {
				continue;
			}
			// Center icon on projected top of chest
			int sx = screen[0] - HALF;
			int sy = screen[1] - HALF;

			// Soft plate behind icon — border uses HUD accent / muted
			int pad = marker.focus() ? 2 : 1;
			int bg = marker.focus() ? 0xDD1A1208 : 0xAA101018;
			int accent = com.chestmemory.client.data.ModSettings.get().hudAccentColor();
			int border = marker.focus()
				? (0xFF000000 | accent)
				: (0x88000000 | accent);
			graphics.fill(sx - pad, sy - pad, sx + ICON + pad, sy + ICON + pad, bg);
			// border
			graphics.fill(sx - pad, sy - pad, sx + ICON + pad, sy - pad + 1, border);
			graphics.fill(sx - pad, sy + ICON + pad - 1, sx + ICON + pad, sy + ICON + pad, border);
			graphics.fill(sx - pad, sy - pad, sx - pad + 1, sy + ICON + pad, border);
			graphics.fill(sx + ICON + pad - 1, sy - pad, sx + ICON + pad, sy + ICON + pad, border);

			graphics.item(stack, sx, sy);
			drawn++;
		}
	}

	/**
	 * @return [x,y] gui coords or null if behind camera / off far
	 */
	private static int[] project(
		Matrix4f viewProj,
		Vec3 camPos,
		double wx, double wy, double wz,
		int screenW, int screenH
	) {
		Vector4f v = new Vector4f(
			(float) (wx - camPos.x),
			(float) (wy - camPos.y),
			(float) (wz - camPos.z),
			1.0F
		);
		viewProj.transform(v);
		if (v.w <= 0.05F) {
			return null; // behind camera
		}
		float ndcX = v.x / v.w;
		float ndcY = v.y / v.w;
		float ndcZ = v.z / v.w;
		// Clip a bit outside NDC so near-edge icons still show
		if (ndcZ < -1.1F || ndcZ > 1.1F) {
			return null;
		}
		if (ndcX < -1.2F || ndcX > 1.2F || ndcY < -1.2F || ndcY > 1.2F) {
			return null;
		}
		int sx = Math.round((ndcX * 0.5F + 0.5F) * screenW);
		int sy = Math.round((1.0F - (ndcY * 0.5F + 0.5F)) * screenH);
		return new int[]{sx, sy};
	}

	private static ItemStack resolveStack(String itemId) {
		ItemStack keyed = com.chestmemory.client.data.ItemStackKeys.toStack(itemId);
		if (!keyed.isEmpty() && !keyed.is(Items.AIR)) {
			return keyed;
		}
		Identifier id = Identifier.tryParse(com.chestmemory.client.data.ItemStackKeys.baseId(itemId));
		if (id == null) {
			return ItemStack.EMPTY;
		}
		Item item = BuiltInRegistries.ITEM.getValue(id);
		if (item == null || item == Items.AIR) {
			return ItemStack.EMPTY;
		}
		return new ItemStack(item);
	}

	/**
	 * 26.2: {@code gameRenderer.mainCamera()}
	 * 26.1.x: {@code gameRenderer.getMainCamera()}
	 */
	private static Camera getMainCamera(Minecraft client) {
		if (client == null || client.gameRenderer == null) {
			return null;
		}
		try {
			// Prefer 26.2 method name if present
			return (Camera) client.gameRenderer.getClass()
				.getMethod("mainCamera")
				.invoke(client.gameRenderer);
		} catch (ReflectiveOperationException ignored) {
		}
		try {
			return (Camera) client.gameRenderer.getClass()
				.getMethod("getMainCamera")
				.invoke(client.gameRenderer);
		} catch (ReflectiveOperationException ignored) {
		}
		return null;
	}
}
