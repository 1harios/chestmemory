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
 * from the diffs the client already computes when polling. Cleared when the session ends
 * so a new gather never shows the previous one's activity.
 */
public final class ClanEventLog {
	/**
	 * Enough to cover a burst of claims when several people open their lists at once,
	 * while staying small — this is a live feed, not an audit trail.
	 */
	public static final int CAPACITY = 24;

	/** Newest first, so rendering reads the head and stops at the visible row count. */
	private static final Deque<Entry> entries = new ArrayDeque<>();

	private ClanEventLog() {
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

	public static synchronized void clear() {
		entries.clear();
	}
}
