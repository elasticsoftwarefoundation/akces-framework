# Akces Framework Comprehensive Overview

Akces is an event-sourcing and CQRS (Command Query Responsibility Segregation) framework built on top of Kafka. It provides a structured approach to building distributed, event-driven applications with a focus on domain events, aggregates, and command handling.

## Main Purpose

Akces provides a comprehensive solution for implementing event-sourcing-based applications. Its primary goal is to simplify building scalable, event-driven systems by providing:

1. A structured approach to defining domain models and handling commands
2. A robust event-sourcing mechanism for maintaining aggregate state
3. Support for distributed processing of commands and events
4. A query model system to support the "read side" of CQRS patterns
5. Privacy-focused data handling through GDPR-compliant encryption

## Key Features

### 1. Event Sourcing

Akces implements event sourcing patterns where the state of an aggregate is derived from a sequence of domain events. Key aspects include:

- Event storage in Kafka topics
- Automatic event application to update aggregate state
- Support for maintaining aggregate state in RocksDB
- Precise event versioning and schema validation

### 2. Command Processing

The framework provides a structured approach to command handling:

- Command validation and routing
- Transaction-based command processing
- Command response handling
- Error event generation for failed commands

### 3. Query Models

Separate from the command side, Akces provides:

- Query model state derived from events
- Database models for persistent storage 
- Support for JPA and JDBC implementations
- Event handlers for query models

### 4. GDPR Compliance

The framework includes built-in support for personal data protection:

- Annotation-based PII data marking
- Transparent encryption/decryption of sensitive data
- Context-aware encryption keys

### 5. Schema Registry Integration

For maintaining compatibility and validation:

- Integration with Confluent Schema Registry
- JSON schema generation and compatibility checking
- Schema versioning support

## Architecture

Akces has a modular architecture organized into the following components:

### Core Modules

1. **API Module**: Contains interfaces and annotations defining the core concepts
   - Aggregates, Commands, Events
   - Event handlers, Command handlers
   - Query models

2. **Runtime Module**: Provides the core event-sourcing implementation
   - Aggregate runtime for handling commands
   - State repositories and management
   - GDPR context management
   - Kafka integration

3. **Client Module**: Handles interaction with the command side
   - Command sending and validation
   - Schema registry integration
   - Command response processing

4. **Query-Support Module**: Handles query models and read-side concerns
   - Database model interaction
   - Query model state handling
   - Event processing for query models

5. **Shared Module**: Common utilities and shared classes
   - Protocol records for communication
   - Serialization/deserialization
   - GDPR utilities
   - Kafka utilities

### Key Components

1. **Aggregates**: The core domain objects that encapsulate business logic
   - Maintain their state through event sourcing
   - Process commands and emit events
   - Handle incoming domain events

2. **Commands**: Represent requests to modify state
   - Validated through schemas
   - Routed to appropriate aggregates
   - Generate domain events

3. **Domain Events**: Represent facts that have occurred
   - The source of truth for aggregate state
   - Used to rebuild state
   - Consumed by query models

4. **Query Models**: Read-optimized representations of data
   - Updated via event handlers
   - Provide fast access to application state

5. **Controllers**: Manage runtime and communication
   - `AkcesAggregateController`: Manages aggregate partitions and command handling
   - `AkcesDatabaseModelController`: Manages database models
   - `AkcesQueryModelController`: Manages query models
   - `AkcesClientController`: Client-side controller for sending commands

6. **Partitioning**: Ensures scalability
   - Kafka-based partitioning of aggregates
   - Command routing based on aggregate ID
   - Parallel processing across partitions

## Workflow

A typical workflow in Akces follows this pattern:

1. A client sends a command to modify an aggregate's state via the Akces client
2. The command is routed to the appropriate aggregate partition
3. The aggregate processes the command, generating one or more domain events
4. The events are stored in Kafka and applied to the aggregate state
5. Query models and database models consume the events to update their state
6. Command responses are sent back to clients

## Deployment Architecture

Akces is designed for distributed deployment with:

- Kafka as the communication and storage backbone
- RocksDB for local state storage
- Schema Registry for schema validation
- Different services for command and query sides

## Key Design Patterns

1. **Event Sourcing**: State is derived from events rather than stored directly
2. **CQRS**: Separate models for reading and writing
3. **Domain-Driven Design**: Focus on aggregates, commands, and events
4. **Partitioning**: Distribution of processing across multiple instances
5. **Annotation-based Configuration**: Using annotations to define behavior

## Technical Implementation

Akces relies on several key technologies:

- **Spring Framework**: For dependency injection and configuration
- **Kafka**: As the event store and messaging backbone
- **RocksDB**: For local state storage
- **Jackson**: For JSON serialization/deserialization
- **Protobuf**: For efficient binary serialization
- **Confluent Schema Registry**: For schema validation and evolution

The framework provides both a programming model (through annotations and interfaces) and a runtime environment for executing event-sourced applications.

## Conclusion

Akces is a comprehensive framework for building event-sourced, CQRS-based applications with a focus on scalability, resilience, and privacy. It provides a structured approach to domain modeling while handling the complex infrastructure concerns of distributed systems.
