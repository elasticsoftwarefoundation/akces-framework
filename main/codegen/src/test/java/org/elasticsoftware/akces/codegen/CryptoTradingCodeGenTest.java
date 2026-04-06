/*
 * Copyright 2022 - 2026 The Original Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *           http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */

package org.elasticsoftware.akces.codegen;

import org.elasticsoftware.akces.codegen.model.EventModelDefinition;
import org.testng.annotations.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.testng.Assert.*;

/**
 * Tests the Akces code generator against the crypto-trading test app definitions.
 * Verifies that the generated code structurally matches the actual crypto-trading
 * aggregate implementations.
 */
public class CryptoTradingCodeGenTest {

    private final AkcesCodeGenerator generator = new AkcesCodeGenerator("com.example.aggregates");

    // --- Account Aggregate Tests ---

    @Test
    public void testParseAccountDefinition() {
        EventModelDefinition definition = loadDefinition("crypto-trading-account.json");

        assertNotNull(definition);
        assertEquals(definition.packageName(), "org.elasticsoftware.cryptotrading.aggregates");
        assertEquals(definition.slices().size(), 1);
        assertNotNull(definition.aggregateConfig());
        assertTrue(definition.aggregateConfig().containsKey("Account"));
    }

    @Test
    public void testGenerateAccountFiles() {
        EventModelDefinition definition = loadDefinition("crypto-trading-account.json");
        List<GeneratedFile> files = generator.generate(definition);

        assertNotNull(files);
        assertFalse(files.isEmpty());

        Map<String, GeneratedFile> fileMap = files.stream()
                .collect(Collectors.toMap(GeneratedFile::relativePath, f -> f));

        // Verify expected files are generated
        assertTrue(fileMap.containsKey("com/example/aggregates/account/commands/CreateAccountCommand.java"),
                "Should generate CreateAccountCommand.java");
        assertTrue(fileMap.containsKey("com/example/aggregates/account/events/AccountCreatedEvent.java"),
                "Should generate AccountCreatedEvent.java");
        assertTrue(fileMap.containsKey("com/example/aggregates/account/AccountState.java"),
                "Should generate AccountState.java");
        assertTrue(fileMap.containsKey("com/example/aggregates/account/Account.java"),
                "Should generate Account.java");

        assertEquals(files.size(), 4, "Should generate exactly 4 files for Account aggregate");
    }

    @Test
    public void testGeneratedAccountCommand() {
        EventModelDefinition definition = loadDefinition("crypto-trading-account.json");
        List<GeneratedFile> files = generator.generate(definition);

        GeneratedFile commandFile = findFile(files, "com/example/aggregates/account/commands/CreateAccountCommand.java");
        assertNotNull(commandFile);

        String content = commandFile.content();

        // Verify structure matches actual CreateAccountCommand
        assertTrue(content.contains("@CommandInfo(type = \"CreateAccount\""),
                "Should have correct CommandInfo annotation");
        assertTrue(content.contains("public record CreateAccountCommand("),
                "Should be a record");
        assertTrue(content.contains("implements Command"),
                "Should implement Command");
        assertTrue(content.contains("@AggregateIdentifier"),
                "Should have AggregateIdentifier on id field");
        assertTrue(content.contains("@NotNull String userId"),
                "Should have userId field");
        assertTrue(content.contains("@NotNull String country"),
                "Should have country field");
        assertTrue(content.contains("@PIIData @NotNull String firstName"),
                "Should have PIIData annotation on firstName field");
        assertTrue(content.contains("@PIIData @NotNull String lastName"),
                "Should have PIIData annotation on lastName field");
        assertTrue(content.contains("@PIIData @NotNull String email"),
                "Should have PIIData annotation on email field");
        assertTrue(content.contains("return userId()"),
                "Should return userId as aggregate ID");
    }

