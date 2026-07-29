package com.chestmemory;

import com.chestmemory.client.clan.ClanSession;
import com.chestmemory.client.data.BulkAmount;
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
 * The six gather complaints, each pinned by the thing that actually broke.
 * <p>
 * Where the logic is Minecraft-free it is exercised for real ({@link ClanSession} and
 * {@link BulkAmount} import nothing from the game); where it lives inside a screen or the
 * hub, the source is inspected, which is the convention the sibling suites established.
 */
class GatherFixesTest {
	private static final String CLAN_SCREEN =
		"src/client/java/com/chestmemory/client/gui/ClanGatherScreen.java";
	private static final String MANAGER =
		"src/client/java/com/chestmemory/client/clan/ClanSessionManager.java";
	private static final String HUB_CLIENT =
		"src/client/java/com/chestmemory/client/clan/ClanHubClient.java";
	private static final String CLIENT =
		"src/client/java/com/chestmemory/client/ChestMemoryClient.java";
	private static final String GATHER_SESSION =
		"src/client/java/com/chestmemory/client/litematica/BuildGatherSession.java";
	private static final String HUB = "hub/clan_hub.py";
	private static final String HUB_PHP = "hub/public/index.php";
	private static final String RU = "src/main/resources/assets/chestmemory/lang/ru_ru.json";
	private static final String EN = "src/main/resources/assets/chestmemory/lang/en_us.json";

	private static String read(String path) throws Exception {
		return Files.readString(Path.of(path));
	}

	private static String body(String src, String signature) {
		int at = src.indexOf(signature);
		assertTrue(at > 0, "not found: " + signature);
		return src.substring(at, src.indexOf("\n\t}", at));
	}

	/**
	 * One Python function's source. Braces do not delimit it, so the slice runs to the next
	 * definition at either indent level — a method's sibling, or the next module-level def.
	 */
	private static String pyBody(String src, String signature) {
		int at = src.indexOf(signature);
		assertTrue(at > 0, "not found: " + signature);
		int method = src.indexOf("\n    def ", at + 1);
		int top = src.indexOf("\ndef ", at + 1);
		int end = method < 0 ? top : (top < 0 ? method : Math.min(method, top));
		return src.substring(at, end < 0 ? src.length() : end);
	}

	private static ClanSession.ClanMaterial mat(int need, int delivered) {
		ClanSession.ClanMaterial m = new ClanSession.ClanMaterial();
		m.need = need;
		m.delivered = delivered;
		return m;
	}

	private static ClanSession.ClanMaterial claimed(int need, String uuid, long at) {
		ClanSession.ClanMaterial m = mat(need, 0);
		m.claimedBy = uuid;
		m.claimedName = "Digger";
		m.claimedAt = at;
		return m;
	}

	@Nested
	@DisplayName("1. The members tab names the material the collector is actually on")
	class ClaimOrder {
		private static final String ME = "aaaa-bbbb";

		@Test
		@DisplayName("Glass claimed before stone is the claim that shows")
		void earliestClaimWins() {
			ClanSession s = new ClanSession();
			// Insertion order deliberately opposite to click order: this is the shape of the
			// bug — the hub's map order said stone, the player had clicked glass first.
			s.materials.put("minecraft:stone", claimed(640, ME, 2_000L));
			s.materials.put("minecraft:glass", claimed(128, ME, 1_000L));
			assertEquals("minecraft:glass", s.firstClaimOf(ME));
		}

		@Test
		@DisplayName("Map order still decides for claims made before timestamps existed")
		void legacyClaimsKeepMapOrder() {
			ClanSession s = new ClanSession();
			s.materials.put("minecraft:stone", claimed(640, ME, 0L));
			s.materials.put("minecraft:glass", claimed(128, ME, 0L));
			assertEquals(
				"minecraft:stone", s.firstClaimOf(ME),
				"a session stored before claimedAt must not be reshuffled by the upgrade"
			);
		}

		@Test
		@DisplayName("Someone else's claims are not mine, and no claim means nothing")
		void onlyOwnClaims() {
			ClanSession s = new ClanSession();
			s.materials.put("minecraft:glass", claimed(128, "someone-else", 1_000L));
			s.materials.put("minecraft:stone", mat(640, 0));
			assertNull(s.firstClaimOf(ME));
			assertNull(s.firstClaimOf(null));
			assertNull(s.firstClaimOf(""));
		}

