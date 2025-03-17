# Akces Framework Services

## Overview

The Akces Framework provides a set of specialized services that implement the CQRS (Command Query Responsibility Segregation) and Event Sourcing patterns using Apache Kafka as the underlying infrastructure. This document describes the main services in the framework and how they interact to form a complete event-driven architecture.

## Core Services Architecture

The Akces Framework consists of three main service types that work together:

1. **Command Services** - Handle commands and validate business logic
2. **Aggregate Services** - Maintain event-sourced state for domain entities
3. **Query Services** - Provide optimized read models for client applications

These services are managed by the Akces Operator, a Kubernetes operator that automates the deployment, scaling, and management of the Akces services in a Kubernetes environment.

## Akces Operator

The Akces Operator is a Kubernetes operator built using the Java Operator SDK that manages the lifecycle of Akces Framework services in a Kubernetes cluster.

### Features

- Automated deployment of Command, Aggregate, and Query services
- Kafka topic management (creation and configuration)
- State management for deployed services
- Resource scaling based on workload
- Configuration management

### Custom Resources

The operator defines three Custom Resource Definitions (CRDs):

#### 1. Aggregate

The Aggregate custom resource defines an Aggregate service that maintains the state of domain entities using event sourcing.

```yaml
apiVersion: akces.elasticsoftware.org/v1
kind: Aggregate
metadata:
  name: account-aggregate
spec:
  replicas: 3
  image: ghcr.io/elasticsoftwarefoundation/akces-aggregate-service:0.9.0
  aggregateNames:
    - Account
    - OrderProcessManager
    - Wallet
  applicationName: "Akces Account Aggregate Service"
  enableSchemaOverwrites: false
  args:
    - "--spring.profiles.active=prod"
  resources:
    requests:
      memory: "512Mi"
      cpu: "500m"
    limits:
      memory: "1Gi"
      cpu: "1000m"
```

#### 2. CommandService

The CommandService custom resource defines a Command service that validates and processes commands before sending them to the appropriate aggregate.

```yaml
apiVersion: akces.elasticsoftware.org/v1
kind: CommandService
metadata:
  name: account-command
spec:
  replicas: 3
  image: ghcr.io/elasticsoftwarefoundation/akces-command-service:0.9.0
  applicationName: "Akces Account Command Service"
  args:
    - "--spring.profiles.active=prod"
  resources:
    requests:
      memory: "512Mi"
      cpu: "500m"
    limits:
      memory: "1Gi"
      cpu: "1000m"
```

#### 3. QueryService

The QueryService custom resource defines a Query service that provides optimized read models for client applications.

```yaml
apiVersion: akces.elasticsoftware.org/v1
kind: QueryService
metadata:
  name: account-query
spec:
  replicas: 3
  image: ghcr.io/elasticsoftwarefoundation/akces-query-service:0.9.0
  applicationName: "Akces Account Query Service"
  args:
    - "--spring.profiles.active=prod"
  env:
    - name: DB_HOST
      value: "postgres.default"
    - name: DB_PORT
      value: "5432"
  applicationProperties: |
    # Custom application properties
    spring.datasource.url=jdbc:postgresql://${DB_HOST}:${DB_PORT}/accounts
    spring.datasource.username=${DB_USER}
    spring.datasource.password=${DB_PASSWORD}
    spring.jpa.hibernate.ddl-auto=validate
  resources:
    requests:
      memory: "512Mi"
      cpu: "500m"
    limits:
      memory: "1Gi"
      cpu: "1000m"
```

### Reconciliation Process

For each custom resource, the operator:

1. Creates a ConfigMap with the service configuration
2. Deploys a StatefulSet to run the service instances
3. Creates a Service for network access
4. Manages Kafka topics required by the service
5. Updates the status of the resource based on the StatefulSet's status

## Service Details

### Aggregate Service

The Aggregate Service is responsible for:

- Processing commands received from the Command Service
- Maintaining the event-sourced state of domain aggregates
- Applying business logic through command handlers
- Emitting domain events to Kafka topics
- Storing state snapshots for efficient recovery

Key characteristics:
- Stateful service (requires persistent storage)
- Uses RocksDB for state storage
- Consumes and produces to Kafka topics
- Processes events in a strictly ordered fashion per aggregate

Configuration:
- Deployed with a PersistentVolumeClaim for state storage
- Configured with Kafka connection details
- Uses ZGC for efficient memory management
- Exposes health endpoints for monitoring

### Command Service

The Command Service is responsible for:

- Receiving commands from client applications
- Validating command structure and content
- Routing commands to the appropriate aggregate
- Managing command response handling
- Implementing schema validation using JSON Schema

Key characteristics:
- Stateless service
- Acts as the entry point for write operations
- Handles command validation and routing
- Provides synchronous and asynchronous APIs for clients

Configuration:
- Deployed without persistent storage
- Configured with Kafka connection details
- Exposes HTTP endpoints for client requests

### Query Service

The Query Service is responsible for:

- Building and maintaining read models from domain events
- Providing optimized views of domain data
- Serving queries from client applications
- Updating read models based on domain events

Key characteristics:
- Stateful service (requires persistent storage)
- Consumes domain events from Kafka topics
- Maintains query-optimized state
- Can integrate with traditional databases (SQL, NoSQL)

