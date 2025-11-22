# Spring Boot 4.0.0 and Spring Framework 7.0.1 Upgrade Plan

## Objective
Update Spring Boot from 3.5.7 to 4.0.0 and Spring Framework from 6.2.12 to 7.0.1 in the Akces Framework.

## Current State
- Spring Boot: 3.5.7
- Spring Framework: 6.2.12
- Java Version: 25
- Maven Enforcer Plugin: 3.5.0
- Project Status: Currently compiles successfully

## Affected Files
1. `/home/runner/work/akces-framework/akces-framework/pom.xml` (root)
   - Update `spring-boot.version` property
   - Verify Maven Enforcer configuration

2. `/home/runner/work/akces-framework/akces-framework/main/pom.xml`
   - Update `spring.version` property
   - Update aspectj version if needed (Spring 7 requires AspectJ 1.9.23+)

3. `/home/runner/work/akces-framework/akces-framework/services/pom.xml`
   - Inherits Spring Boot BOM, verify compatibility

4. `/home/runner/work/akces-framework/akces-framework/test-apps/pom.xml`
   - Inherits Spring Boot BOM, verify compatibility

## Breaking Changes Expected (Spring Boot 3 → 4)
Based on Spring Boot 4.0.0 migration guide:

1. **Jakarta EE 11**: Spring Boot 4 requires Jakarta EE 11
   - jakarta.annotation-api: 3.0.0+
   - jakarta.validation-api: 3.1.1+ (already at 3.1.1 ✓)
   - jakarta.inject-api: 2.0.1+ (already at 2.0.1 ✓)

2. **Dependency Version Changes**:
   - Mockito: May need update (currently 5.18.0)
   - AspectJ: Requires 1.9.23+ for Spring 7

3. **Deprecated API Removals**: 
   - Some deprecated APIs in Spring Boot 3 may be removed

4. **Maven Enforcer**: 
   - Dependency convergence rules may need adjustment

## Implementation Steps

### Phase 1: Update Version Properties
1. Update `spring-boot.version` to `4.0.0` in root pom.xml
2. Update `spring.version` to `7.0.1` in main/pom.xml
3. Check if `aspectj.version` needs update (currently not defined, check if needed)

### Phase 2: Resolve Maven Enforcer Issues
1. Run `mvn clean compile` to identify dependency convergence issues
2. Add explicit dependency management entries for conflicting versions
3. Verify no duplicate dependency versions

### Phase 3: Compatibility Verification
1. Check Mockito version compatibility
2. Verify Kafka clients compatibility
3. Verify TestContainers compatibility
4. Check Operator SDK compatibility with Spring Boot 4

### Phase 4: Build and Test
1. Full clean build: `mvn clean compile`
2. Run unit tests: `mvn test`
3. Check for deprecation warnings
4. Verify all modules compile

### Phase 5: Integration Testing
1. Build services module
2. Build test-apps module
3. Verify Spring Boot applications start correctly

## Risk Assessment
- **Medium Risk**: Spring Boot 4 is a major version upgrade
- **Mitigations**:
  - Currently using Jakarta EE APIs at compatible versions
  - Java 25 already in use (Spring Boot 4 supports Java 17+)
  - Framework uses standard Spring features, minimal custom configuration

## Rollback Plan
If critical issues arise:
1. Revert version properties to original values
2. Revert any dependency management changes
3. Re-run build to verify

## Dependencies to Monitor
- Kafka clients (4.1.0) - Spring Boot 4 may update this
- TestContainers (1.21.3) - Verify Spring Boot 4 compatibility
- Operator SDK (6.1.2) - May need Spring Boot 4 compatible version
- Fabric8 (7.3.1) - Check Kubernetes client compatibility

## Success Criteria
- [x] All modules compile without errors
- [ ] Spring Boot version updated to 4.0.0
- [ ] Spring Framework version updated to 7.0.1
- [ ] No Maven Enforcer violations
- [ ] All unit tests pass
- [ ] No new deprecation warnings (or documented)
- [ ] Services and test-apps build successfully
