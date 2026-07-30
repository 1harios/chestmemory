package com.chestmemory;

import com.chestmemory.client.clan.ClanSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the members panel says a player is collecting.
 * <p>
 * The reported bug, in one sentence: take glass, take cobblestone, deliver all the glass —
 * and the panel names the glass for the rest of the evening. Two causes met in the middle.
 * The hub never released a claim when a delivery completed a material, and the panel picks
 * a member's <em>earliest</em> claim, so the finished glass stayed claimed and stayed
 * earliest. Both are fixed, and both are covered here.
 * <p>
 * These run the real methods rather than reading their source: {@code ClanSession} is plain
 * fields and loops with no Minecraft in reach, which is exactly the kind of logic the test
 * classpath was kept clean for.
 */
class ClaimFocusTest {
	private static final String SESSION_SRC =
		"src/client/java/com/chestmemory/client/clan/ClanSession.java";
	private static final String PANEL =
		"src/client/java/com/chestmemory/client/gui/ClanGatherScreen.java";
	private static final String HUB = "hub/clan_hub.py";
	private static final String PHP = "hub/public/index.php";
	private static final String FEED =
		"src/client/java/com/chestmemory/client/clan/ClanEventLog.java";
	private static final String RU = "src/main/resources/assets/chestmemory/lang/ru_ru.json";
	private static final String EN = "src/main/resources/assets/chestmemory/lang/en_us.json";

	private static final String ME = "11111111-1111-1111-1111-111111111111";
	private static final String OTHER = "22222222-2222-2222-2222-222222222222";

	private static String read(String path) throws Exception {
		return Files.readString(Path.of(path));
	}

	/** A session with one member, so lastDoneOf can resolve their name from the roster. */
	private static ClanSession session(String uuid, String name) {
		ClanSession s = new ClanSession();
		ClanSession.ClanMember m = new ClanSession.ClanMember();
		m.uuid = uuid;
		m.name = name;
		s.members.add(m);
		return s;
	}

	private static ClanSession.ClanMaterial add(
		ClanSession s, String itemId, int need, int delivered
	) {
		ClanSession.ClanMaterial mat = new ClanSession.ClanMaterial();
		mat.need = need;
		mat.delivered = delivered;
		s.materials.put(itemId, mat);
		return mat;
	}

	private static ClanSession.ClanMaterial claim(
		ClanSession.ClanMaterial mat, String uuid, String name, long at
	) {
		mat.claimedBy = uuid;
		mat.claimedName = name;
		mat.claimedAt = at;
		return mat;
	}

	@Nested
	@DisplayName("The panel follows what is still unfinished")
	class Focus {
		@Test
		@DisplayName("The reported bug: finishing the glass moves the row to the cobblestone")
		void finishedClaimIsSkipped() {
			ClanSession s = session(ME, "Karandash");
			// Exactly the sequence from the report, including the claim order.
			claim(add(s, "minecraft:glass", 1200, 1200), ME, "Karandash", 1000L);
			claim(add(s, "minecraft:cobblestone", 640, 0), ME, "Karandash", 2000L);
			assertEquals(
				"minecraft:cobblestone", s.firstClaimOf(ME),
				"the glass is done — naming it is what froze the row"
			);
		}

		@Test
		@DisplayName("Unfinished claims still go in click order, earliest first")
		void earliestUnfinishedWins() {
			ClanSession s = session(ME, "Karandash");
			claim(add(s, "minecraft:glass", 1200, 300), ME, "Karandash", 1000L);
			claim(add(s, "minecraft:cobblestone", 640, 0), ME, "Karandash", 2000L);
			assertEquals("minecraft:glass", s.firstClaimOf(ME));
		}

		@Test
		@DisplayName("Somebody else's claim is never ours")
		void otherMembersClaimsIgnored() {
			ClanSession s = session(ME, "Karandash");
			claim(add(s, "minecraft:glass", 100, 0), OTHER, "Sonya", 1000L);
			assertNull(s.firstClaimOf(ME));
			assertEquals("minecraft:glass", s.firstClaimOf(OTHER));
		}

