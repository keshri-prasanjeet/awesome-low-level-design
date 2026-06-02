# Logging Framework Learning Guide

This guide is a structured course for understanding the logging framework
problem and the Java solution in this folder. The goal is not just to memorize
classes, but to learn how a programmer turns a messy real-world need into a
small set of clear responsibilities.

Use this with:

- `problems/logging-framework.md`
- `class-diagrams/loggingframework-class-diagram.png`
- `solutions/java/src/loggingframework/`

Important note: the problem statement mentions a few conceptual pieces such as
`LoggerConfig` and `DatabaseAppender`. This Java solution models configuration
directly on `Logger` by calling `setLevel`, `addAppender`, and `setAdditivity`.
It includes `ConsoleAppender` and `FileAppender`, but not a database appender.

---

## Course Map

By the end, you should be able to explain and rebuild the framework from
scratch:

1. Why `System.out.println(...)` is not a logging framework.
2. How log levels act like a severity filter.
3. Why a `LogMessage` object exists instead of passing raw strings everywhere.
4. How a `Logger` behaves like a named entry point into the logging system.
5. Why logger hierarchy matters.
6. How appenders separate "where logs go" from the rest of the code.
7. How formatters separate "how logs look" from the rest of the code.
8. Why asynchronous processing helps when output is slow.
9. Which design patterns are actually being used.
10. How to extend the framework without changing the core logic.

Suggested pace:

- Day 1: Modules 1 to 3.
- Day 2: Modules 4 to 5.
- Day 3: Modules 6 to 7.
- Day 4: Modules 8 to 9.
- Day 5: Module 10 and the exercises.
- Day 6: Rebuild the design from memory.
- Day 7: Add one extension, such as JSON formatting or a memory appender.

---

## Module 1: The Real Problem

A beginner version of logging is:

```java
System.out.println("User logged in");
```

That works until the application grows. Then questions appear:

- Should this message appear in production?
- Should warnings go to a file?
- Should fatal errors go to a pager or database?
- How do we include timestamp, thread, level, and component name?
- What happens when 50 threads log at the same time?
- Can one team change formatting without touching the code that logs messages?

A logging framework is not just "printing." It is a small routing system for
events.

Think of it like a restaurant kitchen:

- A waiter writes an order ticket.
- The ticket has data: table number, item, timestamp, priority.
- A dispatcher decides where it should go: grill, bar, dessert station.
- Each station formats the information in the way it needs.
- The waiter should not walk to every station and explain the order manually.

In this solution:

- The waiter is client code calling `logger.info(...)`.
- The order ticket is `LogMessage`.
- The dispatcher is `Logger` plus `LogManager`.
- The kitchen stations are appenders such as `ConsoleAppender` and
  `FileAppender`.
- The printed ticket style is the formatter, such as `SimpleTextFormatter`.
- The background conveyor belt is `AsyncLogProcessor`.

The strongest mental model:

```text
client code -> Logger -> LogMessage -> AsyncLogProcessor -> LogAppender -> LogFormatter -> output
```

Do not read the code as isolated classes. Read it as a pipeline.

---

## Module 2: Requirements Converted Into Classes

The problem requirements map naturally to code responsibilities:

| Requirement | Class or interface | Why it belongs there |
| --- | --- | --- |
| Support log levels | `LogLevel` | Severity is a fixed vocabulary. |
| Store timestamp, level, text | `LogMessage` | A log event needs one data object. |
| Multiple output destinations | `LogAppender` | Destination is a changeable strategy. |
| Console output | `ConsoleAppender` | One concrete output strategy. |
| File output | `FileAppender` | Another concrete output strategy. |
| Custom formatting | `LogFormatter` | Formatting is a changeable strategy. |
| Default text formatting | `SimpleTextFormatter` | One concrete format strategy. |
| Logger configuration | `Logger` | Each logger stores level, appenders, and additivity. |
| Logger lookup and hierarchy | `LogManager` | One registry manages all named loggers. |
| Thread safety and async output | `AsyncLogProcessor` | Output is processed away from caller threads. |

This is a useful LLD habit: do not begin with classes. Begin with things that
change.

Things that change in this problem:

- The minimum severity we want to see.
- The destination of log messages.
- The visual format of log messages.
- The logger name used by a module.
- Whether child loggers should also send logs to parent appenders.
- Whether output should be synchronous or asynchronous.

Good design often means finding these moving parts and putting each behind a
small boundary.

---

## Module 3: Log Levels Are A Severity Ladder