    @Test
    public void testGeneratedAccountEvent() {
        EventModelDefinition definition = loadDefinition("crypto-trading-account.json");
        List<GeneratedFile> files = generator.generate(definition);

        GeneratedFile eventFile = findFile(files, "com/example/aggregates/account/events/AccountCreatedEvent.java");
        assertNotNull(eventFile);

        String content = eventFile.content();

        // Verify structure matches actual AccountCreatedEvent
        assertTrue(content.contains("@DomainEventInfo(type = \"AccountCreated\""),
                "Should have correct DomainEventInfo annotation");
        assertTrue(content.contains("public record AccountCreatedEvent("),
                "Should be a record");
        assertTrue(content.contains("implements DomainEvent"),
                "Should implement DomainEvent");
        assertTrue(content.contains("@AggregateIdentifier"),
                "Should have AggregateIdentifier on id field");
        assertTrue(content.contains("@NotNull String userId"),
                "Should have userId field");
        assertTrue(content.contains("@PIIData @NotNull String firstName"),
                "Should have PIIData annotation on firstName field");
        assertTrue(content.contains("@PIIData @NotNull String lastName"),
                "Should have PIIData annotation on lastName field");
        assertTrue(content.contains("@PIIData @NotNull String email"),
                "Should have PIIData annotation on email field");
        assertTrue(content.contains("return userId()"),
                "Should return userId as aggregate ID");
    }

    @Test
    public void testGeneratedAccountState() {
        EventModelDefinition definition = loadDefinition("crypto-trading-account.json");
        List<GeneratedFile> files = generator.generate(definition);

        GeneratedFile stateFile = findFile(files, "com/example/aggregates/account/AccountState.java");
        assertNotNull(stateFile);

        String content = stateFile.content();

        // Verify structure matches actual AccountState
        assertTrue(content.contains("@AggregateStateInfo(type = \"Account\", version = 1)"),
                "Should have correct AggregateStateInfo annotation");
        assertTrue(content.contains("public record AccountState("),
                "Should be a record");
        assertTrue(content.contains("implements AggregateState"),
                "Should implement AggregateState");
        assertTrue(content.contains("@PIIData"),
                "Should have PIIData annotation on PII fields");
        assertTrue(content.contains("@NotNull String userId"),
                "Should have userId field");
        assertTrue(content.contains("@NotNull String country"),
                "Should have country field");
        assertTrue(content.contains("return userId()"),
                "Should return userId as aggregate ID");

        // Count PIIData annotations - should be 3 (firstName, lastName, email)
        int piiCount = content.split("@PIIData", -1).length - 1;
        assertEquals(piiCount, 3, "Should have 3 PIIData annotations");
    }

    @Test
    public void testGeneratedAccountAggregate() {
        EventModelDefinition definition = loadDefinition("crypto-trading-account.json");
        List<GeneratedFile> files = generator.generate(definition);

        GeneratedFile aggregateFile = findFile(files, "com/example/aggregates/account/Account.java");
        assertNotNull(aggregateFile);

        String content = aggregateFile.content();

        // Verify structure matches actual Account aggregate
        assertTrue(content.contains("@AggregateInfo("),
                "Should have AggregateInfo annotation");
        assertTrue(content.contains("value = \"Account\""),
                "Should have correct aggregate name");
        assertTrue(content.contains("stateClass = AccountState.class"),
                "Should reference state class");
        assertTrue(content.contains("generateGDPRKeyOnCreate = true"),
                "Should have GDPR key generation enabled");
        assertTrue(content.contains("indexed = true"),
                "Should be indexed");
        assertTrue(content.contains("indexName = \"Users\""),
                "Should have correct index name");
        assertTrue(content.contains("public final class Account implements Aggregate<AccountState>"),
                "Should implement Aggregate with correct state type");
        assertTrue(content.contains("@CommandHandler(create = true"),
                "Should have create command handler");
        assertTrue(content.contains("produces = AccountCreatedEvent.class"),
                "Should produce AccountCreatedEvent");
        assertTrue(content.contains("@EventSourcingHandler(create = true)"),
                "Should have create event sourcing handler");
        assertTrue(content.contains("return \"Account\""),
                "getName() should return aggregate name");
        assertTrue(content.contains("return AccountState.class"),
                "getStateClass() should return state class");
    }

