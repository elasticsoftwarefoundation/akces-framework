# Akces Framework Analysis

## Overview

Akces is an event-sourcing and CQRS (Command Query Responsibility Segregation) framework built on top of Apache Kafka. It provides infrastructure for building distributed applications using domain-driven design (DDD) principles, with a focus on event-sourced aggregates, command handling, and queryable projections.

## Main Purpose

The framework enables developers to:
1. Build event-sourced domain models with clear separation of commands and queries
2. Implement complex business logic through aggregates that respond to commands and emit events
3. Create process managers to orchestrate workflows across multiple aggregates
4. Build materialized views (query models) to efficiently read and query state derived from events
5. Handle privacy concerns with built-in GDPR support for PII (Personally Identifiable Information)

## Key Architecture Components

### 1. Aggregates and Commands

- **Aggregates**: Core domain entities (like `Account`, `Wallet`) that encapsulate business logic
- **Commands**: Requests to perform actions that may change an aggregate's state
- **Command Handlers**: Process commands, validate business rules, and produce domain events
- **Event Sourcing Handlers**: Apply domain events to update the aggregate's state

### 2. Events and Event Processing

- **Domain Events**: Represent facts that have occurred in the system
- **Event Handlers**: Process events to trigger side effects or additional processing
- **Event Sourcing**: Rebuilding aggregate state by replaying events

### 3. Process Managers

- Process managers coordinate workflows across multiple aggregates
- They react to events from one aggregate and issue commands to others

### 4. Query Models

- **Query Models**: Read-optimized projections of aggregate state
- **Database Models**: Support for storing events in a database for querying
- Built for efficient reading rather than transaction processing

### 5. Infrastructure

- **Kafka Integration**: Uses Kafka as the event store and message bus
- **Schema Registry**: Validates and evolves event and command schemas
- **GDPR Support**: Special handling for PII data with encryption

## Main Components and Modules

### API Module (`akces-api`)
Contains core interfaces that define the contracts for the framework:
- `Aggregate`, `AggregateState`
- `Command`, `CommandBus`
- `DomainEvent`
- Various handler functions

### Runtime Module (`akces-runtime`)
Provides the runtime implementation for aggregates:
- `AggregateRuntime`: Core business logic executor
- `AggregatePartition`: Processes commands and maintains state for a partition
- Kafka integration for event sourcing and command handling
- State storage with RocksDB

### Client Module (`akces-client`)
Provides an interface for applications to send commands to aggregates:
- `AkcesClient`: API for sending commands
- `AkcesClientController`: Manages connections to Kafka

### Query Support Module (`akces-query-support`)
Handles the read/query side of CQRS:
- `QueryModel`: Defines read-optimized views
- `DatabaseModel`: Supports persistence of query models
- JDBC and JPA implementations for database access

### Shared Module (`akces-shared`)
Contains common code shared across modules:
- Protocol definitions
- GDPR support for PII handling
- Kafka utilities
- Serialization logic

## Key Features

### 1. Event Sourcing with Kafka

Events are the source of truth, stored in Kafka topics, enabling:
- Complete audit trail of all changes
- Ability to replay events to rebuild state
- Temporal queries (state at a point in time)

### 2. CQRS Implementation

Clear separation between write and read models:
- Command-side: Handles business logic validation via aggregates
- Query-side: Optimized read models derived from events

### 3. Strong Schema Evolution

- Uses JSON Schema for validation
- Backward compatibility checking
- Schema registration and versioning

### 4. GDPR Compliance

- Built-in support for PII data handling
- Encryption and decryption of sensitive information
- Annotation-based approach with `@PIIData`

### 5. Scalable Partitioning

- Kafka-native partitioning for horizontal scaling
- Partition-level command processing
- Consistent hashing for routing

### 6. Process Manager Support

- Manages workflows across multiple aggregates
- Reacts to events and issues new commands
- Maintains process state between steps

## How It Works

1. **Command Flow**:
   - Client sends a command through `AkcesClient`
   - Command routes to appropriate aggregate partition
   - Command handler validates and produces domain events
   - Events update aggregate state and are stored in Kafka

2. **Event Processing**:
   - Events are published to Kafka topics
   - Event handlers process events for side effects
   - Query models subscribe to events to update their state

3. **Query Flow**:
   - Applications query read models directly
   - Query models provide optimized views of data
   - State is materialized from events

## Design Patterns Used

- Event Sourcing
- CQRS
- Domain-Driven Design
- Factory Pattern
- Adapter Pattern
- Repository Pattern
- Observer Pattern
- Builder Pattern

## Technical Implementation Details

- Java 17+ with modern language features
- Spring Framework integration
- Kafka as the backbone for communication and storage
- RocksDB for local state storage
- JSON Schema for validation
- Jackson for serialization
- Apache Avro & Confluent Schema Registry integration

This framework is designed for building complex, scalable, and maintainable applications with clear separation of concerns, high consistency, and excellent auditability, particularly suitable for financial, healthcare, and other domains requiring strong data integrity and compliance features.