Open:

- `enums/LogLevel.java`

The enum is:

```java
public enum LogLevel {
    DEBUG(1), INFO(2), WARN(3), ERROR(4), FATAL(5);

    private final int level;

    public boolean isGreaterOrEqual(LogLevel other) {
        return this.level >= other.level;
    }
}
```

This means a logger configured at `INFO` should allow:

- `INFO`
- `WARN`
- `ERROR`
- `FATAL`

It should block:

- `DEBUG`

Think of log levels like a security checkpoint:

- `DEBUG` is low urgency.
- `INFO` is normal operational detail.
- `WARN` means something suspicious happened but the system may continue.
- `ERROR` means something failed.
- `FATAL` means the program or subsystem may not recover.

If the checkpoint is set to `WARN`, only `WARN`, `ERROR`, and `FATAL` pass.

| Logger minimum level | DEBUG message | INFO message | WARN message | ERROR message | FATAL message |
| --- | --- | --- | --- | --- | --- |
| DEBUG | pass | pass | pass | pass | pass |
| INFO | block | pass | pass | pass | pass |
| WARN | block | block | pass | pass | pass |
| ERROR | block | block | block | pass | pass |
| FATAL | block | block | block | block | pass |

The important programming idea is that `LogLevel` owns the comparison logic.
The logger asks:

```java
messageLevel.isGreaterOrEqual(getEffectiveLevel())
```

That is better than scattering numeric comparisons across the system.

Holistic lesson:

When a concept has rules, put the rules near the concept. `LogLevel` is not
just a label. It knows how levels compare.

Practice question:

If you add a new level named `TRACE` below `DEBUG`, what numeric value should it
have? What should happen to a logger configured at `DEBUG`?

Answer:

`TRACE` should be lower than `DEBUG`, for example `TRACE(0)`. A logger at
`DEBUG` should block `TRACE`.

---

## Module 4: LogMessage Is The Event Object

Open:

- `entities/LogMessage.java`

`LogMessage` stores:

- `timestamp`
- `level`
- `loggerName`
- `threadName`
- `message`

This is an immutable event object. Its fields are `final`, and it has getters.

Why not just pass a string?

Because the output destination may need more than the text.

For example:

- Console may print: `2026-06-02 [main] INFO - App started`
- File may write the same text.
- Database may store each field in a separate column.
- JSON formatter may output structured data.
- Monitoring system may count all `ERROR` messages by logger name.

If the whole framework passed only this:

```java
"App started"
```

then every output destination would have to rediscover or ignore important
context.

Think of `LogMessage` as a shipping label:

```text
Package:
  contents: "Payment failed"
  priority: ERROR
  sender: com.example.PaymentService
  createdAt: 2026-06-02 10:15:22
  createdByThread: checkout-worker-4
```

The shipping company does not pass around only "Payment failed." It passes the
whole label.

Subtle point:

`threadName` is captured in the constructor:

```java
this.threadName = Thread.currentThread().getName();
```

That matters because the framework later writes logs asynchronously. If the
formatter asked for `Thread.currentThread().getName()` during output, it would
probably see `AsyncLogProcessor`, not the original caller thread. Capturing the
thread name early preserves the true source.

Holistic lesson:

When later processing may happen in another place or another thread, capture
the facts at the moment the event occurs.

---

## Module 5: Logger Is The Friendly API And The Gatekeeper

Open:

- `Logger.java`

Client code wants a simple API:

```java
logger.info("Application starting up.");
logger.debug("Cache hit for user 42.");
logger.error("Payment failed.");
```

The user of the logger should not care about:

- Which appenders exist.
- Which formatter is active.
- Whether output is async.
- Whether a parent logger also receives the message.

That is why `Logger` is a facade. It gives a small surface to client code while
hiding the internal pipeline.

The central method is:

```java
public void log(LogLevel messageLevel, String message) {
    if (messageLevel.isGreaterOrEqual(getEffectiveLevel())) {
        LogMessage logMessage = new LogMessage(messageLevel, this.name, message);
        callAppenders(logMessage);
    }
}
```

Read it as a four-step story:

1. Receive the level and message.
2. Check whether this level is allowed.
3. Wrap raw input into a `LogMessage`.
4. Send the event to appenders.

The convenience methods are just nicer names:

```java
public void info(String message) {
    log(LogLevel.INFO, message);
}
```

This is the same pattern as:

```java
httpClient.get("/users");
```

being a convenience over:

```java
httpClient.request("GET", "/users");
```

The API gives common operations names that match human intent.

Holistic lesson:

Good API design lets the caller express intent, not mechanics. `logger.info`
says what happened. It does not make the caller assemble the pipeline.

---

## Module 6: Effective Level And Inheritance

`Logger` has:

```java
private LogLevel level;
private final Logger parent;
```

The level can be `null`. That does not mean "no logging." It means "inherit
from my parent."

The method:

```java
public LogLevel getEffectiveLevel() {
    for (Logger logger = this; logger != null; logger = logger.parent) {
        LogLevel currentLevel = logger.level;
        if (currentLevel != null) {
            return currentLevel;
        }
    }
    return LogLevel.DEBUG;
}
```

This walks upward until it finds a configured level.

Example:

```text
root                           level = INFO
root -> com                    level = null
root -> com -> example         level = null
root -> com -> example -> db   level = DEBUG
```

What is the effective level?

| Logger | Configured level | Effective level |
| --- | --- | --- |
| `root` | `INFO` | `INFO` |
| `com` | `null` | `INFO` |
| `com.example` | `null` | `INFO` |
| `com.example.db` | `DEBUG` | `DEBUG` |

Why is this useful?

Suppose your whole application should log only `INFO` and above, but one
troublesome service needs deeper detail:

```java
Logger root = LogManager.getInstance().getRootLogger();
root.setLevel(LogLevel.INFO);

Logger paymentLogger = LogManager.getInstance()
        .getLogger("com.shop.payment.PaymentService");
paymentLogger.setLevel(LogLevel.DEBUG);
```

Now `PaymentService` can be verbose without making the entire application noisy.

Parallel:

Think of company policy. The company says business casual. Most departments
inherit that. The lab department can override with stricter safety clothing.
You do not rewrite the dress code for every employee. You override only where
needed.

Holistic lesson:

Inheritance is useful when defaults should flow downward and local overrides
should stay local.

---

## Module 7: Logger Names Form A Tree

Open:

- `LogManager.java`

This solution treats dots in logger names as hierarchy separators.

```java
Logger logger = logManager.getLogger("com.example.service.UserService");
```

The parent chain becomes:

```text
root
root -> com
root -> com -> example
root -> com -> example -> service
root -> com -> example -> service -> UserService
```

The implementation:

```java
private Logger createLogger(String name) {
    if (name.equals("root")) {
        return rootLogger;
    }
    int lastDot = name.lastIndexOf('.');
    String parentName = (lastDot == -1) ? "root" : name.substring(0, lastDot);
    Logger parent = getLogger(parentName);
    return new Logger(name, parent);
}
```

This is clever because asking for one specific logger automatically creates its
ancestors.

Example:

```java
Logger logger = logManager.getLogger("com.example.db.ConnectionPool");
```

If `com.example.db` does not exist yet, `LogManager` recursively creates:

1. `com`
2. `com.example`
3. `com.example.db`
4. `com.example.db.ConnectionPool`

Parallel:

It is like creating a folder path:

```text
/root/com/example/db/ConnectionPool
```

If the middle folders do not exist, the manager creates them.

Holistic lesson:

Names are not always just strings. Sometimes a name contains structure. Good
LLD notices and uses that structure.

---

## Module 8: Additivity Means "Also Send To Parent"

`Logger` has:

```java
private boolean additivity = true;
```

and:

```java
private void callAppenders(LogMessage logMessage) {
    if (!appenders.isEmpty()) {
        LogManager.getInstance().getProcessor().process(logMessage, this.appenders);
    }
    if (additivity && parent != null) {
        parent.callAppenders(logMessage);
    }
}
```

Additivity means:

1. Send this log to my appenders.
2. If enabled, also ask my parent to handle the same log.
3. Keep walking upward.

Example:

```text
root has ConsoleAppender
com.example.service has FileAppender
com.example.service.UserService logs INFO
```

With additivity `true`, the message goes to:

- `FileAppender` on `com.example.service`
- `ConsoleAppender` on `root`

With additivity `false` on `com.example.service`, the message goes only to:

- `FileAppender` on `com.example.service`

Parallel:

Imagine a school incident report:

- The classroom teacher records it.
- By default, the department head also receives it.
- The principal may also receive it.
- For a confidential counseling note, the teacher might disable forwarding.

Potential trap:

If both a child and parent have console appenders, one log call may print twice.
That is not a bug in `ConsoleAppender`. It is a configuration issue caused by
additivity.

