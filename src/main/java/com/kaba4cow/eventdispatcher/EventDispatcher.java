package com.kaba4cow.eventdispatcher;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * The {@code EventDispatcher} class is a central hub for managing events and their corresponding handlers. It supports both
 * synchronous and asynchronous event dispatching with priority-based handling.
 * <p>
 * Event handlers must be annotated with {@link EventHandler}, and they must have a single parameter corresponding to the event
 * type.
 * <p>
 * The dispatcher uses a thread pool to process asynchronous events and ensures thread-safe operation using concurrent data
 * structures.
 */
public class EventDispatcher {

	private static final EventDispatcherThreadFactory threadFactory = new EventDispatcherThreadFactory();

	private final Map<Class<?>, List<EventHandlerEntry>> handlers;
	private final PriorityBlockingQueue<AsyncEvent> asyncs;
	private final Map<Class<?>, PriorityBlockingQueue<WaiterEvent>> waiters;
	private final ExecutorService executor;

	/**
	 * Creates a new {@code EventDispatcher} with an internal thread pool for handling asynchronous events.
	 */
	public EventDispatcher() {
		this.handlers = new ConcurrentHashMap<>();
		this.asyncs = new PriorityBlockingQueue<>();
		this.waiters = new ConcurrentHashMap<>();
		this.executor = Executors.newCachedThreadPool(threadFactory);
		this.executor.submit(() -> {
			while (!Thread.currentThread().isInterrupted())
				try {
					dispatchNow(asyncs.take().event);
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
				} catch (Exception exception) {
					exception.printStackTrace();
				}
		});
	}

	/**
	 * Registers an event handler object, which contains methods annotated with {@link EventHandler}.
	 *
	 * @param eventHandler the object containing event handler methods
	 * 
	 * @throws NullPointerException     if {@code eventHandler} is null
	 * @throws IllegalArgumentException if an event handler method has an incorrect signature
	 * @throws EventDispatcherException if registration fails
	 */
	public void register(Object eventHandler) {
		try {
			Objects.requireNonNull(eventHandler);
			List<EventHandlerEntry> entries = new ArrayList<>();
			for (Method method : eventHandler.getClass().getMethods())
				if (method.isAnnotationPresent(EventHandler.class))
					if (method.getParameterCount() != 1)
						throw new IllegalArgumentException(
								String.format("Event handler method %s in class %s must have only one argument",
										method.getName(), eventHandler.getClass().getName()));
					else
						entries.add(new EventHandlerEntry(eventHandler, method));
			if (entries.isEmpty())
				throw new IllegalStateException(
						String.format("No event handler methods found in class %s", eventHandler.getClass().getName()));
			else
				for (EventHandlerEntry entry : entries)
					handlers.computeIfAbsent(entry.eventType, key -> new ArrayList<>()).add(entry);
		} catch (Exception exception) {
			throw new EventDispatcherException(
					String.format("Could not register event handler of class %s", eventHandler.getClass().getName()),
					exception);
		}
	}

	/**
	 * Unregisters an event handler object, removing all associated event handler methods from the dispatcher.
	 *
	 * @param eventHandler the object to unregister
	 */
	public void unregister(Object eventHandler) {
		if (Objects.nonNull(eventHandler))
			handlers.values().forEach(list -> list.removeIf(entry -> entry.instance.equals(eventHandler)));
	}

	/**
	 * Immediately dispatches an event to all registered handlers for its type.
	 *
	 * @param event the event to dispatch
	 * 
	 * @throws NullPointerException     if {@code event} is null
	 * @throws IllegalStateException    if no handlers are found for the event type
	 * @throws EventDispatcherException if dispatch fails
	 */
	public void dispatchNow(Object event) {
		try {
			Objects.requireNonNull(event);
			List<EventHandlerEntry> entries = handlers.get(event.getClass());
			if (Objects.isNull(entries) || entries.isEmpty())
				throw new IllegalStateException(
						String.format("No event handler found for event of class %s", event.getClass().getName()));
			for (EventHandlerEntry entry : entries) {
				try {
					entry.handlerMethod.invoke(entry.instance, event);
				} catch (Exception exception) {
					throw new RuntimeException(
							String.format("Could not invoke event handler method of class %s for event of class %s",
									entry.instance.getClass().getName(), event.getClass().getName()),
							exception);
				}
			}
		} catch (Exception exception) {
			throw new EventDispatcherException(
					String.format("Could not dispatch event of class %s", event.getClass().getName()), exception);
		}
	}

