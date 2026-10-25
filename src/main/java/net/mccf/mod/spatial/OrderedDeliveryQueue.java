package net.mccf.mod.spatial;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 这个类只管每个收件人的异步结果按入队顺序交付；不发网络包，也不检查玩家会话，
 * 这些由调用方的 consumer 完成。翻译请求仍可并行，避免逐条发请求增加总延迟。
 */
final class OrderedDeliveryQueue<K> {

	private final Map<K, CompletableFuture<Void>> tails = new HashMap<>();

	<T> CompletableFuture<Void> enqueue(K key, CompletableFuture<T> result,
			Consumer<T> deliver, Executor executor) {
		CompletableFuture<Void> previous;
		CompletableFuture<Void> tail = new CompletableFuture<>();
		synchronized (this) {
			previous = tails.getOrDefault(key, CompletableFuture.completedFuture(null));
			tails.put(key, tail);
		}
		// 失败只影响当前项；先恢复前一项的异常状态，否则一条失败会堵住之后的聊天。
		previous.handle((ignored, error) -> null)
				.thenCompose(ignored -> result)
				.thenAcceptAsync(deliver, executor)
				.whenComplete((ignored, error) -> {
					if (error == null) tail.complete(null);
					else tail.completeExceptionally(error);
					synchronized (this) { tails.remove(key, tail); }
				});
		return tail;
	}

	synchronized void remove(K key) { tails.remove(key); }
	synchronized void clear() { tails.clear(); }
}
