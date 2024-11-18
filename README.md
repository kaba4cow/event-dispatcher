# Event Dispatcher Library

**EventDispatcher** is a Java library for managing and dispatching events in both synchronous and asynchronous modes. It allows you to easily register event handlers and dispatch events with customizable priority levels.

## Features

 - Simple **API** for registering and unregistering event handlers.
 - Annotation-based event handler detection.
 - Supports synchronous and asynchronous event dispatching.
 - Supports waiter event dispatching and polling.
 - Priority queue for managing asynchronous events.
 - Thread-safe operations with a built-in thread pool.
 
## Usage

### 1. Define Events

Events are plain Java objects. For example:

```java
public class Event {
    private final String message;

    public Event(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
```

### 2. Create Event Handlers

Event handler methods must be annotated with `@EventHandler` and take a single parameter corresponding to the event type.

```java
public class EventHandler {
    @EventHandler
    public void onEvent(Event event) {
        System.out.println("Received event: " + event.getMessage());
    }
}
```

### 3. Dispatch Events

Create an instance of `EventDispatcher` and register your event handlers.

```java
EventDispatcher dispatcher = new EventDispatcher();
dispatcher.register(new EventHandler());
dispatcher.dispatchNow(new Event("Hello, World!"));
dispatcher.dispatchAsync(new Event("Async Event"), EventPriority.HIGH);
dispatcher.shutdown();
```

### 4. Use Waiters

Waiters allow you to dispatch events with a time-to-live (TTL) and retrieve them later within their lifespan.

```java
EventDispatcher dispatcher = new EventDispatcher();
dispatcher.dispatchWaiter(new Event("Waiting Event"), L5, TimeUnit.SECONDS);
Optional<Event> event = dispatcher.pollWaiter(Event.class);
event.ifPresent(e -> System.out.println("Polled event: " + e.getMessage()));
```

## Key Classes

`EventDispatcher` - the core class responsible for managing event handlers and dispatching events.

`@EventHandler` - annotation to mark methods as event handlers.

`EventPriority` - an enumeration defining priority levels for asynchronous events:
 - `VERY_LOW`
 - `LOW`
 - `MEDIUM`
 - `HIGH`
 - `VERY_HIGH`

`EventDispatcherException` - custom runtime exception for handling errors during event registration or dispatching.

## Threading Model

Asynchronous events are processed in a separate thread pool managed by the `EventDispatcher`. Events are queued in a priority queue, ensuring higher-priority events are processed first.

## Error Handling

If an exception occurs during event dispatching, it will be wrapped in an `EventDispatcherException`. The stack trace is printed for debugging purposes.