package net.mccf.mod.client.chat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderedClientResultsTest {
	@Test
	void outOfOrderResponsesAreDisplayedInMessageReceiveOrder() {
		var queue = new OrderedClientResults();
		var received = new ArrayList<String>();
		var first = queue.reserve();
		var second = queue.reserve();
		queue.complete(second, () -> received.add("second"));
		assertEquals(List.of(), received);
		queue.complete(first, () -> received.add("first"));
		assertEquals(List.of("first", "second"), received);
	}

	@Test
	void failedRequestCanAdvanceTheQueueWithoutAddingAChatLine() {
		var queue = new OrderedClientResults();
		var received = new ArrayList<String>();
		var failed = queue.reserve();
		var next = queue.reserve();
		queue.complete(next, () -> received.add("next"));
		queue.complete(failed, () -> {});
		assertEquals(List.of("next"), received);
	}

	@Test
	void disconnectDropsBothBufferedAndStillPendingResponsesFromPreviousServer() {
		var queue = new OrderedClientResults();
		var received = new ArrayList<String>();
		var pendingOnA = queue.reserve();
		var bufferedOnA = queue.reserve();
		queue.complete(bufferedOnA, () -> received.add("A buffered"));
		queue.reset();
		var onB = queue.reserve();
		queue.complete(pendingOnA, () -> received.add("A late"));
		queue.complete(onB, () -> received.add("B"));
		assertEquals(List.of("B"), received);
	}
}