    // --- Wallet Aggregate Tests ---

    @Test
    public void testParseWalletDefinition() {
        EventModelDefinition definition = loadDefinition("crypto-trading-wallet.json");

        assertNotNull(definition);
        assertEquals(definition.slices().size(), 6);
        assertNotNull(definition.aggregateConfig());
        assertTrue(definition.aggregateConfig().containsKey("Wallet"));
    }

    @Test
    public void testGenerateWalletFiles() {
        EventModelDefinition definition = loadDefinition("crypto-trading-wallet.json");
        List<GeneratedFile> files = generator.generate(definition);

        assertNotNull(files);
        assertFalse(files.isEmpty());

        Map<String, GeneratedFile> fileMap = files.stream()
                .collect(Collectors.toMap(GeneratedFile::relativePath, f -> f));

        // Commands (6)
        assertTrue(fileMap.containsKey("com/example/aggregates/wallet/commands/CreateWalletCommand.java"));
        assertTrue(fileMap.containsKey("com/example/aggregates/wallet/commands/CreateBalanceCommand.java"));
        assertTrue(fileMap.containsKey("com/example/aggregates/wallet/commands/CreditWalletCommand.java"));
        assertTrue(fileMap.containsKey("com/example/aggregates/wallet/commands/DebitWalletCommand.java"));
        assertTrue(fileMap.containsKey("com/example/aggregates/wallet/commands/ReserveAmountCommand.java"));
        assertTrue(fileMap.containsKey("com/example/aggregates/wallet/commands/CancelReservationCommand.java"));

        // Success Events (6)
        assertTrue(fileMap.containsKey("com/example/aggregates/wallet/events/WalletCreatedEvent.java"));
        assertTrue(fileMap.containsKey("com/example/aggregates/wallet/events/BalanceCreatedEvent.java"));
        assertTrue(fileMap.containsKey("com/example/aggregates/wallet/events/WalletCreditedEvent.java"));
        assertTrue(fileMap.containsKey("com/example/aggregates/wallet/events/WalletDebitedEvent.java"));
        assertTrue(fileMap.containsKey("com/example/aggregates/wallet/events/AmountReservedEvent.java"));
        assertTrue(fileMap.containsKey("com/example/aggregates/wallet/events/ReservationCancelledEvent.java"));

        // Error Events (5)
        assertTrue(fileMap.containsKey("com/example/aggregates/wallet/events/BalanceAlreadyExistsErrorEvent.java"));
        assertTrue(fileMap.containsKey("com/example/aggregates/wallet/events/InvalidCryptoCurrencyErrorEvent.java"));
        assertTrue(fileMap.containsKey("com/example/aggregates/wallet/events/InvalidAmountErrorEvent.java"));
        assertTrue(fileMap.containsKey("com/example/aggregates/wallet/events/InsufficientFundsErrorEvent.java"));
        assertTrue(fileMap.containsKey("com/example/aggregates/wallet/events/ReservationNotFoundErrorEvent.java"));

        // State + Aggregate class
        assertTrue(fileMap.containsKey("com/example/aggregates/wallet/WalletState.java"));
        assertTrue(fileMap.containsKey("com/example/aggregates/wallet/Wallet.java"));
    }

    @Test
    public void testGeneratedWalletCreateCommand() {
        EventModelDefinition definition = loadDefinition("crypto-trading-wallet.json");
        List<GeneratedFile> files = generator.generate(definition);

        GeneratedFile file = findFile(files, "com/example/aggregates/wallet/commands/CreateWalletCommand.java");
        assertNotNull(file);

        String content = file.content();
        assertTrue(content.contains("@CommandInfo(type = \"CreateWallet\""));
        assertTrue(content.contains("public record CreateWalletCommand("));
        assertTrue(content.contains("implements Command"));
        assertTrue(content.contains("@AggregateIdentifier"));
        assertTrue(content.contains("@NotNull String id"));
        assertTrue(content.contains("@NotNull String currency"));
    }

