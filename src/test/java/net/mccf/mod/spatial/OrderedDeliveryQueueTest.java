package net.mccf.mod.spatial;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class OrderedDeliveryQueueTest {

	@Test
	void fasterSecondTranslationWaitsForFirstButOtherListenersProceed() {
		OrderedDeliveryQueue<String> queue = new OrderedDeliveryQueue<>();
		List<String> received = new ArrayList<>();
		CompletableFuture<String> first = new CompletableFuture<>();
		CompletableFuture<String> second = new CompletableFuture<>();
		queue.enqueue("alice", first, received::add, Runnable::run);
		CompletableFuture<Void> tail = queue.enqueue("alice", second, received::add, Runnable::run);
		second.complete("second");
		assertTrue(received.isEmpty());
		queue.enqueue("bob", CompletableFuture.completedFuture("bob"), received::add, Runnable::run).join();
		assertEquals(List.of("bob"), received);
		first.complete("first");
		tail.join();
		assertEquals(List.of("bob", "first", "second"), received);
	}

	@Test
	void failedDeliveryDoesNotBlockFollowingMessage() {
		OrderedDeliveryQueue<String> queue = new OrderedDeliveryQueue<>();
		List<String> received = new ArrayList<>();
		CompletableFuture<String> first = new CompletableFuture<>();
		queue.enqueue("alice", first, received::add, Runnable::run);
		CompletableFuture<Void> tail = queue.enqueue("alice", CompletableFuture.completedFuture("second"),
				received::add, Runnable::run);
		first.completeExceptionally(new IllegalStateException("upstream unavailable"));
		tail.join();
		assertEquals(List.of("second"), received);
	}

	@Test
	void disconnectedListenerDoesNotDelayNewSession() {
		OrderedDeliveryQueue<String> queue = new OrderedDeliveryQueue<>();
		List<String> received = new ArrayList<>();
		queue.enqueue("alice", new CompletableFuture<String>(), received::add, Runnable::run);
		queue.remove("alice");
		queue.enqueue("alice", CompletableFuture.completedFuture("new-session"), received::add, Runnable::run).join();
		assertEquals(List.of("new-session"), received);
	}
}