		@Test
		@DisplayName("An excluded material is never reported as what someone is carrying")
		void excludedIsNotCarried() {
			ClanSession s = new ClanSession();
			ClanSession.ClanMaterial glass = claimed(128, ME, 1_000L);
			glass.excluded = true;
			s.materials.put("minecraft:glass", glass);
			s.materials.put("minecraft:stone", claimed(640, ME, 2_000L));
			assertEquals("minecraft:stone", s.firstClaimOf(ME));
		}

		@Test
		@DisplayName("The hub stamps every claim, and clears the stamp with the claim")
		void hubRecordsClaimTime() throws Exception {
			String hub = read(HUB);
			assertTrue(
				pyBody(hub, "def _claim(").contains("m[\"claimedAt\"] = _now()"),
				"without a hub-side timestamp no client can know which claim came first"
			);
			assertTrue(hub.contains("def _clear_claim("), "one place releases a claim");
			assertTrue(
				pyBody(hub, "def _clear_claim(").contains("mat[\"claimedAt\"] = 0"),
				"a released material must not keep a timestamp that outlives its claim"
			);
			// Six calls: the stale sweep, unclaim, leave, kick, release_claims — and
			// exclude, which releases the claim on a material it strikes off. The def line
			// itself is not a call, hence the -1 twice.
			assertEquals(
				6, hub.split("_clear_claim\\(", -1).length - 2,
				"every release path shares the helper, or one of them forgets a field"
			);
			assertTrue(
				read(HUB_PHP).contains("$mat['claimedAt'] = now_ms();"),
				"the PHP hub writes the same sessions.json and cannot disagree about the shape"
			);
		}

		@Test
		@DisplayName("The panel reads the claim order, not the materials map")
		void panelUsesClaimOrder() throws Exception {
			String screen = read(CLAN_SCREEN);
			String members = body(screen, "private void drawMembers(");
			assertTrue(
				members.contains("takenAt") && members.contains("mat.claimedAt"),
				"the roster must pick the earliest claim, not the first in map order"
			);
			assertFalse(
				members.contains("claims.putIfAbsent("),
				"putIfAbsent kept whichever material the hub happened to store first"
			);
			assertTrue(
				members.contains("ClanSessionManager.firstClaimOf("),
				"our own row follows the click order the HUD follows, so the two cannot disagree"
			);
			assertTrue(
				body(read(GATHER_SESSION), "private static @Nullable String firstOwnClaim(")
					.contains("byClaimTime"),
				"the queue's fallback must order by claim time, not by map order"
			);
		}
	}

	@Nested
	@DisplayName("2. A player who is standing right there is not offline")
	class Presence {
		@Test
		@DisplayName("The away timer runs against the hub's clock, and a heartbeat resets it")
		void heartbeatClearsAway() {
			ClanSession s = new ClanSession();
			ClanSession.ClanMember m = new ClanSession.ClanMember();
			m.uuid = "aaaa";
			m.name = "Digger";
			s.members.add(m);

			// The bug: lastSeen frozen at the last revision-bumping change while the hub kept
			// answering "unchanged". Four minutes on, a working player read as offline.
			long hubNow = 10_000_000L;
			m.lastSeen = hubNow - 240_000L;
			s.now = hubNow;
			s.receivedAt = System.currentTimeMillis();
			assertTrue(s.isMemberAway(m), "four minutes without a heartbeat is genuinely away");

			// What applying the stub does: hub clock forward, lastSeen forward with it.
			s.now = hubNow + 240_000L;
			s.receivedAt = System.currentTimeMillis();
			m.lastSeen = s.now;
			assertFalse(
				s.isMemberAway(m),
				"the stub proves the hub just heard from this client — it is not away"
			);
		}

		@Test
		@DisplayName("A member the hub never saw is not reported away")
		void unknownLastSeenIsNotAway() {
			ClanSession s = new ClanSession();
			ClanSession.ClanMember m = new ClanSession.ClanMember();
			m.lastSeen = 0;
			assertFalse(s.isMemberAway(m));
		}

		@Test
		@DisplayName("The hub's quiet-poll stub carries the clock and the roster's freshness")
		void stubCarriesHeartbeat() throws Exception {
			String hub = read(HUB);
			int stub = hub.indexOf("\"unchanged\": True");
			assertTrue(stub > 0, "the since-poll stub is gone");
			String around = hub.substring(stub, Math.min(hub.length(), stub + 400));
			assertTrue(
				around.contains("\"seen\""),
				"without lastSeen in the stub the client counts its away timer against a "
					+ "frozen value and marks everyone offline"
			);
			assertTrue(around.contains("\"now\": _now()"), "the stub must carry the hub clock");
		}

