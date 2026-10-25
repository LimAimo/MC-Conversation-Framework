package net.mccf.mod.context;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ConversationManagerTest {

	private final UUID a = UUID.randomUUID();
	private final UUID b = UUID.randomUUID();
	private final UUID c = UUID.randomUUID();
	private final UUID d = UUID.randomUUID();
	private final ConversationManager manager = new ConversationManager(() -> 120, () -> 10.0);

	private ConversationManager.Location at(String dimension, double x) {
		return new ConversationManager.Location(dimension, x, 0, 0);
	}

	@Test
	void movingApartSplitsGroupAndRemovesOtherSpeakersContext() {
		Conversation original = manager.recordUtterance(a, Set.of(b), 1).conversation();
		original.recordMessage(a, "a-secret", 1);
		original.recordMessage(b, "b-secret", 2);
		manager.consumeRosterChanges();
		manager.reconcileParticipants(Map.of(a, at("overworld", 0), b, at("overworld", 20)));
		assertNotEquals(manager.getConversationFor(a).getId(), manager.getConversationFor(b).getId());
		assertEquals(List.of("a-secret"), manager.getConversationFor(a).contextFor(a, Set.of(a)));
		assertEquals(List.of("b-secret"), manager.getConversationFor(b).contextFor(b, Set.of(b)));
		assertEquals(2, manager.consumeRosterChanges().size());
	}

	@Test
	void dimensionChangeSplitsEvenAtIdenticalCoordinates() {
		manager.recordUtterance(a, Set.of(b), 1);
		manager.reconcileParticipants(Map.of(a, at("overworld", 0), b, at("nether", 0)));
		assertEquals(Set.of(a), manager.getConversationFor(a).getParticipants());
		assertEquals(Set.of(b), manager.getConversationFor(b).getParticipants());
	}

	@Test
	void mergeOfExistingGroupsUpdatesRosterAndPreservesTimelineAndAudience() {
		Conversation first = manager.recordUtterance(a, Set.of(b), 1).conversation();
		first.recordMessage(a, "first", 1);
		first.recordMessage(b, "third", 3);
		Conversation second = manager.recordUtterance(c, Set.of(d), 2).conversation();
		second.recordMessage(c, "second", 2);
		manager.consumeRosterChanges();
		Conversation merged = manager.recordUtterance(a, Set.of(c), 4).conversation();
		assertEquals(Set.of(a, b, c, d), merged.getParticipants());
		assertEquals(1, manager.consumeRosterChanges().size());
		assertEquals(List.of("first", "second", "third"),
				merged.getRecentMessages().stream().map(Conversation.ContextMessage::text).toList());
		assertEquals(List.of("second"), merged.contextFor(c, Set.of(a, b, c, d)));
		assertTrue(merged.contextFor(a, c, Set.of(a, b, c, d)).isEmpty());
	}

	@Test
	void disconnectedParticipantIsRemovedAndRosterChangesOnlyOnce() {
		Conversation conversation = manager.recordUtterance(a, Set.of(b), 1).conversation();
		conversation.recordMessage(b, "leaving", 2);
		manager.consumeRosterChanges();
		manager.removeParticipant(b);
		assertNull(manager.getConversationFor(b));
		assertTrue(conversation.getRecentMessages().isEmpty());
		assertEquals(List.of(conversation), manager.consumeRosterChanges());
		assertTrue(manager.consumeRosterChanges().isEmpty());
	}

	@Test
	void idleExpirationAndShutdownClearPlayerMappings() {
		Conversation conversation = manager.recordUtterance(a, Set.of(b), 1).conversation();
		conversation.recordMessage(a, "hello", 1);
		manager.expire(2402);
		assertNull(manager.getConversationFor(a));
		assertEquals(0, manager.getActiveConversationCount());
		manager.recordUtterance(c, Set.of(d), 2403);
		manager.clear();
		assertNull(manager.getConversationFor(c));
		assertTrue(manager.consumeRosterChanges().isEmpty());
	}

	@Test
	void sameConnectedGroupDoesNotGenerateRepeatedRosterChanges() {
		manager.recordUtterance(a, Set.of(b, c), 1);
		manager.consumeRosterChanges();
		manager.reconcileParticipants(Map.of(a, at("overworld", 0), b, at("overworld", 9), c, at("overworld", 18)));
		manager.recordUtterance(a, Set.of(b), 2);
		assertEquals(1, manager.getActiveConversationCount());
		assertTrue(manager.consumeRosterChanges().isEmpty());
	}

	@Test
	void listenerCannotUseUnheardMessagesOrAbsentSpeakers() {
		Conversation conversation = manager.recordUtterance(a, Set.of(b), 1).conversation();
		conversation.recordMessage(a, "private", 1, Set.of(a));
		conversation.recordMessage(a, "heard", 2, Set.of(a, b));
		conversation.recordMessage(c, "far-away", 3, Set.of(b, c));
		assertEquals(List.of("heard"), conversation.contextFor(b, Set.of(a, b)));
	}
}
