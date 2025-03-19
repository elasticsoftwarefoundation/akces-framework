/*
 * Copyright 2022 - 2025 The Original Authors
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

package org.elasticsoftware.akces.eventcatalog;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

public class EventCatalogProcessorTest {

    @Test
    void testProcessorWithSampleData() {
        // Create sample input files
        JavaFileObject aggregateFile = JavaFileObjects.forSourceString(
                "org.elasticsoftware.akcestest.aggregate.account.Account",
                """
                        package org.elasticsoftware.akcestest.aggregate.account;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.aggregate.Aggregate;
import org.elasticsoftware.akces.annotations.AggregateInfo;
import org.elasticsoftware.akces.annotations.CommandHandler;
import org.elasticsoftware.akces.annotations.EventSourcingHandler;
import org.elasticsoftware.akces.annotations.UpcastingHandler;

import java.util.stream.Stream;

@AggregateInfo(
        value = "Account",
        stateClass = AccountState.class,
        generateGDPRKeyOnCreate = true,
        indexed = true,
        indexName = "Users")
@SuppressWarnings("unused")
public final class Account implements Aggregate<AccountState> {
    @Override
    public String getName() {
        return "Account";
    }

    @Override
    public Class<AccountState> getStateClass() {
        return AccountState.class;
    }

    @CommandHandler(create = true, produces = AccountCreatedEvent.class, errors = {})
    public Stream<AccountCreatedEvent> create(CreateAccountCommand cmd, AccountState isNull) {
        return Stream.of(new AccountCreatedEvent(cmd.userId(), cmd.country(), cmd.firstName(), cmd.lastName(), cmd.email()));
    }

    @UpcastingHandler
    public AccountCreatedEventV2 cast(AccountCreatedEvent event) {
        return new AccountCreatedEventV2(event.userId(), event.country(), event.firstName(), event.lastName(), event.email(), false);
    }

    @EventSourcingHandler(create = true)
    @NotNull
    public AccountState create(@NotNull AccountCreatedEventV2 event, AccountState isNull) {
        return new AccountState(event.userId(), event.country(), event.firstName(), event.lastName(), event.email());
    }
}
                        """
        );

JavaFileObject aggregateStateFile = JavaFileObjects.forSourceString(
                "org.elasticsoftware.akcestest.aggregate.account.AccountState",
                """
package org.elasticsoftware.akcestest.aggregate.account;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.aggregate.AggregateState;
import org.elasticsoftware.akces.annotations.AggregateStateInfo;
import org.elasticsoftware.akces.annotations.PIIData;

@AggregateStateInfo(type = "Account", version = 1)
public record AccountState(@NotNull String userId,
                           @NotNull String country,
                           @NotNull @PIIData String firstName,
                           @NotNull @PIIData String lastName,
                           @NotNull @PIIData String email,
                           Boolean twoFactorEnabled) implements AggregateState {
    // Compact constructor to handle possible null values from deserialization
    public AccountState {
        if (twoFactorEnabled == null) {
            twoFactorEnabled = false;
        }
    }

    // Default constructor with false for twoFactorEnabled
    public AccountState(@NotNull String userId,
                       @NotNull String country,
                       @NotNull String firstName,
                       @NotNull String lastName,
                       @NotNull String email) {
        this(userId, country, firstName, lastName, email, false);
    }

    @Override
    public String getAggregateId() {
        return userId();
    }
}
                        """);

        JavaFileObject commandFile = JavaFileObjects.forSourceString(
                "org.elasticsoftware.akcestest.aggregate.account.CreateAccountCommand",
                """
        package org.elasticsoftware.akcestest.aggregate.account;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.CommandInfo;
import org.elasticsoftware.akces.annotations.PIIData;
import org.elasticsoftware.akces.commands.Command;

@CommandInfo(type = "CreateAccount")
public record CreateAccountCommand(
        @AggregateIdentifier @NotNull String userId,
        @NotNull String country,
        @NotNull @PIIData String firstName,
        @NotNull @PIIData String lastName,
        @NotNull @PIIData String email
) implements Command {
    @Override
    @NotNull
    public String getAggregateId() {
        return userId();
    }
}
        """);

        JavaFileObject eventFile = JavaFileObjects.forSourceString(
                "org.elasticsoftware.akcestest.aggregate.account.AccountCreatedEvent",
                """
        package org.elasticsoftware.akcestest.aggregate.account;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.annotations.PIIData;
import org.elasticsoftware.akces.events.DomainEvent;


@DomainEventInfo(type = "AccountCreated")
public record AccountCreatedEvent(
        @AggregateIdentifier @NotNull String userId,
        @NotNull String country,
        @NotNull @PIIData String firstName,
        @NotNull @PIIData String lastName,
        @NotNull @PIIData String email
) implements DomainEvent {
    @Override
    public String getAggregateId() {
        return userId();
    }
}
        """);

        JavaFileObject eventFileV2 = JavaFileObjects.forSourceString(
                "org.elasticsoftware.akcestest.aggregate.account.AccountCreatedEventV2",
                """
        package org.elasticsoftware.akcestest.aggregate.account;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.annotations.PIIData;
import org.elasticsoftware.akces.events.DomainEvent;


@DomainEventInfo(type = "AccountCreated", version = 2)
public record AccountCreatedEventV2(
        @AggregateIdentifier @NotNull String userId,
        @NotNull String country,
        @NotNull @PIIData String firstName,
        @NotNull @PIIData String lastName,
        @NotNull @PIIData String email,
        @NotNull boolean twoFactorEnabled
) implements DomainEvent {
    @Override
    public String getAggregateId() {
        return userId();
    }
}
        """);

        // Run the annotation processor
        Compilation compilation = javac()
            .withProcessors(new EventCatalogProcessor())
            .compile(aggregateFile,aggregateStateFile, commandFile, eventFile, eventFileV2);

        // Verify successful compilation
        assertThat(compilation).succeeded();


        // Check for expected messages in the processing output - without specific file association
        assertThat(compilation)
                .hadNoteContaining("Found aggregate: Account");

        assertThat(compilation)
                .hadNoteContaining("Found command: CreateAccount");

        assertThat(compilation)
                .hadNoteContaining("Found event: AccountCreated");

        assertThat(compilation)
                .hadNoteContaining("Found command handler");

        assertThat(compilation)
                .hadNoteContaining("Matched command handler");

        assertThat(compilation)
                .hadNoteContaining("Aggregate Account handles commands");

        // check the generated files
        assertThat(compilation).generatedFile(StandardLocation.CLASS_OUTPUT, "eventcatalog/domains/Core/services/AccountAggregate/commands/CreateAccount/index.mdx");
        assertThat(compilation).generatedFile(StandardLocation.CLASS_OUTPUT, "eventcatalog/domains/Core/services/AccountAggregate/commands/CreateAccount/schema.json");


        compilation.generatedFile(StandardLocation.CLASS_OUTPUT, "eventcatalog/domains/Core/services/AccountAggregate/commands/CreateAccount/index.mdx")
            .ifPresent(file -> {
                try {
                    String content = file.getCharContent(true).toString();
                    System.out.println("File content:\n" + content);
                } catch (IOException e) {
                    System.err.println("Error reading file content: " + e.getMessage());
                }
            });

        compilation.generatedFile(StandardLocation.CLASS_OUTPUT, "eventcatalog/domains/Core/services/AccountAggregate/commands/CreateAccount/schema.json")
            .ifPresent(file -> {
                try {
                    String content = file.getCharContent(true).toString();
                    System.out.println("File content:\n" + content);
                } catch (IOException e) {
                    System.err.println("Error reading file content: " + e.getMessage());
                }
            });
    }
}