		@Test
		@DisplayName("The client applies the stub instead of discarding it")
		void clientAppliesHeartbeat() throws Exception {
			assertTrue(
				body(read(HUB_CLIENT), "private Result<ClanSession> parse(")
					.contains("Result.unchanged(heartbeat(probe))"),
				"the stub's payload was thrown away — that is what froze lastSeen"
			);
			String manager = read(MANAGER);
			assertTrue(
				body(manager, "private static void pollAsync(").contains("applyHeartbeat("),
				"a bare return on an unchanged poll is what made everyone go offline"
			);
			String apply = body(manager, "private static void applyHeartbeat(");
			assertTrue(apply.contains("cur.now = hubNow"), "the hub's clock must move forward");
			assertTrue(
				apply.contains("m.lastSeen = Math.max(m.lastSeen, hubNow)"),
				"a successful poll is proof the hub accepted OUR heartbeat, roster or not"
			);
		}
	}

	@Nested
	@DisplayName("3. ESC closes the gather instead of opening the item list")
	class Escape {
		@Test
		@DisplayName("The key-opened gather has no parent to navigate to")
		void keybindOpensStandalone() throws Exception {
			String client = read(CLIENT);
			assertTrue(
				client.contains("new com.chestmemory.client.gui.ClanGatherScreen()"),
				"the panel key must open the gather with no parent"
			);
			assertFalse(
				client.contains("ClanGatherScreen(new ChestMemoryScreen())"),
				"a synthetic parent is exactly what ESC then navigated into"
			);
		}

		@Test
		@DisplayName("A null parent is a supported state, not an accident")
		void nullParentIsDeclared() throws Exception {
			String screen = read(CLAN_SCREEN);
			assertTrue(
				screen.contains("private final @org.jspecify.annotations.Nullable Screen parent"),
				"the standalone case is expressed as a nullable parent"
			);
			assertTrue(
				screen.contains("public ClanGatherScreen() {"),
				"a no-arg constructor is the standalone entry point"
			);
		}

		@Test
		@DisplayName("Closing to the world and stepping back are different actions")
		void closeAndBackAreSeparate() throws Exception {
			String screen = read(CLAN_SCREEN);
			assertTrue(
				body(screen, "public void onClose()").contains("closeToWorld()"),
				"ESC with no parent has to give the player the world back"
			);
			assertTrue(
				body(screen, "private void closeToWorld()")
					.contains("ClientScreens.set(this.minecraft, null)"),
				"closing to the world means no screen at all"
			);
			assertTrue(
				body(screen, "private void goBack()").contains("new ChestMemoryScreen()"),
				"«Назад» keeps the item list reachable while the key goes straight to the gather"
			);
			assertFalse(
				screen.contains("this::onClose"),
				"the back rows route through goBack — otherwise ESC and Back are one action again"
			);
		}
	}

	@Nested
	@DisplayName("4 & 6. The grid is a container, and the tooltip does the arithmetic")
	class GridAndTooltip {
		@Test
		@DisplayName("1728 of a 64-stack item is exactly one shulker box")
		void oneBox() {
			BulkAmount b = BulkAmount.of(1728, 64);
			assertEquals(1, b.boxes());
			assertEquals(0, b.stacks());
			assertEquals(0, b.items());
			assertEquals(27, b.totalStacks());
			assertEquals(1728, BulkAmount.boxCapacity(64));
		}

		@Test
		@DisplayName("A messy amount splits into boxes, stacks and loose items")
		void mixedAmount() {
			// 1728 + 3·64 + 5
			BulkAmount b = BulkAmount.of(1728 + 192 + 5, 64);
			assertEquals(1, b.boxes());
			assertEquals(3, b.stacks());
			assertEquals(5, b.items());
			assertEquals(30, b.totalStacks(), "30 whole stacks, ignoring the box split");
			assertEquals(5, b.looseAfterStacks());
		}

		@Test
		@DisplayName("Stack size comes from the item: 16-stack and unstackable differ")
		void respectsStackSize() {
			assertEquals(432, BulkAmount.boxCapacity(16), "27 × 16 — ender pearls, snowballs");
			assertEquals(27, BulkAmount.boxCapacity(1), "27 shulker boxes fill one box");
			BulkAmount pearls = BulkAmount.of(432, 16);
			assertEquals(1, pearls.boxes());
			assertEquals(0, pearls.stacks());
			BulkAmount wrong = BulkAmount.of(432, 64);
			assertEquals(0, wrong.boxes(), "computing against 64 is off by a factor of four");
		}