		@Test
		@DisplayName("An excluded material is nobody's job, finished or not")
		void excludedSkipped() {
			ClanSession s = session(ME, "Karandash");
			ClanSession.ClanMaterial struck = claim(
				add(s, "minecraft:glass", 100, 0), ME, "Karandash", 1000L);
			struck.excluded = true;
			assertNull(s.firstClaimOf(ME));
		}

		@Test
		@DisplayName("Every claim finished means no current item, not a stale one")
		void allDoneGivesNothing() {
			ClanSession s = session(ME, "Karandash");
			claim(add(s, "minecraft:glass", 100, 100), ME, "Karandash", 1000L);
			claim(add(s, "minecraft:cobblestone", 64, 64), ME, "Karandash", 2000L);
			assertNull(s.firstClaimOf(ME), "the fallback is lastDoneOf, not a finished claim");
		}

		@Test
		@DisplayName("A gather with no need recorded is not treated as finished")
		void zeroNeedIsNotDone() {
			// need == 0 would make "delivered >= need" true for an untouched material and
			// silently hide it. Old sessions can carry a zero need.
			ClanSession s = session(ME, "Karandash");
			claim(add(s, "minecraft:glass", 0, 0), ME, "Karandash", 1000L);
			assertEquals("minecraft:glass", s.firstClaimOf(ME));
		}

		@Test
		@DisplayName("Claims from before the hub stamped them keep their old order")
		void legacyClaimsUnchanged() {
			ClanSession s = session(ME, "Karandash");
			claim(add(s, "minecraft:glass", 100, 0), ME, "Karandash", 0L);
			claim(add(s, "minecraft:cobblestone", 100, 0), ME, "Karandash", 5000L);
			assertEquals(
				"minecraft:glass", s.firstClaimOf(ME),
				"an unstamped claim sorts ahead, which is the documented old behaviour"
			);
		}
	}

	@Nested
	@DisplayName("With nothing left to carry, the row credits the last thing finished")
	class LastDone {
		@Test
		@DisplayName("The most recently completed material wins")
		void mostRecentDone() {
			ClanSession s = session(ME, "Karandash");
			ClanSession.ClanMaterial glass = add(s, "minecraft:glass", 100, 100);
			glass.lastDeliveredBy = "Karandash";
			glass.lastDeliveredAt = 1000L;
			ClanSession.ClanMaterial cobble = add(s, "minecraft:cobblestone", 64, 64);
			cobble.lastDeliveredBy = "Karandash";
			cobble.lastDeliveredAt = 9000L;
			assertEquals("minecraft:cobblestone", s.lastDoneOf(ME));
		}

		@Test
		@DisplayName("Credit follows the name the hub recorded, resolved from the roster")
		void creditedByName() {
			// lastDeliveredBy is a name, not a uuid, so the lookup has to go through the
			// roster. Passing the uuid straight into the comparison would match nothing.
			ClanSession s = session(ME, "Karandash");
			ClanSession.ClanMaterial glass = add(s, "minecraft:glass", 100, 100);
			glass.lastDeliveredBy = "Karandash";
			glass.lastDeliveredAt = 1000L;
			assertEquals("minecraft:glass", s.lastDoneOf(ME));
			assertNull(s.lastDoneOf(OTHER), "not on the roster, so nothing to credit");
		}

		@Test
		@DisplayName("Somebody else's delivery is not our achievement")
		void notOthersWork() {
			ClanSession s = session(ME, "Karandash");
			ClanSession.ClanMaterial glass = add(s, "minecraft:glass", 100, 100);
			glass.lastDeliveredBy = "Sonya";
			glass.lastDeliveredAt = 1000L;
			assertNull(s.lastDoneOf(ME));
		}

		@Test
		@DisplayName("Half-delivered and excluded materials do not count as finished")
		void onlyFinishedCounts() {
			ClanSession s = session(ME, "Karandash");
			ClanSession.ClanMaterial part = add(s, "minecraft:glass", 100, 40);
			part.lastDeliveredBy = "Karandash";
			part.lastDeliveredAt = 5000L;
			ClanSession.ClanMaterial struck = add(s, "minecraft:cobblestone", 64, 64);
			struck.lastDeliveredBy = "Karandash";
			struck.lastDeliveredAt = 6000L;
			struck.excluded = true;
			assertNull(s.lastDoneOf(ME));
		}