Configuration:
- Deployed with a PersistentVolumeClaim for state storage
- Can be configured with custom application properties
- Supports custom environment variables for database connections
- Exposes HTTP endpoints for client queries

## Kafka Topic Structure

The operator manages the following Kafka topics for each aggregate:

- `<AggregateName>-Commands` - Commands sent to the aggregate
- `<AggregateName>-DomainEvents` - Domain events emitted by the aggregate
- `<AggregateName>-AggregateState` - Compacted topic storing the latest state of each aggregate instance

Additionally, the framework uses the following system topics:

- `Akces-Control` - Control messages and metadata
- `Akces-CommandResponses` - Responses to commands (for client notification)
- `Akces-GDPRKeys` - Encryption keys for GDPR-protected data

## Deployment Patterns

The Akces services can be deployed in various patterns:

1. **Monolithic Deployment** - All three service types deployed together for simple applications
2. **Microservice Deployment** - Each aggregate type gets its own set of services
3. **Hybrid Deployment** - Command services handle multiple aggregates, while query services are specialized

## Configuration and Integration

### Spring Boot Integration

All Akces services are built on Spring Boot and provide:

- Health endpoints for monitoring
- Graceful shutdown
- Structured logging with Logback
- Support for configuration via properties files or environment variables

### Customization Options

Each service type can be customized:

- Custom application properties
- Environment variables
- Resource limits and requests
- Replica count for scaling
- Custom Docker images for domain-specific logic

### High Availability

The operator deploys services with:

- Multiple replicas for redundancy
- Affinity rules for distributing across nodes
- Probes for health checking
- Graceful shutdown handling

## Usage Examples

### Deploying a Complete CQRS System

To deploy a complete CQRS system for a domain, create instances of all three CRDs:

```yaml
# 1. Create the Aggregate Service
apiVersion: akces.elasticsoftware.org/v1
kind: Aggregate
metadata:
  name: wallet-aggregate
spec:
  replicas: 3
  image: ghcr.io/elasticsoftwarefoundation/akces-aggregate-service:0.9.0
  aggregateNames:
    - Wallet
  applicationName: "Wallet Aggregate Service"
  enableSchemaOverwrites: false

---
# 2. Create the Command Service
apiVersion: akces.elasticsoftware.org/v1
kind: CommandService
metadata:
  name: wallet-command
spec:
  replicas: 3
  image: ghcr.io/elasticsoftwarefoundation/akces-command-service:0.9.0
  applicationName: "Wallet Command Service"

---
# 3. Create the Query Service
apiVersion: akces.elasticsoftware.org/v1
kind: QueryService
metadata:
  name: wallet-query
spec:
  replicas: 3
  image: ghcr.io/elasticsoftwarefoundation/akces-query-service:0.9.0
  applicationName: "Wallet Query Service"
  applicationProperties: |
    spring.application.name=Wallet Query Service
    akces.querymodels.enabled=true
    akces.querymodels.packages=com.example.wallet.query
```

### Scaling Services

To scale a service, update the `replicas` field:

```yaml
apiVersion: akces.elasticsoftware.org/v1
kind: Aggregate
metadata:
  name: wallet-aggregate
spec:
  replicas: 5  # Increased from 3 to 5
  # other fields remain the same
```

### Accessing Service Information

The operator updates the status of each resource with information about the running service:

```bash
kubectl get aggregate wallet-aggregate -o yaml
```

Example output:
```yaml
apiVersion: akces.elasticsoftware.org/v1
kind: Aggregate
metadata:
  name: wallet-aggregate
spec:
  # [...]
status:
  readyReplicas: 3
```

## Best Practices

1. **Resource Planning**
   - Size CPU and memory resources based on the expected load
   - Monitor resource usage to adjust as needed

2. **Persistence**
   - Use SSDs or high-performance disks for state storage
   - In GCP, consider using Hyperdisk Balanced for optimal performance

3. **Scaling**
   - Horizontally scale services to match workload
   - Ensure Kafka partitions match or exceed service replica count

4. **Monitoring**
   - Set up monitoring for the health endpoints
   - Monitor Kafka lag to detect processing delays

5. **Backup**
   - Regular backups of Kafka topics are recommended
   - Implement disaster recovery plans for data safety

## Troubleshooting

Common issues and solutions:

1. **Service Not Starting**
   - Check ConfigMap exists and has correct format
   - Verify image pull secrets are configured
   - Check resource constraints

2. **Command Processing Issues**
   - Verify Kafka topics exist and are accessible
   - Check schema compatibility in Schema Registry
   - Review service logs for validation errors

3. **Query Service Not Updating**
   - Check Kafka consumer lag
   - Verify event handlers are correctly implemented
   - Check database connectivity if using external databases

4. **Performance Issues**
   - Tune Kafka parameters for performance
   - Adjust JVM settings via environment variables
   - Scale up resources or increase replicas

5. **Operator Issues**
   - Check operator logs for reconciliation errors
   - Verify RBAC permissions for the operator

## Conclusion

The Akces Framework services provide a complete implementation of CQRS and Event Sourcing patterns using Kafka as the backbone infrastructure. The Kubernetes operator simplifies the deployment and management of these services, allowing teams to focus on domain-specific implementations rather than infrastructure concerns.

By separating commands, aggregates, and queries into distinct services, the framework provides flexibility, scalability, and resilience while maintaining the integrity of the event-sourced data model.