Practice:

Predict outputs:

```java
Logger root = manager.getRootLogger();
root.addAppender(new ConsoleAppender());

Logger service = manager.getLogger("com.app.service");
service.addAppender(new ConsoleAppender());

Logger user = manager.getLogger("com.app.service.UserService");
user.info("created user");
```

How many console lines?

Answer:

Two, assuming the level allows the message. One from the service branch and one
from root through additivity.

---

## Module 9: Appender Strategy, Or "Where Should This Go?"

Open:

- `strategies/appender/LogAppender.java`
- `strategies/appender/ConsoleAppender.java`
- `strategies/appender/FileAppender.java`

The interface is:

```java
public interface LogAppender {
    void append(LogMessage logMessage);
    void close();
    LogFormatter getFormatter();
    void setFormatter(LogFormatter formatter);
}
```

This is the Strategy pattern.

The stable behavior:

```text
Logger has a LogMessage and wants it written somewhere.
```

The variable behavior:

```text
Where does the message go?
```

So the variable part becomes an interface.

Concrete strategies:

```text
ConsoleAppender -> writes to System.out
FileAppender    -> writes to a file
```

Why not put this inside `Logger`?

Bad version:

```java
if (destination.equals("console")) {
    System.out.println(...);
} else if (destination.equals("file")) {
    writer.write(...);
} else if (destination.equals("database")) {
    database.insert(...);
}
```

This makes `Logger` grow every time a new destination appears.

Better version:

```java
for (LogAppender appender : appenders) {
    appender.append(logMessage);
}
```

Now `Logger` does not know whether it is writing to console, file, database,
email, Kafka, or a test list.

Parallel:

Think of payment methods:

```text
PaymentProcessor -> PaymentMethod
                 -> CreditCardPayment
                 -> UpiPayment
                 -> WalletPayment
```

The checkout flow should not know every detail of every payment method. It
should depend on a common interface.

Holistic lesson:

When you hear "support multiple X" and "easy to add future X", look for a
strategy interface.

---

## Module 10: Formatter Strategy, Or "How Should This Look?"

Open:

- `strategies/formatter/LogFormatter.java`
- `strategies/formatter/SimpleTextFormatter.java`

The interface is:

```java
public interface LogFormatter {
    String format(LogMessage logMessage);
}
```

`SimpleTextFormatter` turns a structured object into text:

```text
2026-06-02 10:15:22.123 [main] INFO - com.example.Main: Application starting up.
```

The appender owns a formatter:

```java
private LogFormatter formatter;
```

That means two appenders can write the same `LogMessage` differently.

Example:

```text
ConsoleAppender with SimpleTextFormatter:
2026-06-02 10:15:22.123 [main] INFO - com.shop.Payment: paid

FileAppender with JsonFormatter:
{"time":"2026-06-02T10:15:22.123","level":"INFO","logger":"com.shop.Payment","message":"paid"}
```

Why not put formatting inside `LogMessage.toString()`?

Because there is no single correct format.

Parallel:

A person has one identity, but different documents present it differently:

- Passport: name, nationality, date of birth.
- Office badge: name, photo, employee id.
- Conference badge: first name and company.

The person does not become three different people. The presentation changes.

Same here:

- `LogMessage` is the event.
- `LogFormatter` is the presentation.

Practice extension:

```java
public class JsonLogFormatter implements LogFormatter {
    @Override
    public String format(LogMessage logMessage) {
        return String.format(
                "{\"timestamp\":\"%s\",\"thread\":\"%s\",\"level\":\"%s\",\"logger\":\"%s\",\"message\":\"%s\"}",
                logMessage.getTimestamp(),
                logMessage.getThreadName(),
                logMessage.getLevel(),
                logMessage.getLoggerName(),
                logMessage.getMessage().replace("\"", "\\\"")
        );
    }
}
```

This is a teaching example, not production-grade JSON escaping. In production,
use a JSON library.

Holistic lesson:

Separate data from presentation. It makes the same data useful in many places.

Implementation observation:

`SimpleTextFormatter` currently includes a trailing newline in the formatted
string. `ConsoleAppender` also uses `println`, and `FileAppender` also appends
`"\n"`. In a production version, choose one owner for line endings. Usually the
appender owns line boundaries because the formatter should only describe the
message text.

---

## Module 11: AsyncLogProcessor, Or "Do Not Make The Caller Wait"

Open:

- `AsyncLogProcessor.java`

Logging can be slow:

- Console output can block.
- File output can block.
- Network output can be much slower.
- Database inserts can fail or pause.

If every `logger.info(...)` waits for all output destinations, normal business
code becomes tied to logging speed.

This solution uses:

```java
Executors.newSingleThreadExecutor(...)
```

The logger submits work:

```java
executor.submit(() -> {
    for (LogAppender appender : appenders) {
        appender.append(logMessage);
    }
});
```

That creates a producer-consumer shape:

```text
Producer threads:
  application code calls logger.info(...)

Consumer thread:
  AsyncLogProcessor writes the logs
```

Parallel:

Think of an email outbox.

When you click send, the app puts the email in an outbox and lets you continue.
A background worker sends it. You do not freeze the whole app while the mail
server responds.

Thread-safety pieces in this solution:

| Code | Purpose |
| --- | --- |
| `ConcurrentHashMap` in `LogManager` | Multiple threads can ask for loggers safely. |
| `computeIfAbsent` | Creation and lookup happen atomically for a key. |
| `CopyOnWriteArrayList` in `Logger` | Appenders can be iterated while configuration changes. |
| Single-thread executor | Appender calls are serialized through one worker. |
| `synchronized` on `FileAppender.append` | Protects file writes if called concurrently. |

Important nuance:

The single-thread executor means log output order is easier to reason about,
but it may become a bottleneck for very high log volume. A production framework
may use bounded queues, batching, backpressure, or multiple workers.

Another thread-safety nuance:

This solution uses thread-safe collections for logger lookup and appender lists.
If configuration changes are expected while many threads are actively logging,
fields such as `level` and `additivity` could be made `volatile` or wrapped in a
configuration object with clear visibility guarantees.

Shutdown matters:

```java
public void shutdown() {
    processor.stop();
    loggers.values().stream()
            .flatMap(logger -> logger.getAppenders().stream())
            .distinct()
            .forEach(LogAppender::close);
}
```

If you do not shut down, some submitted logs may not flush before the program
exits. This is why `LoggingFrameworkDemo` sleeps briefly and calls shutdown.

Holistic lesson:

Async design separates "accept the work" from "finish the work." That improves
responsiveness, but creates lifecycle responsibilities like shutdown, flushing,
and failure handling.

---

## Module 12: LogManager As Singleton Registry

Open:

- `LogManager.java`

`LogManager` is a singleton:

```java
private static final LogManager INSTANCE = new LogManager();

public static LogManager getInstance() {
    return INSTANCE;
}
```

It owns:

- The root logger.
- A map of named loggers.
- The async processor.

Its job is not to format or write logs. Its job is to coordinate shared objects.

Parallel:

Think of a city transport office:

- It does not drive every bus.
- It knows all bus routes.
- It creates a route if needed.
- It knows the central dispatch system.

`LogManager` is the central office for loggers.

Why singleton?

Logging is usually global infrastructure. Most parts of an application should
agree on the same root logger, same logger registry, and same background
processor.

Tradeoff:

Singletons are convenient, but they can make tests harder because global state
survives between tests. A larger production system might inject a `LogManager`
or use a configurable logging context.

Holistic lesson:

Singleton can be reasonable for infrastructure, but know the cost: hidden global
state. Use it intentionally, not automatically.

---

## Module 13: End-To-End Walkthrough

Use this code from `LoggingFrameworkDemo.java`:

```java
LogManager logManager = LogManager.getInstance();
Logger rootLogger = logManager.getRootLogger();
rootLogger.setLevel(LogLevel.INFO);
rootLogger.addAppender(new ConsoleAppender());

Logger mainLogger = logManager.getLogger("com.example.Main");
mainLogger.info("Application starting up.");
mainLogger.debug("This is a debug message, it should NOT appear.");
mainLogger.warn("This is a warning message.");
```

Trace `mainLogger.info(...)`:

1. `mainLogger.info(...)` calls `log(LogLevel.INFO, message)`.
2. `mainLogger.getEffectiveLevel()` finds no level on `mainLogger`.
3. It walks to parent loggers until root.
4. Root has `INFO`, so effective level is `INFO`.
5. Message level `INFO` is greater than or equal to `INFO`.
6. `Logger` creates a `LogMessage`.
7. `mainLogger.callAppenders(...)` finds no appenders on `mainLogger`.
8. Additivity is true, so it calls parent appenders upward.
9. Root has `ConsoleAppender`.
10. `AsyncLogProcessor` receives the `LogMessage` and root appender list.
11. Background thread calls `ConsoleAppender.append(...)`.
12. `ConsoleAppender` asks `SimpleTextFormatter` to format.
13. The formatted text goes to `System.out`.

