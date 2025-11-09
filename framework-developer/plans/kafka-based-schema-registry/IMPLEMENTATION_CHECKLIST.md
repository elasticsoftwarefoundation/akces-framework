# Implementation Checklist

This document provides a detailed, step-by-step checklist for implementing the Kafka-based schema registry.

## Pre-Implementation

- [x] Create detailed plan
- [x] Document architecture
- [x] Identify all affected components
- [x] Get architect approval
- [ ] Set up feature branch
- [ ] Ensure test environment available

---

## Phase 1: Core Infrastructure (Shared Module)

### 1.1 Create Storage Interface
**File**: `main/shared/src/main/java/org/elasticsoftware/akces/schemas/storage/KafkaTopicSchemaStorage.java`

- [ ] Create interface with methods:
  - [ ] `registerSchema(String schemaName, JsonSchema schema, int version)`
  - [ ] `getSchemas(String schemaName)`
  - [ ] `getSchema(String schemaName, int version)`
  - [ ] `deleteSchema(String schemaName, int version)`
  - [ ] `initialize()`
- [ ] Add JavaDoc for all methods
- [ ] Add license header

### 1.2 Create Schema Record Class
**File**: `main/shared/src/main/java/org/elasticsoftware/akces/schemas/storage/SchemaRecord.java`

- [ ] Create record with fields:
  - [ ] `String schemaName`
  - [ ] `int version`
  - [ ] `JsonSchema schema`
  - [ ] `String registeredAt`
- [ ] Add serialization annotations if needed
- [ ] Add license header

### 1.3 Create Storage Exception
**File**: `main/shared/src/main/java/org/elasticsoftware/akces/schemas/storage/KafkaTopicSchemaStorageException.java`

- [ ] Extend `SchemaException`
- [ ] Add constructors for various error scenarios
- [ ] Add license header

### 1.4 Implement Storage
**File**: `main/shared/src/main/java/org/elasticsoftware/akces/schemas/storage/KafkaTopicSchemaStorageImpl.java`

- [ ] Implement `KafkaTopicSchemaStorage` interface
- [ ] Add fields:
  - [ ] `KafkaProducer<String, String>` for writes
  - [ ] `KafkaConsumer<String, String>` for reads
  - [ ] `Cache<String, SchemaRecord>` (Caffeine)
  - [ ] `String topicName`
  - [ ] `ObjectMapper` for JSON serialization
- [ ] Implement `initialize()`:
  - [ ] Check if topic exists
  - [ ] Create topic if needed (compacted)
  - [ ] Load all schemas into cache
- [ ] Implement `registerSchema()`:
  - [ ] Serialize schema to JSON
  - [ ] Send to Kafka topic
  - [ ] Update cache
  - [ ] Handle errors
- [ ] Implement `getSchemas()`:
  - [ ] Check cache first
  - [ ] If cache miss, read from topic
  - [ ] Return list of all versions
- [ ] Implement `getSchema()`:
  - [ ] Check cache first
  - [ ] Return specific version
- [ ] Implement `deleteSchema()`:
  - [ ] Send tombstone record (null value)
  - [ ] Remove from cache
- [ ] Add background polling thread:
  - [ ] Poll topic for updates
  - [ ] Update cache on new records
  - [ ] Handle deletions
- [ ] Add proper error handling and logging
- [ ] Add license header

### 1.5 Update KafkaSchemaRegistry
**File**: `main/shared/src/main/java/org/elasticsoftware/akces/schemas/KafkaSchemaRegistry.java`

- [ ] Add new constructor accepting `KafkaTopicSchemaStorage`
- [ ] Add internal adapter to convert between storage and client interfaces
- [ ] Modify `validate()` methods to use storage if available
- [ ] Modify `registerAndValidate()` to use storage if available
- [ ] Keep existing constructor for backward compatibility
- [ ] Add configuration flag to switch modes
- [ ] Add license header to modifications

---

## Phase 2: Auto-Configuration Updates

