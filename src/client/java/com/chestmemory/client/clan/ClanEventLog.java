package com.chestmemory.client.clan;

import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Recent clan activity: who took what, who delivered, who came and went.
 * <p>
 * These events already existed, but only as chat messages. Chat scrolls away — and on a
 * busy server it scrolls away in seconds — so a player opening the clan screen had no way
 * to answer "who is on the glass?" or "did anyone deliver while I was mining?". The log
 * keeps the last {@link #CAPACITY} events so the screen can show them.
 * <p>
 * The hub records the history and this renders it — see {@link #fromSession}. It used to be
 * the other way round: the client watched snapshots go by and wrote down the differences,
 * which meant the feed only ever knew what happened while somebody was looking at it.
 * Relogging emptied it, switching gathers emptied it, and an evening's work done while you
 * were offline left no trace at all.
 */
public final class ClanEventLog {
	/**
	 * Enough to cover a burst of claims when several people open their lists at once,
	 * while staying small — this is a live feed, not an audit trail.
	 */
	public static final int CAPACITY = 24;

	/** Newest first, so rendering reads the head and stops at the visible row count. */
	private static final Deque<Entry> entries = new ArrayDeque<>();

	/** Which gather these events belong to, upper case, or empty when the feed is idle. */
	private static String sessionCode = "";

	private ClanEventLog() {
	}

	/**
	 * Rebuild the feed from the hub's history for this gather.
	 * <p>
	 * The hub is the single source now, which is what makes the feed survive switching
	 * gathers and relogging, and lets a player see what happened while they were offline.
	 * Rebuilding wholesale rather than appending is deliberate: the hub's list already IS the
	 * history, so merging would only risk showing an event twice.
	 * <p>
	 * Formatting happens here, never on the hub — the hub sends who/what/how many and has no
	 * business deciding what language anybody reads.
	 */
	public static synchronized void fromSession(String code, List<ClanSession.ClanEvent> events) {
		sessionCode = code == null ? "" : code.trim().toUpperCase(java.util.Locale.ROOT);
		entries.clear();
		if (events == null) {
			return;
		}
		// The hub appends oldest first; the feed reads newest first.
		for (int i = events.size() - 1; i >= 0 && entries.size() < CAPACITY; i--) {
			ClanSession.ClanEvent e = events.get(i);
			if (e == null || e.kind == null) {
				continue;
			}
			Component line = describe(e);
			if (line != null) {
				entries.addLast(new Entry(kindOf(e.kind), line, e.at));
			}
		}
	}

	/** Colour and glyph bucket for a hub event kind. */
	private static Kind kindOf(String kind) {
		return switch (kind) {
			case "claim" -> Kind.CLAIM;
			case "release", "release_all", "timeout" -> Kind.RELEASE;
			// Finishing a material off is the good end of a delivery, not a release.
			case "deliver", "done" -> Kind.DELIVER;
			case "join", "create" -> Kind.JOIN;
			case "leave", "kick" -> Kind.LEAVE;
			// Striking a material off is the host taking work away, which reads like a
			// release; nothing about it is a delivery or an arrival.
			case "exclude", "include" -> Kind.RELEASE;
			default -> Kind.JOIN;
		};
	}

	/** One feed line, or null for a kind this client does not know how to say. */
	private static @Nullable Component describe(ClanSession.ClanEvent e) {
		String who = e.who == null || e.who.isBlank() ? "?" : e.who;
		String item = e.item == null || e.item.isBlank()
			? ""
			: com.chestmemory.client.data.ChestMemoryStorage.itemDisplayName(e.item);
		return switch (e.kind) {
			case "claim" -> Component.translatable("screen.chestmemory.clan.ev_claim", who, item);
			case "release" -> Component.translatable("screen.chestmemory.clan.ev_release", who, item);
			case "timeout" -> Component.translatable("screen.chestmemory.clan.ev_timeout", who, item);
			case "release_all" ->
				Component.translatable("screen.chestmemory.clan.ev_release_all", who, e.n);
			case "deliver" ->
				Component.translatable("screen.chestmemory.clan.ev_deliver", who, e.n, item);
			case "done" ->
				Component.translatable("screen.chestmemory.clan.ev_done", who, item);
			case "join" -> Component.translatable("screen.chestmemory.clan.ev_join", who);
			case "leave" -> Component.translatable("screen.chestmemory.clan.ev_leave", who);
			case "kick" -> Component.translatable("screen.chestmemory.clan.ev_kick", who);
			case "exclude" -> Component.translatable("screen.chestmemory.clan.ev_exclude", who, item);
			case "include" -> Component.translatable("screen.chestmemory.clan.ev_include", who, item);
			case "create" -> Component.translatable("screen.chestmemory.clan.ev_create", who, e.n);
			// An unknown kind means a newer hub than this jar. Skipping the line keeps the
			// feed readable instead of printing a raw keyword at the player.
			default -> null;
		};
	}

	/** The gather this feed describes, or empty when idle. */
	public static synchronized String sessionCode() {
		return sessionCode;
	}

	/** What happened. The kind drives the colour and glyph, so the feed is scannable. */
	public enum Kind {
		/** Someone reserved a material. */
		CLAIM,
		/** A reservation went back to the pool (released, or holder disconnected). */
		RELEASE,
		/** Materials landed in the warehouse. */
		DELIVER,
		/** A member joined the session. */
		JOIN,
		/** A member left the session. */
		LEAVE
	}

	public record Entry(Kind kind, Component text, long at) {
	}

	/** Newest first. Copy, so rendering never iterates a deque being written by the IO thread. */
	public static synchronized List<Entry> recent(int max) {
		List<Entry> out = new ArrayList<>(Math.min(max, entries.size()));
		for (Entry e : entries) {
			if (out.size() >= max) {
				break;
			}
			out.add(e);
		}
		return out;
	}

	/** Every entry, newest first — the feed scrolls now, so it needs the whole log. */
	public static synchronized List<Entry> all() {
		return recent(CAPACITY);
	}

	public static synchronized boolean isEmpty() {
		return entries.isEmpty();
	}

	/**
	 * Drop everything and forget which gather this was — the session ended.
	 * <p>
	 * Releasing the code matters: the next snapshot to arrive must be treated as a fresh
	 * gather's history rather than as more of this one.
	 */
	public static synchronized void clear() {
		entries.clear();
		sessionCode = "";
	}
}