Trace `mainLogger.debug(...)` before root changes to `DEBUG`:

1. Message level is `DEBUG`.
2. Effective level is root's `INFO`.
3. `DEBUG.isGreaterOrEqual(INFO)` is false.
4. No `LogMessage` is created.
5. No appender is called.

That last part is important. Filtering early avoids doing unnecessary work.

Trace after:

```java
rootLogger.setLevel(LogLevel.DEBUG);
mainLogger.debug("This debug message should now be visible.");
```

Now effective level is `DEBUG`, so the debug message passes.

Holistic lesson:

A clean system often has one obvious path for the common case. Here the path is:

```text
level check -> event creation -> appender routing -> formatting -> output
```

---

## Module 14: Design Patterns In This Solution

### Strategy Pattern

Used by:

- `LogAppender`
- `LogFormatter`

Why:

The framework needs to swap destinations and formats without changing `Logger`.

Pattern shape:

```text
Context depends on interface.
Concrete classes implement different behavior.
```

In this code:

```text
Logger -> LogAppender interface -> ConsoleAppender/FileAppender
Appender -> LogFormatter interface -> SimpleTextFormatter
```

### Singleton Pattern

Used by:

- `LogManager`

Why:

The framework wants one global registry of loggers and one root logger.

### Facade Pattern

Used by:

- `Logger`

Why:

Client code gets a simple interface: `info`, `debug`, `warn`, `error`, `fatal`.
The caller does not deal with processors, appenders, or formatters.

### Producer-Consumer Pattern

Used by:

- `AsyncLogProcessor`

Why:

Application threads produce log events. A background thread consumes and writes
them.

### Chain-Like Responsibility Through Parent Loggers

Used by:

- `Logger.callAppenders(...)`

Why:

If the current logger does not fully handle output, the event can continue up to
parent loggers through additivity.

Be careful with pattern names:

Do not force every class into a famous pattern. The point is responsibility,
not vocabulary. Pattern names are useful only when they help you explain why
the shape exists.

---

## Module 15: Why This Design Makes You A Better Programmer

This solution teaches a general way to think:

1. Keep the caller's API simple.
2. Turn important events into objects.
3. Separate fixed flow from changeable behavior.
4. Use interfaces where future variation is expected.
5. Push rules into the type that owns the concept.
6. Protect shared state when multiple threads are involved.
7. Think about lifecycle when background work exists.

You can reuse the same thinking in many systems.

### Parallel 1: Notification System

```text
NotificationService -> NotificationMessage -> NotificationChannel -> Formatter
```

Examples:

- Email channel.
- SMS channel.
- Push notification channel.
- Slack channel.

The same pattern appears:

- `NotificationMessage` is like `LogMessage`.
- `NotificationChannel` is like `LogAppender`.
- `EmailFormatter` is like `LogFormatter`.
- Async sending is like `AsyncLogProcessor`.

### Parallel 2: Payment System

```text
CheckoutService -> PaymentRequest -> PaymentMethod
```

Examples:

- Credit card.
- Wallet.
- Bank transfer.

The stable flow is "charge this order." The variable behavior is "how to charge."
That variable behavior becomes a strategy.

### Parallel 3: File Export System

```text
Report -> ReportFormatter -> ExportDestination
```

Examples:

- Format as PDF, CSV, JSON.
- Export to local file, S3, email.

Same principle:

- Keep data separate from presentation.
- Keep presentation separate from destination.

Once you see this, LLD becomes less mysterious. Many problems are pipelines
where each stage owns one decision.

---

## Module 16: Extension Lab 1 - Add A JSON Formatter

Goal:

Add a formatter that prints structured JSON-like output.

File to create:

```text
strategies/formatter/JsonLogFormatter.java
```

Example:

```java
package loggingframework.strategies.formatter;

import loggingframework.entities.LogMessage;

public class JsonLogFormatter implements LogFormatter {
    @Override
    public String format(LogMessage logMessage) {
        return String.format(
                "{\"timestamp\":\"%s\",\"thread\":\"%s\",\"level\":\"%s\",\"logger\":\"%s\",\"message\":\"%s\"}",
                logMessage.getTimestamp(),
                logMessage.getThreadName(),
                logMessage.getLevel(),
                logMessage.getLoggerName(),
                escape(logMessage.getMessage())
        );
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
```

