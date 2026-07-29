package com.chestmemory.client.clan;

import net.minecraft.network.chat.Component;

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
 * Client-side and per-session: the hub sends state, not history, and the log is rebuilt
 * from the diffs the client already computes when polling. The feed describes exactly one
 * gather and knows which one — see {@link #forSession}.
 */
public final class ClanEventLog {
	/**
	 * Enough to cover a burst of claims when several people open their lists at once,
	 * while staying small — this is a live feed, not an audit trail.
	 */
	public static final int CAPACITY = 24;

	/** Newest first, so rendering reads the head and stops at the visible row count. */
	private static final Deque<Entry> entries = new ArrayDeque<>();

	/**
	 * Which gather these events belong to, upper case, or empty when the feed is idle.
	 * <p>
	 * The feed used to be cleared only by the paths that END a session — leaving, being
	 * kicked, a gather that vanished. Switching between two gathers goes through join
	 * instead, which cleared nothing, so the house build's feed opened showing the farm's
	 * claims. Owning the code closes that at the source: every path that changes the
	 * followed gather resets the feed, including paths not written yet.
	 */
	private static String sessionCode = "";

	private ClanEventLog() {
	}

	/**
	 * Point the feed at a gather, discarding another gather's events.
	 * <p>
	 * The same code twice is deliberately a no-op: this is called from the poll, and
	 * clearing on every snapshot would wipe the feed three times a second. Coming back to a
	 * gather later starts its feed empty rather than resurrecting stale rows — those events
	 * were true minutes ago, and the hub keeps no history to rebuild them from.
	 */
	public static synchronized void forSession(String code) {
		String key = code == null ? "" : code.trim().toUpperCase(java.util.Locale.ROOT);
		if (key.equals(sessionCode)) {
			return;
		}
		sessionCode = key;
		entries.clear();
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

	public static synchronized void add(Kind kind, Component text) {
		entries.addFirst(new Entry(kind, text, System.currentTimeMillis()));
		while (entries.size() > CAPACITY) {
			entries.removeLast();
		}
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
	 * Releasing the code matters: leaving a gather and rejoining it has to start a clean
	 * feed, and a retained code would make {@link #forSession} treat the rejoin as "same
	 * gather, nothing to do".
	 */
	public static synchronized void clear() {
		entries.clear();
		sessionCode = "";
	}
}