	/**
	 * Dispatches an event asynchronously, adding it to a priority queue.
	 *
	 * @param event    the event to dispatch
	 * @param priority the priority level of the event
	 * 
	 * @throws NullPointerException     if {@code event} or {@code priority} is null
	 * @throws IllegalStateException    if the dispatcher is shut down or no handlers exist
	 * @throws EventDispatcherException if dispatch fails
	 */
	public void dispatchAsync(Object event, EventPriority priority) {
		try {
			Objects.requireNonNull(event);
			Objects.requireNonNull(priority);
			if (executor.isShutdown())
				throw new IllegalStateException("Event dispatcher is shut down");
			List<EventHandlerEntry> entries = handlers.get(event.getClass());
			if (Objects.isNull(entries) || entries.isEmpty())
				throw new IllegalStateException(
						String.format("No event handler found for event of class %s", event.getClass().getName()));
			asyncs.add(new AsyncEvent(event, priority));
		} catch (Exception exception) {
			throw new EventDispatcherException(
					String.format("Could not dispatch async event of class %s", event.getClass().getName()), exception);
		}
	}

	/**
	 * Dispatches an event asynchronously with a default priority of {@code MEDIUM}.
	 *
	 * @param event the event to dispatch
	 * 
	 * @throws NullPointerException     if {@code event} is null
	 * @throws IllegalStateException    if the dispatcher is shut down or no handlers exist
	 * @throws EventDispatcherException if dispatch fails
	 */
	public void dispatchAsync(Object event) {
		dispatchAsync(event, EventPriority.MEDIUM);
	}

	/**
	 * Dispatches an event to the waiter queue with a specified time-to-live (TTL). The event will remain in the queue until it
	 * is either polled or its TTL expires.
	 *
	 * @param event    the event to dispatch; must not be {@code null}
	 * @param ttl      the time-to-live duration for the event; must be greater than 1
	 * @param timeUnit the {@link TimeUnit} of the TTL; must not be {@code null}
	 * 
	 * @throws NullPointerException     if the event or {@code timeUnit} is {@code null}
	 * @throws IllegalArgumentException if the TTL is less than 1
	 */
	public void dispatchWaiter(Object event, long ttl, TimeUnit timeUnit) {
		Objects.requireNonNull(event);
		if (ttl < 1L)
			throw new IllegalArgumentException("Waiter lifespan must be greater than 1");
		long timestamp = updateWaiters();
		waiters.computeIfAbsent(event.getClass(), k -> new PriorityBlockingQueue<>())
				.add(new WaiterEvent(event, timestamp, timeUnit.toMillis(ttl)));
	}

	/**
	 * Polls and retrieves the next event of the specified type from the waiter queue. If the event's lifespan has expired, it
	 * is removed from the queue, and an empty {@link Optional} is returned.
	 *
	 * @param <T>  the type of the event
	 * @param type the class of the event to poll; must not be {@code null}
	 * 
	 * @return an {@link Optional} containing the event if present and not expired; otherwise, an empty {@link Optional}
	 * 
	 * @throws NullPointerException if the type is {@code null}
	 */
	@SuppressWarnings("unchecked")
	public <T> Optional<T> poll(Class<T> type) {
		Objects.requireNonNull(type);
		updateWaiters();
		PriorityBlockingQueue<WaiterEvent> events = waiters.get(type);
		if (events != null) {
			WaiterEvent event = events.poll();
			if (event != null)
				return Optional.of((T) event.event);
		}
		return Optional.empty();
	}

	private long updateWaiters() {
		long now = System.currentTimeMillis();
		for (PriorityBlockingQueue<WaiterEvent> events : waiters.values())
			events.removeIf(event -> now >= event.timestamp + event.lifetime);
		return now;
	}

	/**
	 * Shuts down the {@code EventDispatcher}, interrupting any ongoing asynchronous event processing.
	 */
	public void shutdown() {
		executor.shutdownNow();
	}

	private static class EventHandlerEntry {

		private final Object instance;
		private final Method handlerMethod;
		private final Class<?> eventType;

		private EventHandlerEntry(Object instance, Method method) {
			this.instance = instance;
			this.handlerMethod = method;
			this.eventType = method.getParameterTypes()[0];
		}

	}

	private static class EventDispatcherThreadFactory implements ThreadFactory {

		private int counter;

		private EventDispatcherThreadFactory() {
			this.counter = 0;
		}

		@Override
		public Thread newThread(Runnable runnable) {
			return new Thread(runnable, String.format("EventDispatcherThread-%s", counter++));
		}

	}

	private static class WaiterEvent implements Comparable<WaiterEvent> {

		private final Object event;
		private final long timestamp;
		private final long lifetime;

		public WaiterEvent(Object event, long timestamp, long lifetime) {
			this.event = event;
			this.timestamp = timestamp;
			this.lifetime = lifetime;
		}

		@Override
		public int compareTo(WaiterEvent other) {
			return Long.compare(this.timestamp, other.timestamp);
		}

	}

	private static class AsyncEvent implements Comparable<AsyncEvent> {

		private final Object event;
		private final EventPriority priority;

		private AsyncEvent(Object event, EventPriority priority) {
			this.event = event;
			this.priority = priority;
		}

		@Override
		public int compareTo(AsyncEvent other) {
			return other.priority.ordinal() - this.priority.ordinal();
		}

	}

	public static class EventDispatcherException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		public EventDispatcherException(String message, Throwable cause) {
			super(message, cause);
		}

	}

}