		@Test
		@DisplayName("Below one box, and below one stack, nothing is invented")
		void smallAmounts() {
			BulkAmount half = BulkAmount.of(864, 64);
			assertFalse(half.hasBox(), "half a box is not something anyone can carry");
			assertTrue(half.hasStack());
			assertEquals(13, half.stacks());
			assertEquals(32, half.items());

			BulkAmount few = BulkAmount.of(7, 64);
			assertFalse(few.hasStack());
			assertEquals(7, few.items());
		}

		@Test
		@DisplayName("Zero, negative and broken stack sizes cannot divide by zero")
		void degenerateInput() {
			BulkAmount zero = BulkAmount.of(0, 64);
			assertEquals(0, zero.boxes());
			assertEquals(0, zero.items());
			assertEquals(0, BulkAmount.of(-500, 64).count(), "a negative count reads as none");
			assertEquals(1, BulkAmount.of(10, 0).perStack(), "a zero stack size is clamped to 1");
			BulkAmount unstackable = BulkAmount.of(10, -3);
			assertEquals(10, unstackable.stacks(), "at one per stack, ten items are ten stacks");
			assertEquals(0, unstackable.items(), "and nothing is left loose");
		}

		@Test
		@DisplayName("Empty slots are painted across the whole tray, not just where items reach")
		void trayIsFilled() throws Exception {
			String grid = body(read(CLAN_SCREEN), "private int drawMaterialGrid(");
			assertTrue(
				grid.contains("visibleRows()"),
				"the placeholder pass must cover rows below the last material, and "
					+ "lastVisible() stops at the data"
			);
			int firstSlot = grid.indexOf("ChestGuiStyle.drawSlot(");
			int firstItem = grid.indexOf("graphics.item(");
			assertTrue(
				firstSlot > 0 && firstSlot < firstItem,
				"slot backgrounds are laid down before the items that sit on them"
			);
		}

		@Test
		@DisplayName("The tooltip says stacks, boxes and the conversion rate")
		void tooltipShowsBulk() throws Exception {
			String screen = read(CLAN_SCREEN);
			String tip = body(screen, "private List<Component> clanCellTooltip(");
			assertTrue(tip.contains("gather_percent"), "per-material progress");
			assertTrue(tip.contains("gather_left_bulk"), "what is left, in boxes and stacks");
			assertTrue(tip.contains("box_holds"), "the conversion rate, so the numbers check out");
			assertTrue(
				body(screen, "private static String bulkText(").contains("unit_box"),
				"the breakdown is built from localized units"
			);
			assertFalse(
				screen.contains("private static String formatBoxes("),
				"the single decimal figure it replaced was not actionable"
			);
		}
	}

	@Nested
	@DisplayName("5. Only the gather's creator strikes materials off it")
	class HostExclusion {
		@Test
		@DisplayName("Excluded materials count toward neither need nor delivered")
		void progressIgnoresExcluded() {
			ClanSession s = new ClanSession();
			s.materials.put("minecraft:glass", mat(100, 40));
			ClanSession.ClanMaterial stone = mat(40_000, 0);
			stone.excluded = true;
			s.materials.put("minecraft:stone", stone);

			assertEquals(100, s.totalNeed(), "a struck-off material is not still wanted");
			assertEquals(40, s.totalDelivered());
			assertEquals(0, s.remaining("minecraft:stone"));
			assertEquals(60, s.remaining("minecraft:glass"));
			assertTrue(s.isExcluded("minecraft:stone"));
			assertFalse(s.isExcluded("minecraft:glass"));
			assertFalse(s.isExcluded("minecraft:dirt"), "an unknown item is not excluded");
		}

		@Test
		@DisplayName("Excluding everything reads as finished, not as stuck at 99%")
		void excludingAllCompletes() {
			ClanSession s = new ClanSession();
			ClanSession.ClanMaterial only = mat(40_000, 0);
			only.excluded = true;
			s.materials.put("minecraft:stone", only);
			assertEquals(0, s.totalNeed());
			assertEquals(0, s.totalDelivered());
		}

		@Test
		@DisplayName("Delivered history survives being struck off")
		void historyKept() {
			ClanSession s = new ClanSession();
			ClanSession.ClanMaterial m = mat(1000, 640);
			m.excluded = true;
			s.materials.put("minecraft:stone", m);
			ClanSession.ClanMaterial back = s.material("minecraft:stone");
			assertEquals(640, back.delivered, "the stone really was delivered; do not rewrite it");
			back.excluded = false;
			assertEquals(1000, s.totalNeed(), "un-excluding restores it whole");
		}