### 2.1 Update Client Configuration
**File**: `main/client/src/main/java/org/elasticsoftware/akces/client/AkcesClientAutoConfiguration.java`

- [ ] Add `@Bean` for `KafkaTopicSchemaStorage`:
  - [ ] Add `@ConditionalOnProperty(name = "akces.schemas.storage", havingValue = "kafka")`
  - [ ] Inject required dependencies (Producer, Consumer, KafkaAdmin)
  - [ ] Configure topic name from properties
- [ ] Modify `createSchemaRegistry()`:
  - [ ] Check if storage bean available
  - [ ] Use storage if present, else use SchemaRegistryClient
- [ ] Add properties with defaults:
  - [ ] `akces.schemas.storage=kafka` (default)
  - [ ] `akces.schemas.topic=akces-schemas` (default)
  - [ ] `akces.schemas.cache.ttl=3600` (default)
- [ ] Keep backward compatibility for registry mode

### 2.2 Update Aggregate Service Configuration
**File**: `main/runtime/src/main/java/org/elasticsoftware/akces/AggregateServiceApplication.java`

- [ ] Add `@Bean` for `KafkaTopicSchemaStorage`
- [ ] Modify `schemaRegistry()` bean
- [ ] Add same properties as client
- [ ] Keep backward compatibility

### 2.3 Update Query Model Configuration
**File**: `main/query-support/src/main/java/org/elasticsoftware/akces/query/models/AkcesQueryModelAutoConfiguration.java`

- [ ] Add `@Bean` for `KafkaTopicSchemaStorage`
- [ ] Modify `createSchemaRegistry()` bean
- [ ] Add same properties as client
- [ ] Keep backward compatibility

### 2.4 Update Database Model Configuration
**File**: `main/query-support/src/main/java/org/elasticsoftware/akces/query/database/AkcesDatabaseModelAutoConfiguration.java`

- [ ] Add `@Bean` for `KafkaTopicSchemaStorage`
- [ ] Modify `createSchemaRegistry()` bean
- [ ] Add same properties as client
- [ ] Keep backward compatibility

### 2.5 Create Properties File
**File**: `main/shared/src/main/resources/akces-schemas.properties`

- [ ] Add default properties:
  ```properties
  akces.schemas.storage=kafka
  akces.schemas.topic=akces-schemas
  akces.schemas.cache.ttl=3600
  akces.schemas.replication=3
  ```

---

## Phase 3: Testing

### 3.1 Unit Tests for Storage
**File**: `main/shared/src/test/java/org/elasticsoftware/akces/schemas/storage/KafkaTopicSchemaStorageTest.java`

- [ ] Test `initialize()`:
  - [ ] Topic creation
  - [ ] Topic already exists
  - [ ] Error handling
- [ ] Test `registerSchema()`:
  - [ ] First version registration
  - [ ] Subsequent version registration
  - [ ] Duplicate version handling
  - [ ] Error handling
- [ ] Test `getSchemas()`:
  - [ ] Empty result
  - [ ] Single version
  - [ ] Multiple versions
  - [ ] Cache hit/miss
- [ ] Test `getSchema()`:
  - [ ] Specific version retrieval
  - [ ] Version not found
  - [ ] Cache behavior
- [ ] Test `deleteSchema()`:
  - [ ] Tombstone creation
  - [ ] Cache invalidation
- [ ] Test caching:
  - [ ] Cache population
  - [ ] Cache expiration
  - [ ] Cache invalidation
- [ ] Test concurrent access
- [ ] Test error scenarios

### 3.2 Integration Tests
**File**: `main/shared/src/test/java/org/elasticsoftware/akces/schemas/KafkaSchemaRegistryWithStorageTest.java`

- [ ] Set up test with Kafka Testcontainers
- [ ] Test schema registration flow:
  - [ ] Register schema v1
  - [ ] Validate v1
  - [ ] Register schema v2 (compatible)
  - [ ] Validate backward compatibility
  - [ ] Try registering incompatible v3 (should fail)