    @Test
    public void testGeneratedWalletCreditCommand() {
        EventModelDefinition definition = loadDefinition("crypto-trading-wallet.json");
        List<GeneratedFile> files = generator.generate(definition);

        GeneratedFile file = findFile(files, "com/example/aggregates/wallet/commands/CreditWalletCommand.java");
        assertNotNull(file);

        String content = file.content();
        assertTrue(content.contains("@CommandInfo(type = \"CreditWallet\""));
        assertTrue(content.contains("BigDecimal amount"));
        assertTrue(content.contains("import java.math.BigDecimal;"));
    }

    @Test
    public void testGeneratedWalletCreditedEvent() {
        EventModelDefinition definition = loadDefinition("crypto-trading-wallet.json");
        List<GeneratedFile> files = generator.generate(definition);

        GeneratedFile file = findFile(files, "com/example/aggregates/wallet/events/WalletCreditedEvent.java");
        assertNotNull(file);

        String content = file.content();
        assertTrue(content.contains("@DomainEventInfo(type = \"WalletCredited\""));
        assertTrue(content.contains("public record WalletCreditedEvent("));
        assertTrue(content.contains("implements DomainEvent"));
        assertTrue(content.contains("BigDecimal amount"));
        assertTrue(content.contains("BigDecimal balance"));
    }

    @Test
    public void testGeneratedInsufficientFundsErrorEvent() {
        EventModelDefinition definition = loadDefinition("crypto-trading-wallet.json");
        List<GeneratedFile> files = generator.generate(definition);

        GeneratedFile file = findFile(files, "com/example/aggregates/wallet/events/InsufficientFundsErrorEvent.java");
        assertNotNull(file);

        String content = file.content();
        assertTrue(content.contains("@DomainEventInfo(type = \"InsufficientFundsError\""));
        assertTrue(content.contains("public record InsufficientFundsErrorEvent("));
        assertTrue(content.contains("implements ErrorEvent"),
                "Error events should implement ErrorEvent");
        assertTrue(content.contains("@Nullable String referenceId"),
                "Optional fields should use @Nullable");
        assertTrue(content.contains("BigDecimal availableAmount"));
        assertTrue(content.contains("BigDecimal requestedAmount"));
    }

    @Test
    public void testGeneratedWalletAggregate() {
        EventModelDefinition definition = loadDefinition("crypto-trading-wallet.json");
        List<GeneratedFile> files = generator.generate(definition);

        GeneratedFile file = findFile(files, "com/example/aggregates/wallet/Wallet.java");
        assertNotNull(file);

        String content = file.content();

        // Verify aggregate structure
        assertTrue(content.contains("@AggregateInfo("));
        assertTrue(content.contains("value = \"Wallet\""));
        assertTrue(content.contains("stateClass = WalletState.class"));
        assertTrue(content.contains("indexed = true"));
        assertTrue(content.contains("indexName = \"Users\""));
        assertTrue(content.contains("public final class Wallet implements Aggregate<WalletState>"));

        // Verify command handlers exist
        assertTrue(content.contains("@CommandHandler(create = true"));
        assertTrue(content.contains("CreateWalletCommand cmd"));
        assertTrue(content.contains("CreateBalanceCommand cmd"));
        assertTrue(content.contains("CreditWalletCommand cmd"));
        assertTrue(content.contains("DebitWalletCommand cmd"));
        assertTrue(content.contains("ReserveAmountCommand cmd"));
        assertTrue(content.contains("CancelReservationCommand cmd"));

        // Verify event sourcing handlers exist
        assertTrue(content.contains("@EventSourcingHandler(create = true)"));
        assertTrue(content.contains("WalletCreatedEvent event"));

        // Verify error events are referenced
        assertTrue(content.contains("BalanceAlreadyExistsErrorEvent.class"));
        assertTrue(content.contains("InsufficientFundsErrorEvent.class"));
        assertTrue(content.contains("InvalidCryptoCurrencyErrorEvent.class"));
        assertTrue(content.contains("InvalidAmountErrorEvent.class"));
    }

