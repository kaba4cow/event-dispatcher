package com.kaba4cow.eventdispatcher;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadFactory;

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
	private final PriorityBlockingQueue<AsyncEvent> queue;
	private final ExecutorService executor;

	/**
	 * Creates a new {@code EventDispatcher} with an internal thread pool for handling asynchronous events.
	 */
	public EventDispatcher() {
		this.handlers = new ConcurrentHashMap<>();
		this.queue = new PriorityBlockingQueue<>();
		this.executor = Executors.newCachedThreadPool(threadFactory);
		this.executor.submit(() -> {
			while (!Thread.currentThread().isInterrupted())
				try {
					dispatchNow(queue.take().event);
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
			queue.add(new AsyncEvent(event, priority));
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