		@Test
		@DisplayName("Both hubs route a host-checked exclude and refuse claims on it")
		void hubEnforcesHostOnly() throws Exception {
			String hub = read(HUB);
			assertTrue(hub.contains("def _exclude("), "handler missing");
			assertTrue(hub.contains("action == \"exclude\""), "route missing");
			String ex = pyBody(hub, "def _exclude(");
			assertTrue(
				ex.contains("self._host_session("),
				"exclusion is an authority claim and must use the shared host check"
			);
			assertTrue(ex.contains("_clear_claim(mat)"), "excluding releases the claim on it");
			assertTrue(
				pyBody(hub, "def _claim(").contains("m.get(\"excluded\")"),
				"claiming a struck-off material has to be refused"
			);

			String php = read(HUB_PHP);
			assertTrue(php.contains("$action === 'exclude'"), "the PHP mirror needs it too");
			assertTrue(
				php.contains("require_verified_host($sess, 'only the gather host can exclude items')"),
				"same authority check on the PHP side"
			);
			assertTrue(php.contains("|release_claims|exclude)"), "PHP route not extended");
		}

		@Test
		@DisplayName("Right-click is host-only, and excluded cells go black")
		void screenGatesTheGesture() throws Exception {
			String screen = read(CLAN_SCREEN);
			String click = body(screen, "private boolean hostExcludeClick(");
			assertTrue(click.contains("event.button() != 1"), "right-click is the gesture");
			assertTrue(
				click.contains("ClanSessionManager.isHost(this.minecraft)"),
				"a member must not be offered a control the hub will refuse"
			);
			assertTrue(click.contains("excludeAsync("), "the click has to reach the hub");
			assertTrue(
				screen.contains("ChestGuiStyle.STOCK_EXCLUDED"),
				"excluded materials are blacked out, which is what was asked for"
			);
			assertTrue(
				body(screen, "private int clanBand(").contains("s.isExcluded(itemId)"),
				"struck-off materials sink to the end of the grid"
			);
			assertTrue(
				body(read(MANAGER), "public static void excludeAsync(")
					.contains("if (!isHost(mc))"),
				"the client refuses early rather than showing the host's 403"
			);
			assertTrue(
				body(read(MANAGER), "public static int clanNeed(").contains("m.excluded"),
				"an excluded material must drop out of the local gather queue"
			);
		}
	}

	@Nested
	@DisplayName("Every new string exists in both languages")
	class Strings {
		@Test
		@DisplayName("Russian and English both carry the new keys")
		void bothLanguages() throws Exception {
			String ru = read(RU);
			String en = read(EN);
			for (String key : new String[]{
				"screen.chestmemory.clan.mat_excluded",
				"screen.chestmemory.clan.mat_excluded_hint",
				"screen.chestmemory.clan.mat_excluded_host_hint",
				"screen.chestmemory.clan.mat_exclude_hint",
				"screen.chestmemory.tooltip.gather_percent",
				"screen.chestmemory.tooltip.gather_left_bulk",
				"screen.chestmemory.tooltip.box_holds",
				"screen.chestmemory.tooltip.unit_box",
				"screen.chestmemory.tooltip.unit_stack",
				"screen.chestmemory.tooltip.unit_item",
				"message.chestmemory.clan_excluded",
				"message.chestmemory.clan_unexcluded",
				"message.chestmemory.clan_exclude_host_only",
				"message.chestmemory.clan_claim_excluded",
			}) {
				assertTrue(ru.contains('"' + key + '"'), "ru_ru is missing " + key);
				assertTrue(en.contains('"' + key + '"'), "en_us is missing " + key);
			}
		}

		@Test
		@DisplayName("The unit strings stay short enough to sit in a tooltip line")
		void unitsAreShort() throws Exception {
			String ru = read(RU);
			for (String key : new String[]{
				"screen.chestmemory.tooltip.unit_box",
				"screen.chestmemory.tooltip.unit_stack",
				"screen.chestmemory.tooltip.unit_item",
			}) {
				int at = ru.indexOf('"' + key + '"');
				String value = ru.substring(ru.indexOf(':', at) + 1, ru.indexOf('\n', at));
				// Three of these are joined with " + " on one line; a wordy unit wraps the
				// tooltip and the breakdown stops being readable at a glance.
				assertTrue(
					value.trim().length() <= 12,
					key + " is too long for a joined breakdown: " + value.trim()
				);
			}
		}
	}
}