    @Test
    public void testGeneratedWalletState() {
        EventModelDefinition definition = loadDefinition("crypto-trading-wallet.json");
        List<GeneratedFile> files = generator.generate(definition);

        GeneratedFile file = findFile(files, "com/example/aggregates/wallet/WalletState.java");
        assertNotNull(file);

        String content = file.content();
        assertTrue(content.contains("@AggregateStateInfo(type = \"Wallet\", version = 1)"));
        assertTrue(content.contains("public record WalletState("));
        assertTrue(content.contains("implements AggregateState"));
        assertTrue(content.contains("@AggregateIdentifier"));
        assertTrue(content.contains("return id()"));
    }

    @Test
    public void testGeneratedReserveAmountCommand() {
        EventModelDefinition definition = loadDefinition("crypto-trading-wallet.json");
        List<GeneratedFile> files = generator.generate(definition);

        GeneratedFile file = findFile(files, "com/example/aggregates/wallet/commands/ReserveAmountCommand.java");
        assertNotNull(file);

        String content = file.content();
        assertTrue(content.contains("@CommandInfo(type = \"ReserveAmount\""));
        assertTrue(content.contains("@AggregateIdentifier"));
        assertTrue(content.contains("@NotNull String userId"));
        assertTrue(content.contains("@NotNull String currency"));
        assertTrue(content.contains("@NotNull BigDecimal amount"));
        assertTrue(content.contains("@NotNull String referenceId"));
        assertTrue(content.contains("return userId()"),
                "Should use userId as aggregate ID");
    }

    @Test
    public void testGeneratedAmountReservedEvent() {
        EventModelDefinition definition = loadDefinition("crypto-trading-wallet.json");
        List<GeneratedFile> files = generator.generate(definition);

        GeneratedFile file = findFile(files, "com/example/aggregates/wallet/events/AmountReservedEvent.java");
        assertNotNull(file);

        String content = file.content();
        assertTrue(content.contains("@DomainEventInfo(type = \"AmountReserved\""));
        assertTrue(content.contains("public record AmountReservedEvent("));
        assertTrue(content.contains("@AggregateIdentifier"));
        assertTrue(content.contains("@NotNull String userId"));
        assertTrue(content.contains("@NotNull BigDecimal amount"));
        assertTrue(content.contains("@NotNull String referenceId"));
    }

    // --- File Output Test ---

    @Test
    public void testGenerateToDirectory() throws Exception {
        EventModelDefinition definition = loadDefinition("crypto-trading-account.json");
        java.nio.file.Path outputDir = java.nio.file.Files.createTempDirectory("akces-codegen-test");
        try {
            List<GeneratedFile> files = generator.generateToDirectory(definition, outputDir);
            assertEquals(files.size(), 4);

            // Verify files exist on disk
            for (GeneratedFile file : files) {
                java.nio.file.Path filePath = outputDir.resolve(file.relativePath());
                assertTrue(java.nio.file.Files.exists(filePath),
                        "File should exist: " + file.relativePath());
                String diskContent = java.nio.file.Files.readString(filePath);
                assertEquals(diskContent, file.content(),
                        "Disk content should match generated content");
            }
        } finally {
            // Clean up
            try (var walk = java.nio.file.Files.walk(outputDir)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .map(java.nio.file.Path::toFile)
                        .forEach(java.io.File::delete);
            }
        }
    }

    // --- Helper Methods ---

    private EventModelDefinition loadDefinition(String resourceName) {
        InputStream is = getClass().getClassLoader().getResourceAsStream(resourceName);
        assertNotNull(is, "Test resource not found: " + resourceName);
        return generator.parse(is);
    }

    private GeneratedFile findFile(List<GeneratedFile> files, String relativePath) {
        return files.stream()
                .filter(f -> f.relativePath().equals(relativePath))
                .findFirst()
                .orElse(null);
    }
}