- [ ] Test validation flow:
  - [ ] Validate existing schema
  - [ ] Validate missing schema (should fail)
- [ ] Test with external events:
  - [ ] External schema validation
  - [ ] Relaxed validation mode
- [ ] Test force register:
  - [ ] Delete and re-register
  - [ ] Verify warning logged
- [ ] Test error handling:
  - [ ] Kafka unavailable
  - [ ] Topic creation failure
  - [ ] Serialization errors

### 3.3 Backward Compatibility Tests
**File**: `main/shared/src/test/java/org/elasticsoftware/akces/schemas/BackwardCompatibilityTest.java`

- [ ] Test with SchemaRegistryClient mode:
  - [ ] All existing tests pass
  - [ ] Configuration works
- [ ] Test mode switching:
  - [ ] Switch from registry to kafka
  - [ ] Verify same behavior
- [ ] Test dual deployment:
  - [ ] Some services use registry
  - [ ] Some services use kafka
  - [ ] Verify interoperability

### 3.4 Update Existing Tests
- [ ] Update `QueryModelRuntimeTests`:
  - [ ] Make schema registry container optional
  - [ ] Add tests for Kafka mode
  - [ ] Keep existing registry mode tests
- [ ] Update `DatabaseModelRuntimeTests`:
  - [ ] Same as above
- [ ] Update `AkcesClientTests`:
  - [ ] Same as above
- [ ] Run full test suite:
  - [ ] `cd main/shared && mvn test`
  - [ ] `cd main/client && mvn test`
  - [ ] `cd main/runtime && mvn test`
  - [ ] `cd main/query-support && mvn test`
  - [ ] All tests pass ✅

---

## Phase 4: Documentation & Operations

### 4.1 Migration Guide
**File**: `docs/SCHEMA_STORAGE_MIGRATION.md`

- [ ] Document current state (with registry)
- [ ] Document migration steps:
  - [ ] Upgrade to new version
  - [ ] Validate registry mode still works
  - [ ] Switch to kafka mode
  - [ ] Monitor and verify
  - [ ] Remove registry deployment
- [ ] Document rollback procedure
- [ ] Document troubleshooting
- [ ] Add FAQ section

### 4.2 Configuration Guide
**File**: `docs/SCHEMA_STORAGE_CONFIG.md`

- [ ] Document all configuration properties
- [ ] Document Kafka mode configuration
- [ ] Document Registry mode configuration
- [ ] Document topic configuration
- [ ] Document cache configuration
- [ ] Add examples for different scenarios

### 4.3 Update README
**File**: `README.md`

- [ ] Update prerequisites section:
  - [ ] Mark Schema Registry as optional
  - [ ] Document when it's needed (registry mode)
- [ ] Update getting started:
  - [ ] Default configuration uses Kafka
  - [ ] How to use registry mode if needed
- [ ] Update architecture diagram
- [ ] Add note about schema storage options

### 4.4 Update Kubernetes Operator
**Files**: `services/operator/src/main/resources/org/elasticsoftware/akces/operator/.../configmap.yaml`

- [ ] Update `aggregate/configmap.yaml`:
  - [ ] Add `akces.schemas.storage=kafka`
  - [ ] Add `akces.schemas.topic=akces-schemas`
  - [ ] Comment out or remove `akces.schemaregistry.url`
- [ ] Update `command/configmap.yaml`:
  - [ ] Same as above
- [ ] Update `query/configmap.yaml`:
  - [ ] Same as above
- [ ] Update CRD definitions if needed
- [ ] Update operator documentation

### 4.5 Update Test Applications
**Files**: `test-apps/crypto-trading/.../application.properties`

- [ ] Update properties to use Kafka mode
- [ ] Add comments showing registry mode option
- [ ] Test that applications work with new config
- [ ] Update any deployment scripts

