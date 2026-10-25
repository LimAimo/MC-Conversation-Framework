package net.mccf.mod.client.chat;

import java.util.HashMap;
import java.util.Map;

/** 只管主线程结果的排序与会话隔离，不发起翻译或操作 HUD；展示由 ClientOnlyChatTranslator 回调执行。 */
public final class OrderedClientResults {
	public record Ticket(long session, long sequence) {}
	private final Map<Long, Runnable> completed = new HashMap<>();
	private long session;
	private long issued;
	private long next;

	public Ticket reserve() {
		return new Ticket(session, issued++);
	}

	public void complete(Ticket ticket, Runnable delivery) {
		if (ticket.session() != session || ticket.sequence() < next) return;
		completed.put(ticket.sequence(), delivery);
		Runnable ready;
		while ((ready = completed.remove(next)) != null) {
			next++;
			ready.run();
		}
	}

	public void reset() {
		session++;
		issued = 0;
		next = 0;
		completed.clear();
	}
}