		@Test
		@DisplayName("A member who has finished nothing gets no credit line")
		void nothingFinished() {
			ClanSession s = session(ME, "Karandash");
			add(s, "minecraft:glass", 100, 0);
			assertNull(s.lastDoneOf(ME));
		}
	}

	@Nested
	@DisplayName("The hub stops reserving what is already collected")
	class HubReleasesOnDone {
		@Test
		@DisplayName("The delivery that completes a material drops its claim")
		void releasedOnCompletion() throws Exception {
			String hub = read(HUB);
			assertTrue(
				hub.contains("if new_delivered >= int(m.get(\"need\", 0)) and m.get(\"claimedBy\"):"),
				"only the completing delivery releases, and only if something held it"
			);
			assertTrue(
				hub.contains("_event(sess, \"done\","),
				"the feed should say who finished it, not go quiet"
			);
		}

		@Test
		@DisplayName("The PHP mirror does the same, so the two hubs cannot drift")
		void phpMirrors() throws Exception {
			String php = read(PHP);
			assertTrue(php.contains("if ($newDelivered >= $need && ($mat['claimedBy'] ?? null)) {"));
			assertTrue(php.contains("push_event($sess, 'done',"));
			assertTrue(
				php.contains("push_event($sess, 'deliver', $name, $item, $newDelivered - $del);"),
				"PHP was not recording deliveries at all, which the Python hub always did"
			);
		}

		@Test
		@DisplayName("An unknown kind is skipped, so an older jar survives the new event")
		void unknownKindTolerated() throws Exception {
			String feed = read(FEED);
			assertTrue(feed.contains("case \"deliver\", \"done\" -> Kind.DELIVER;"));
			assertTrue(feed.contains("case \"done\" ->"), "and the feed can say it");
			assertTrue(feed.contains("default -> null;"), "the tolerance this relies on");
		}
	}

	@Nested
	@DisplayName("The panel and the HUD cannot disagree")
	class PanelWiring {
		@Test
		@DisplayName("Our own row follows the gather queue, not the hub's timestamps")
		void ownRowFollowsQueue() throws Exception {
			String panel = read(PANEL);
			assertTrue(
				panel.contains("com.chestmemory.client.litematica.BuildGatherSession.currentItemId()"),
				"the item the player is watching themselves collect is the honest answer"
			);
			assertFalse(
				panel.contains("// Our own row defers to the click order recorded locally"),
				"that comment promised local click order while the code read the hub"
			);
		}

		@Test
		@DisplayName("A finished claim is skipped in the panel too, for old gathers")
		void panelSkipsFinished() throws Exception {
			String panel = read(PANEL);
			assertTrue(
				panel.contains("if (mat.need > 0 && mat.delivered >= mat.need) {"),
				"a gather made before the hub released claims still carries stale ones"
			);
		}

		@Test
		@DisplayName("The row can show a tick, and says so in both languages")
		void tickRendered() throws Exception {
			String panel = read(PANEL);
			assertTrue(panel.contains("String itemId, String name, int delivered, int need, boolean done"));
			assertTrue(panel.contains("claim.done()"), "the row has to branch on it");
			assertTrue(panel.contains("screen.chestmemory.clan.finished"));
			for (String file : new String[]{RU, EN}) {
				String lang = read(file);
				for (String key : new String[]{
					"screen.chestmemory.clan.finished",
					"screen.chestmemory.clan.ev_done",
				}) {
					assertTrue(lang.contains('"' + key + '"'), file + " is missing " + key);
				}
			}
			assertTrue(read(RU).contains("\"screen.chestmemory.clan.finished\": \"✔ %s\""),
				"the tick is the whole point of the label");
		}

		@Test
		@DisplayName("lastDeliveredAt is read off the wire, or ordering has nothing to sort on")
		void timestampParsed() throws Exception {
			assertTrue(
				read(SESSION_SRC).contains("public long lastDeliveredAt;"),
				"Gson maps this by name; without the field every finish looks simultaneous"
			);
		}
	}
}
