package net.mccf.mod.network;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ConversationRosterPayloadTest {

	@Test
	void normal129PlayerGatheringDoesNotThrow() {
		List<UUID> ids = IntStream.range(0, 129).mapToObj(index -> UUID.randomUUID()).toList();
		List<String> names = IntStream.range(0, 129).mapToObj(index -> "player" + index).toList();
		assertDoesNotThrow(() -> new ConversationRosterPayload(UUID.randomUUID(), ids, names));
	}

	@Test
	void oversizedOrMisalignedListsRemainRejected() {
		List<UUID> ids = IntStream.range(0, ConversationRosterPayload.MAX_PARTICIPANTS + 1)
				.mapToObj(index -> UUID.randomUUID()).toList();
		List<String> names = ids.stream().map(id -> "player").toList();
		assertThrows(IllegalArgumentException.class,
				() -> new ConversationRosterPayload(UUID.randomUUID(), ids, names));
		assertThrows(IllegalArgumentException.class,
				() -> new ConversationRosterPayload(UUID.randomUUID(), List.of(UUID.randomUUID()), List.of()));
	}

	@Test
	void callerCannotMutateAnAlreadyQueuedRoster() {
		List<UUID> ids = new ArrayList<>(List.of(UUID.randomUUID()));
		List<String> names = new ArrayList<>(List.of("alice"));
		ConversationRosterPayload payload = new ConversationRosterPayload(UUID.randomUUID(), ids, names);
		ids.clear();
		names.clear();
		assertEquals(1, payload.participantIds().size());
		assertEquals(List.of("alice"), payload.participantNames());
	}
}
