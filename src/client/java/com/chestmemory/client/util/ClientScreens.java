package com.chestmemory.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Screen access that works on both Minecraft 26.1.x and 26.2+.
 * <ul>
 *   <li>26.2+: {@code client.gui.screen()} / {@code client.gui.setScreen(...)}</li>
 *   <li>26.1.x: {@code client.screen} field / {@code client.setScreen(...)}</li>
 * </ul>
 */
public final class ClientScreens {
	private static final @Nullable Method GUI_GET_SCREEN;
	private static final @Nullable Method GUI_SET_SCREEN;
	private static final @Nullable Field MC_SCREEN_FIELD;
	private static final @Nullable Method MC_SET_SCREEN;

	static {
		Method guiGet = null;
		Method guiSet = null;
		Field mcField = null;
		Method mcSet = null;

		try {
			guiGet = Gui.class.getMethod("screen");
		} catch (NoSuchMethodException ignored) {
		}
		try {
			guiSet = Gui.class.getMethod("setScreen", Screen.class);
		} catch (NoSuchMethodException ignored) {
		}
		try {
			mcField = Minecraft.class.getField("screen");
		} catch (NoSuchFieldException ignored) {
		}
		try {
			mcSet = Minecraft.class.getMethod("setScreen", Screen.class);
		} catch (NoSuchMethodException ignored) {
		}

		GUI_GET_SCREEN = guiGet;
		GUI_SET_SCREEN = guiSet;
		MC_SCREEN_FIELD = mcField;
		MC_SET_SCREEN = mcSet;
	}

	private ClientScreens() {
	}

	public static @Nullable Screen get(Minecraft client) {
		if (client == null) {
			return null;
		}
		try {
			if (GUI_GET_SCREEN != null && client.gui != null) {
				return (Screen) GUI_GET_SCREEN.invoke(client.gui);
			}
			if (MC_SCREEN_FIELD != null) {
				return (Screen) MC_SCREEN_FIELD.get(client);
			}
		} catch (ReflectiveOperationException e) {
			// fall through
		}
		return null;
	}

	public static void set(Minecraft client, @Nullable Screen screen) {
		if (client == null) {
			return;
		}
		try {
			if (GUI_SET_SCREEN != null && client.gui != null) {
				GUI_SET_SCREEN.invoke(client.gui, screen);
				return;
			}
			if (MC_SET_SCREEN != null) {
				MC_SET_SCREEN.invoke(client, screen);
			}
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException("Failed to open/close screen (MC 26.1 / 26.2 compatibility)", e);
		}
	}

	public static boolean isOpen(Minecraft client) {
		return get(client) != null;
	}
}
