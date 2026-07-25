package com.chestmemory.client.clan;

import com.chestmemory.ChestMemoryMod;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import org.jspecify.annotations.Nullable;

/**
 * Proves to the clan hub which Minecraft account this client is.
 * <p>
 * The hub used to trust whatever {@code uuid} the request body claimed, so any member
 * could act as any other. This runs the same handshake the game performs when joining
 * an online-mode server:
 * <ol>
 *   <li>ask the hub for a nonce</li>
 *   <li>call Mojang's {@code joinServer} with it — the access token never leaves the
 *       client, only Mojang sees it</li>
 *   <li>tell the hub our name; it asks Mojang {@code hasJoined} and, if Mojang agrees,
 *       returns a session token bound to our real UUID</li>
 * </ol>
 * The session token then travels in {@code X-Clan-Session} on every request.
 * <p>
 * All methods block on network I/O and must be called from the clan IO executor, never
 * from the client thread.
 */
public final class ClanAuth {
	/** Session token for the configured hub, or null when not authenticated yet. */
	private static volatile @Nullable String sessionToken;
	/** Hub the token belongs to — switching hubs must invalidate it. */
	private static volatile @Nullable String tokenHubUrl;

	private ClanAuth() {
	}

	public static @Nullable String sessionToken(String hubUrl) {
		return hubUrl.equals(tokenHubUrl) ? sessionToken : null;
	}

	public static void clear() {
		sessionToken = null;
		tokenHubUrl = null;
	}

	/**
	 * Authenticate against {@code client} if not already done for this hub.
	 *
	 * @return true when a session token is available afterwards
	 */
	public static boolean ensureAuthenticated(ClanHubClient client, Minecraft mc) {
		String hubUrl = client.baseUrl();
		if (sessionToken(hubUrl) != null) {
			return true;
		}
		return authenticate(client, mc);
	}

	/** Force a fresh handshake (used when the hub reports the token expired). */
	public static boolean authenticate(ClanHubClient client, Minecraft mc) {
		User user = mc.getUser();
		if (user == null) {
			return false;
		}

		ClanHubClient.Result<String> challenge = client.authChallenge();
		if (!challenge.ok || challenge.value == null) {
			ChestMemoryMod.LOGGER.warn("Clan auth: no challenge from hub ({})", challenge.error);
			return false;
		}
		String nonce = challenge.value;

		try {
			// Tells Mojang "this account is joining a server identified by <nonce>".
			// The hub then verifies that claim independently via hasJoined.
			mc.services().sessionService().joinServer(user.getProfileId(), user.getAccessToken(), nonce);
		} catch (Exception e) {
			// Offline/cracked launchers and Mojang outages land here. The hub can still be
			// used with require_auth off, so this is a warning rather than an error.
			ChestMemoryMod.LOGGER.warn("Clan auth: joinServer failed ({})", e.toString());
			return false;
		}

		JsonObject body = new JsonObject();
		body.addProperty("name", user.getName());
		body.addProperty("nonce", nonce);
		ClanHubClient.Result<String> verify = client.authVerify(body);
		if (!verify.ok || verify.value == null) {
			ChestMemoryMod.LOGGER.warn("Clan auth: hub rejected verification ({})", verify.error);
			return false;
		}

		sessionToken = verify.value;
		tokenHubUrl = client.baseUrl();
		ChestMemoryMod.LOGGER.info("Clan auth: verified as {}", user.getName());
		return true;
	}
}