### 4.6 API Documentation
**File**: `docs/API.md` (create if doesn't exist)

- [ ] Document `KafkaTopicSchemaStorage` interface
- [ ] Document `SchemaRecord` class
- [ ] Document configuration options
- [ ] Add code examples
- [ ] Add JavaDoc generation

---

## Phase 5: Code Review & Quality

### 5.1 Code Review Checklist
- [ ] All code follows Java 25 standards
- [ ] All code has proper license headers
- [ ] All public APIs have JavaDoc
- [ ] No breaking changes to existing APIs
- [ ] Error handling is comprehensive
- [ ] Logging is appropriate (level and messages)
- [ ] Thread safety is ensured
- [ ] Resource cleanup is proper (AutoCloseable, try-with-resources)

### 5.2 Security Review
- [ ] No secrets in code or config files
- [ ] Kafka ACLs documented
- [ ] Input validation for schema data
- [ ] Prevention of schema injection
- [ ] Audit logging for schema changes

### 5.3 Performance Review
- [ ] Caching is effective
- [ ] No memory leaks
- [ ] No excessive thread creation
- [ ] Kafka producer/consumer configured optimally
- [ ] Benchmark results documented

### 5.4 Linting & Formatting
- [ ] Run formatter on all changed files
- [ ] Run checkstyle if available
- [ ] Fix any warnings
- [ ] Ensure consistent code style

---

## Phase 6: Integration & Deployment

### 6.1 Build & Package
- [ ] Build full project: `mvn clean install`
- [ ] Verify no build errors
- [ ] Verify no test failures
- [ ] Check artifact sizes (shouldn't increase significantly)

### 6.2 Integration Testing
- [ ] Deploy to test environment
- [ ] Run end-to-end tests
- [ ] Test schema registration
- [ ] Test schema validation
- [ ] Test with multiple services
- [ ] Monitor logs for errors/warnings

### 6.3 Performance Testing
- [ ] Measure cold start time
- [ ] Measure steady-state latency
- [ ] Measure cache hit ratio
- [ ] Compare with registry mode
- [ ] Document results

### 6.4 Migration Testing
- [ ] Test migration from registry to kafka:
  - [ ] Export schemas from registry
  - [ ] Import to kafka topic
  - [ ] Verify all schemas present
  - [ ] Switch services to kafka mode
  - [ ] Verify functionality

---

## Phase 7: Release Preparation

### 7.1 Documentation Review
- [ ] All documentation complete
- [ ] All diagrams accurate
- [ ] All examples tested
- [ ] All links valid
- [ ] Spelling/grammar check

### 7.2 Release Notes
**File**: `RELEASE_NOTES.md`

- [ ] Document new feature
- [ ] Document configuration changes
- [ ] Document migration steps
- [ ] Document breaking changes (if any)
- [ ] Document known limitations

### 7.3 Changelog
**File**: `CHANGELOG.md`

- [ ] Add entry for this version
- [ ] List all changes
- [ ] Credit contributors

### 7.4 Version Bump
**Files**: Various `pom.xml` files

- [ ] Update version numbers
- [ ] Update dependency versions
- [ ] Update documentation versions

---

## Post-Implementation

### Monitoring
- [ ] Set up alerts for schema topic issues
- [ ] Monitor cache hit rates
- [ ] Monitor schema registration latency
- [ ] Track error rates

### Feedback Collection
- [ ] Collect user feedback
- [ ] Monitor GitHub issues
- [ ] Track adoption rate
- [ ] Identify pain points

### Continuous Improvement
- [ ] Address feedback
- [ ] Optimize based on metrics
- [ ] Add features if needed
- [ ] Update documentation

---

## Sign-Off

- [ ] Architect approval
- [ ] Code review passed
- [ ] All tests passing
- [ ] Documentation complete
- [ ] Ready for merge

---

## Notes

Use this section to track progress, blockers, and decisions made during implementation:

### Progress Log
- 2025-11-09: Initial plan created
- [Date]: Phase 1 started
- [Date]: Phase 1 completed
- ...

### Blockers
- None currently

### Decisions Made
- Decision 1: [Description and rationale]
- Decision 2: [Description and rationale]

### Open Questions
- Question 1: [Description and status]
- Question 2: [Description and status]