Use it:

```java
ConsoleAppender appender = new ConsoleAppender();
appender.setFormatter(new JsonLogFormatter());
rootLogger.addAppender(appender);
```

Learning target:

You changed formatting without touching `Logger`, `LogManager`, or
`AsyncLogProcessor`. That is the Strategy pattern paying rent.

---

## Module 17: Extension Lab 2 - Add A Memory Appender For Tests

Goal:

Create an appender that stores log messages in a list. This helps unit tests
assert what was logged without reading console output.

Example:

```java
package loggingframework.strategies.appender;

import loggingframework.entities.LogMessage;
import loggingframework.strategies.formatter.LogFormatter;
import loggingframework.strategies.formatter.SimpleTextFormatter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class MemoryAppender implements LogAppender {
    private final List<String> messages = new CopyOnWriteArrayList<>();
    private LogFormatter formatter = new SimpleTextFormatter();

    @Override
    public void append(LogMessage logMessage) {
        messages.add(formatter.format(logMessage));
    }

    public List<String> getMessages() {
        return messages;
    }

    @Override
    public void close() {
    }

    @Override
    public LogFormatter getFormatter() {
        return formatter;
    }

    @Override
    public void setFormatter(LogFormatter formatter) {
        this.formatter = formatter;
    }
}
```

Test idea:

```java
MemoryAppender appender = new MemoryAppender();
Logger logger = LogManager.getInstance().getLogger("test.UserService");
logger.setLevel(LogLevel.INFO);
logger.setAdditivity(false);
logger.addAppender(appender);

logger.info("created");
```

Because this framework is async, your test must wait for the processor or expose
a synchronous test mode. This is a good moment to notice how async design affects
test design.

Learning target:

Interfaces are not only for production extensions. They also make testing
easier.

---

## Module 18: Extension Lab 3 - Add File Logging

The existing `FileAppender` can be attached like this:

```java
Logger root = LogManager.getInstance().getRootLogger();
root.addAppender(new FileAppender("app.log"));
```

Then a message can go to both console and file:

```java
root.addAppender(new ConsoleAppender());
root.addAppender(new FileAppender("app.log"));
```

This is a major feature with almost no extra core code because `Logger` already
stores:

```java
private final List<LogAppender> appenders;
```

and calls:

```java
for (LogAppender appender : appenders) {
    appender.append(logMessage);
}
```

Learning target:

Designing for "many" instead of "one" often means using a collection of
interfaces.

---

## Module 19: Common Mistakes And How To Think Past Them

### Mistake 1: Treating logging as string printing

Better thought:

Logging is event capture plus routing plus presentation plus output.

### Mistake 2: Putting all logic in Logger

Bad symptom:

`Logger` knows how to write console, file, database, JSON, XML, and email.

Better thought:

`Logger` should decide whether to log and where to route. Appenders and
formatters handle the rest.

### Mistake 3: Forgetting lifecycle

Async systems need shutdown. Files need close. Queues need draining.

Better thought:

Every resource you open needs an ownership and closing story.

### Mistake 4: Ignoring duplicate logs

If child and parent both have appenders and additivity is true, duplicate output
can happen.

Better thought:

Hierarchy is powerful, but configuration must be deliberate.

### Mistake 5: Thinking interfaces are only abstraction theater

Interfaces are useful when behavior varies independently:

- Destination varies: appender interface.
- Format varies: formatter interface.

Better thought:

An interface earns its place when it protects stable code from likely change.

---

## Module 20: Interview Explanation Script

If you need to explain this design in an interview, say it in layers:

1. "I would expose a simple `Logger` API with methods like `info`, `debug`, and
   `error`."
2. "Each call creates a `LogMessage` containing timestamp, level, logger name,
   thread name, and message text."
3. "The logger checks the message level against its effective configured level."
4. "Loggers are named hierarchically, so `com.app.service.UserService` can
   inherit settings from parent loggers and root."
5. "Output destinations are modeled with a `LogAppender` interface, so console,
   file, and database output can be added independently."
6. "Formatting is modeled with a `LogFormatter` interface, so text, JSON, or
   custom formats do not affect routing."
7. "For thread safety and responsiveness, log writes are sent through an async
   processor. Shared logger lookup uses concurrent collections."
8. "On shutdown, the processor is stopped and appenders are closed."

Then mention tradeoffs:

- A single async worker is simple but may bottleneck.
- A singleton manager is convenient but can make tests harder.
- File appenders need careful null and error handling in production.
- Production logging may need rotation, batching, bounded queues, and structured
  context.

This answer shows both design clarity and engineering maturity.

---

## Module 21: Production Improvements To Consider

This repo is an LLD learning solution, not a full production logger. If you were
hardening it, consider:

| Improvement | Why it matters |
| --- | --- |
| Bounded queue | Prevent memory growth under massive log volume. |
| Backpressure policy | Decide what happens when logs arrive faster than they can be written. |
| Batch writes | Improve throughput for file, database, or network destinations. |
| File rotation | Avoid one log file growing forever. |
| Safer `FileAppender` constructor | Handle failed writer creation without later `NullPointerException`. |
| Clear line-ending ownership | Avoid double blank lines when formatters and appenders both add newlines. |
| Stronger config visibility | Make dynamic changes to level and additivity reliably visible across threads. |
| Structured fields | Support request id, user id, trace id, and key-value metadata. |
| Logger config object | Load levels and appenders from config files. |
| Synchronous test mode | Make unit tests deterministic. |
| Appender failure isolation | One broken appender should not break every destination. |
| Better JSON escaping | Avoid hand-written JSON formatting. |

The key is to avoid jumping to all of this at once in an interview. Build the
core shape first, then discuss how you would evolve it.

---

## Module 22: Rebuild From Memory

Try implementing the system again without looking at the files.

Step 1: Create `LogLevel`.

- Add `DEBUG`, `INFO`, `WARN`, `ERROR`, `FATAL`.
- Add comparison logic.

Step 2: Create `LogMessage`.

- Capture timestamp.
- Capture level.
- Capture logger name.
- Capture thread name.
- Capture message text.

Step 3: Create formatter interface.

- `String format(LogMessage message)`

Step 4: Create appender interface.

- `append(LogMessage message)`
- `close()`
- formatter getters and setters.

Step 5: Implement console appender.

- Default to `SimpleTextFormatter`.
- Print formatted message.

Step 6: Implement file appender.

- Open writer.
- Write formatted message.
- Flush.
- Close.

Step 7: Implement logger.

- Store name, parent, level, appenders, and additivity.
- Implement effective level.
- Implement `log`.
- Implement convenience methods.

Step 8: Implement manager.

- Singleton instance.
- Root logger.
- Concurrent logger map.
- Hierarchical logger creation.
- Shutdown.

Step 9: Add async processor.

- Single worker executor.
- Submit appender calls.
- Stop gracefully.

Step 10: Write a demo.

- Root logger at `INFO`.
- Console appender.
- Child logger with inherited level.
- One logger overriding to `DEBUG`.
- Dynamic level change.
- Shutdown.

If you can rebuild this, you understand the design.

---

## Module 23: Checkpoint Questions

Use these to test yourself.

1. Why is `LogMessage` immutable?
2. Why should `LogLevel` own level comparison?
3. What is the difference between configured level and effective level?
4. Why does `LogManager` create parent loggers recursively?
5. What problem does additivity solve?
6. How can additivity cause duplicate logs?
7. Why is `LogAppender` an interface?
8. Why is `LogFormatter` separate from `LogAppender`?
9. Why does the framework capture thread name before async processing?
10. What could go wrong if the application exits without shutdown?
11. Why is `CopyOnWriteArrayList` a reasonable choice for appenders?
12. What production problem can a single-thread async processor create?

Answers:

1. A log event should not change after it is captured, especially if processed
   asynchronously.
2. The comparison rule belongs near the severity concept and avoids scattered
   numeric checks.
3. Configured level is directly set on a logger. Effective level is the first
   configured level found by walking up parents.
4. A name like `com.example.service.UserService` implies a hierarchy, and each
   node may carry configuration.
5. It lets child logs also flow to parent appenders, commonly root appenders.
6. If both child and parent have appenders, the same log event can be written by
   both.
7. Destinations vary. The logger should not change when a new destination is
   added.
8. Destination and presentation vary independently.
9. The async worker thread is not the original caller thread.
10. Queued logs may not be written and file resources may not close.
11. Appender lists are read often and modified rarely, which suits
   copy-on-write behavior.
12. It can become a bottleneck under high log volume.

---

## Module 24: The One-Sentence Summary

This logging framework converts simple calls like `logger.info("...")` into
structured log events, filters them by severity and hierarchy, then routes them
asynchronously through pluggable appenders and formatters.

If you remember that sentence, the whole design has a place to attach in your
mind.
