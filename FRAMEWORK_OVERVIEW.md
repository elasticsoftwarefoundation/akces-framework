# Akces Framework: Comprehensive Overview

Akces is an event-sourcing and CQRS (Command Query Responsibility Segregation) framework built on top of Apache Kafka. It provides a structured way to implement domain-driven design (DDD) patterns in distributed systems while leveraging Kafka's robustness for event streaming.

## Core Purpose

The primary purpose of Akces is to simplify building event-sourced, distributed applications with clear separation between command and query responsibilities. It provides:
1. A structured approach to defining aggregates, commands, and events
2. Automatic schema evolution and compatibility checking
3. Built-in support for GDPR compliance with encryption of personally identifiable information (PII)
4. Query model support for read-optimized views of data

## Key Components

### 1. Architectural Overview

The framework is modular and organized into several key components:

- **api**: Core interfaces and annotations defining the domain modeling primitives
- **runtime**: Implementation of the event-sourcing engine that processes commands and events
- **client**: Client library for sending commands to the system
- **query-support**: Infrastructure for building read models based on event streams
- **shared**: Common utilities and shared components

### 2. Core Domain Concepts

#### Aggregates
Aggregates are the central domain objects that encapsulate business logic and data consistency rules:
- Annotated with `@AggregateInfo`
- Handle commands via `@CommandHandler` methods
- Apply events via `@EventSourcingHandler` methods
- Can react to external events via `@EventHandler` methods

#### Commands
Commands represent intentions to change the system state:
- Immutable data objects implementing the `Command` interface
- Annotated with `@CommandInfo` for schema management
- Routed to appropriate aggregate instances

#### Events
Events represent facts that have occurred in the system:
- Immutable data objects implementing the `DomainEvent` interface
- Annotated with `@DomainEventInfo` for schema management
- Used to reconstruct aggregate state (event sourcing)
- Can trigger changes in query models

#### Query Models
Read-optimized views derived from domain events:
- Annotated with `@QueryModelInfo`
- Define `@QueryModelEventHandler` methods to handle domain events
- Support indexing for efficient querying

### 3. Key Features

#### Schema Management
- JSON Schema generation and validation
- Schema evolution with backward compatibility checks
- Versioning support for commands and events

#### GDPR Support
The framework provides built-in PII data protection:
- `@PIIData` annotation for marking sensitive fields
- Transparent encryption/decryption with the `GDPRContext` mechanism
- Key management through `RocksDBGDPRContextRepository`

#### Kafka Integration
Leverages Kafka for:
- Command routing and distribution
- Event storage and distribution
- Scalable processing with partition-based sharding
- Transactional processing guarantees

#### State Management
- Event sourcing for aggregate state reconstruction
- Persistent state storage using RocksDB
- Indexing for efficient querying

## Architecture Deep Dive

### 1. Command Flow
1. Client sends a command via `AkcesClient`
2. Command is validated against its JSON schema
3. Command is routed to the correct aggregate instance via Kafka
4. Command is processed by the appropriate `@CommandHandler` method
5. Events are generated and applied to the aggregate via `@EventSourcingHandler`
6. Events are persisted to Kafka and the updated state is stored
7. Events are optionally indexed for query models

### 2. Query Flow
1. Query models subscribe to domain events
2. Events are processed by `@QueryModelEventHandler` methods
3. Query model state is updated and indexed
4. Client requests data from query models via `QueryModels` interface

### 3. Runtime Processing
The `AkcesAggregateController` manages partitions of aggregate instances:
- Each partition is processed by an `AggregatePartition` thread
- Commands and events are processed transactionally
- State is stored persistently with RocksDB
- PII data is automatically encrypted/decrypted

### 4. Schema Evolution
The framework supports versioning and evolution of schemas:
- Backward compatibility is enforced
- Version numbers are tracked and validated
- Schema registry integration ensures consistency

## Technical Implementation

The framework uses several technologies:
- **Kafka**: For event streaming and command distribution
- **RocksDB**: For persistent state storage
- **JSON Schema**: For schema definition and validation
- **Jackson**: For serialization/deserialization
- **Spring Boot**: For application configuration and lifecycle management

Key design patterns:
- **Event Sourcing**: Reconstructing state from a sequence of events
- **CQRS**: Separating command and query responsibilities
- **Domain-Driven Design**: Focus on domain modeling and ubiquitous language
- **Functional interfaces**: Clean separation of behavior through handler functions

## Usage Example

A typical aggregate definition would look like:

```java
@AggregateInfo(value = "Account", generateGDPRKeyOnCreate = true)
public final class Account implements Aggregate<AccountState> {
    @CommandHandler(create = true, produces = AccountCreatedEvent.class, errors = {})
    public Stream<AccountCreatedEvent> create(CreateAccountCommand cmd, AccountState isNull) {
        return Stream.of(new AccountCreatedEvent(cmd.userId(), cmd.country(), cmd.firstName(), cmd.lastName(), cmd.email()));
    }

    @EventSourcingHandler(create = true)
    public AccountState create(AccountCreatedEvent event, AccountState isNull) {
        return new AccountState(event.userId(), event.country(), event.firstName(), event.lastName(), event.email());
    }
}
```

## Summary

The Akces Framework provides a comprehensive solution for building event-sourced, distributed systems on top of Kafka. It combines the benefits of event sourcing, CQRS, and domain-driven design while addressing practical concerns such as schema evolution and GDPR compliance. The framework is designed to be modular, with clear separation of concerns and a focus on type safety and maintainability.
