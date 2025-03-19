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
    void testProcessorWithSingleAggregate() {
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
        assertThat(compilation).generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/eventcatalog/services/AccountAggregate/commands/CreateAccount/index.mdx");
        assertThat(compilation).generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/eventcatalog/services/AccountAggregate/commands/CreateAccount/schema.json");

        compilation.generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/eventcatalog/services/AccountAggregate/commands/CreateAccount/index.mdx")
            .ifPresent(file -> {
                try {
                    String content = file.getCharContent(true).toString();
                    System.out.println(content);
                } catch (IOException e) {
                    System.err.println("Error reading file content: " + e.getMessage());
                }
            });

        compilation.generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/eventcatalog/services/AccountAggregate/commands/CreateAccount/schema.json")
            .ifPresent(file -> {
                try {
                    String content = file.getCharContent(true).toString();
                    System.out.println(content);
                } catch (IOException e) {
                    System.err.println("Error reading file content: " + e.getMessage());
                }
            });
    }

    @Test
    void testProcessorWithMultipleAggregates() {
        // Create sample input files
        JavaFileObject accountAggregateFile = JavaFileObjects.forSourceString(
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

        JavaFileObject accountAggregateStateFile = JavaFileObjects.forSourceString(
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

        JavaFileObject accountCommandFile = JavaFileObjects.forSourceString(
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

        JavaFileObject accountEventFile = JavaFileObjects.forSourceString(
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

        JavaFileObject accountEventFileV2 = JavaFileObjects.forSourceString(
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

        JavaFileObject orderProcessFile = JavaFileObjects.forSourceString("org.elasticsoftware.akcestest.aggregate.orders.OrderProcess", """
                package org.elasticsoftware.akcestest.aggregate.orders;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.processmanager.AkcesProcess;
import org.elasticsoftware.akcestest.aggregate.wallet.InsufficientFundsErrorEvent;
import org.elasticsoftware.akcestest.aggregate.wallet.InvalidCurrencyErrorEvent;

import java.math.BigDecimal;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = BuyOrderProcess.class, name = "BUY")
})
public sealed interface OrderProcess extends AkcesProcess permits BuyOrderProcess {
    String orderId();

    FxMarket market();

    BigDecimal quantity();

    BigDecimal limitPrice();

    String clientReference();

    DomainEvent handle(InsufficientFundsErrorEvent error);

    DomainEvent handle(InvalidCurrencyErrorEvent error);
}""");

        JavaFileObject buyOrderProcessFile = JavaFileObjects.forSourceString("org.elasticsoftware.akcestest.aggregate.orders.BuyOrderProcess", """
                package org.elasticsoftware.akcestest.aggregate.orders;

import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akcestest.aggregate.wallet.InsufficientFundsErrorEvent;
import org.elasticsoftware.akcestest.aggregate.wallet.InvalidCurrencyErrorEvent;

import java.math.BigDecimal;

public record BuyOrderProcess(String orderId, FxMarket market, BigDecimal quantity, BigDecimal limitPrice,
                              String clientReference) implements OrderProcess {
    @Override
    public String getProcessId() {
        return orderId();
    }

    @Override
    public DomainEvent handle(InsufficientFundsErrorEvent error) {
        return new BuyOrderRejectedEvent(error.walletId(), orderId(), clientReference());
    }

    @Override
    public DomainEvent handle(InvalidCurrencyErrorEvent error) {
        return new BuyOrderRejectedEvent(error.walletId(), orderId(), clientReference());
    }
}""" );

        JavaFileObject orderProcessManager = JavaFileObjects.forSourceString("org.elasticsoftware.akcestest.aggregate.orders.OrderProcessManager", """
                package org.elasticsoftware.akcestest.aggregate.orders;

import org.elasticsoftware.akces.aggregate.Aggregate;
import org.elasticsoftware.akces.annotations.AggregateInfo;
import org.elasticsoftware.akces.annotations.CommandHandler;
import org.elasticsoftware.akces.annotations.EventHandler;
import org.elasticsoftware.akces.annotations.EventSourcingHandler;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akcestest.aggregate.account.AccountCreatedEvent;
import org.elasticsoftware.akcestest.aggregate.wallet.AmountReservedEvent;
import org.elasticsoftware.akcestest.aggregate.wallet.InsufficientFundsErrorEvent;
import org.elasticsoftware.akcestest.aggregate.wallet.InvalidCurrencyErrorEvent;
import org.elasticsoftware.akcestest.aggregate.wallet.ReserveAmountCommand;

import java.util.ArrayList;
import java.util.UUID;
import java.util.stream.Stream;

@AggregateInfo(
        value = "OrderProcessManager",
        stateClass = OrderProcessManagerState.class,
        indexed = true,
        indexName = "Users")
public class OrderProcessManager implements Aggregate<OrderProcessManagerState> {
    @Override
    public String getName() {
        return "OrderProcessManager";
    }

    @Override
    public Class<OrderProcessManagerState> getStateClass() {
        return OrderProcessManagerState.class;
    }

    @EventHandler(create = true, produces = UserOrderProcessesCreatedEvent.class, errors = {})
    public Stream<UserOrderProcessesCreatedEvent> create(AccountCreatedEvent event, OrderProcessManagerState isNull) {
        return Stream.of(new UserOrderProcessesCreatedEvent(event.userId()));
    }

    @EventSourcingHandler(create = true)
    public OrderProcessManagerState create(UserOrderProcessesCreatedEvent event, OrderProcessManagerState isNull) {
        return new OrderProcessManagerState(event.userId());
    }

    @EventSourcingHandler
    public OrderProcessManagerState handle(BuyOrderCreatedEvent event, OrderProcessManagerState state) {
        return new OrderProcessManagerState(state.userId(), new ArrayList<>(state.runningProcesses()) {{
            add(new BuyOrderProcess(
                    event.orderId(),
                    event.market(),
                    event.quantity(),
                    event.limitPrice(),
                    event.clientReference()));
        }});
    }

    @EventSourcingHandler
    public OrderProcessManagerState handle(BuyOrderRejectedEvent event, OrderProcessManagerState state) {
        return new OrderProcessManagerState(state.userId(), new ArrayList<>(state.runningProcesses()) {{
            removeIf(process -> process.orderId().equals(event.orderId()));
        }});
    }

    @EventSourcingHandler
    public OrderProcessManagerState handle(BuyOrderPlacedEvent event, OrderProcessManagerState state) {
        // TODO: we should not remove it but mark it as placed
        return new OrderProcessManagerState(state.userId(), new ArrayList<>(state.runningProcesses()) {{
            removeIf(process -> process.orderId().equals(event.orderId()));
        }});
    }

    /**
     * This is the entry point for the user to place a buy order
     *
     * @param command
     * @param state
     * @return
     */
    @CommandHandler(produces = BuyOrderCreatedEvent.class, errors = {})
    public Stream<BuyOrderCreatedEvent> placeBuyOrder(PlaceBuyOrderCommand command, OrderProcessManagerState state) {
        // we need to reserve the quote currency amount on wallet of the user
        String orderId = UUID.randomUUID().toString();
        // send command to reserve the amount of the quote currency
        getCommandBus().send(new ReserveAmountCommand(
                state.userId(),
                command.market().quoteCurrency(),
                command.quantity().multiply(command.limitPrice()),
                orderId));
        // register the buy order process
        return Stream.of(new BuyOrderCreatedEvent(
                state.userId(),
                orderId,
                command.market(),
                command.quantity(),
                command.limitPrice(),
                command.clientReference()));
    }


    @EventHandler(produces = BuyOrderPlacedEvent.class, errors = {})
    public Stream<DomainEvent> handle(AmountReservedEvent event, OrderProcessManagerState state) {
        // happy path, need to send a command to the market to place the order
        OrderProcess orderProcess = state.getAkcesProcess(event.referenceId());
        if (orderProcess != null) {
            // TODO: send order to ForexMarket
            return Stream.of(new BuyOrderPlacedEvent(state.userId(), orderProcess.orderId(), orderProcess.market(), orderProcess.quantity(), orderProcess.limitPrice()));
        } else {
            // TODO: this cannot happen
            return Stream.empty();
        }
    }

    @EventHandler(produces = BuyOrderRejectedEvent.class, errors = {})
    public Stream<DomainEvent> handle(InsufficientFundsErrorEvent errorEvent, OrderProcessManagerState state) {
        return Stream.of(state.getAkcesProcess(errorEvent.referenceId()).handle(errorEvent));
    }

    @EventHandler(produces = BuyOrderRejectedEvent.class, errors = {})
    public Stream<DomainEvent> handle(InvalidCurrencyErrorEvent errorEvent, OrderProcessManagerState state) {
        return Stream.of(state.getAkcesProcess(errorEvent.referenceId()).handle(errorEvent));
    }
}""");

        JavaFileObject orderProcessManagerState = JavaFileObjects.forSourceString("org.elasticsoftware.akcestest.aggregate.orders.OrderProcessManagerState", """
                package org.elasticsoftware.akcestest.aggregate.orders;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.AggregateStateInfo;
import org.elasticsoftware.akces.processmanager.ProcessManagerState;
import org.elasticsoftware.akces.processmanager.UnknownAkcesProcessException;

import java.util.List;

@AggregateStateInfo(type = "OrderProcessManager", version = 1)
public record OrderProcessManagerState(
        @NotNull @AggregateIdentifier String userId,
        List<BuyOrderProcess> runningProcesses
) implements ProcessManagerState<OrderProcess> {
    public OrderProcessManagerState(@NotNull String userId) {
        this(userId, List.of());
    }

    @Override
    public String getAggregateId() {
        return userId();
    }

    @Override
    public OrderProcess getAkcesProcess(String processId) {
        return runningProcesses.stream().filter(p -> p.orderId().equals(processId)).findFirst()
                .orElseThrow(() -> new UnknownAkcesProcessException("OrderProcessManager", userId(), processId));
    }

    @Override
    public boolean hasAkcesProcess(String processId) {
        return runningProcesses.stream().anyMatch(p -> p.orderId().equals(processId));
    }
}""");

        JavaFileObject fxMarket = JavaFileObjects.forSourceString("org.elasticsoftware.akcestest.aggregate.orders.FxMarket", """
                package org.elasticsoftware.akcestest.aggregate.orders;

public record FxMarket(String id, String baseCurrency, String quoteCurrency) {
}""");

        JavaFileObject buyOrderCreatedEvent = JavaFileObjects.forSourceString("org.elasticsoftware.akcestest.aggregate.orders.BuyOrderCreatedEvent", """
                package org.elasticsoftware.akcestest.aggregate.orders;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.DomainEvent;

import java.math.BigDecimal;

@DomainEventInfo(type = "BuyOrderCreated", version = 1)
public record BuyOrderCreatedEvent(
        @NotNull @AggregateIdentifier String userId,
        @NotNull String orderId,
        @NotNull FxMarket market,
        @NotNull BigDecimal quantity,
        @NotNull BigDecimal limitPrice,
        @NotNull String clientReference
) implements DomainEvent {
    @Override
    public String getAggregateId() {
        return orderId();
    }
}""");

        JavaFileObject buyOrderPlacedEvent = JavaFileObjects.forSourceString("org.elasticsoftware.akcestest.aggregate.orders.BuyOrderPlacedEvent", """
                package org.elasticsoftware.akcestest.aggregate.orders;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.DomainEvent;

import java.math.BigDecimal;

@DomainEventInfo(type = "BuyOrderPlaced", version = 1)
public record BuyOrderPlacedEvent(
        @NotNull @AggregateIdentifier String userId,
        @NotNull String orderId,
        @NotNull FxMarket market,
        @NotNull BigDecimal quantity,
        @NotNull BigDecimal limitPrice
) implements DomainEvent {
    @Override
    public String getAggregateId() {
        return orderId();
    }
}""");

        JavaFileObject buyOrderRejectedEvent = JavaFileObjects.forSourceString("org.elasticsoftware.akcestest.aggregate.orders.BuyOrderRejectedEvent", """
                package org.elasticsoftware.akcestest.aggregate.orders;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.DomainEvent;

@DomainEventInfo(type = "BuyOrderRejected", version = 1)
public record BuyOrderRejectedEvent(
        @NotNull @AggregateIdentifier String userId,
        @NotNull String orderId,
        @NotNull String clientReference
) implements DomainEvent {
    @Override
    public String getAggregateId() {
        return userId();
    }
}""");

        JavaFileObject insufficientFundsErrorEvent = JavaFileObjects.forSourceString("org.elasticsoftware.akcestest.aggregate.wallet.InsufficientFundsErrorEvent", """
                package org.elasticsoftware.akcestest.aggregate.wallet;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.ErrorEvent;

import javax.annotation.Nonnull;
import java.math.BigDecimal;

@DomainEventInfo(type = "InsufficientFundsError")
public record InsufficientFundsErrorEvent(
        @NotNull @AggregateIdentifier String walletId,
        @NotNull String currency,
        @NotNull BigDecimal availableAmount,
        @NotNull BigDecimal requestedAmount,
        @Nullable String referenceId
) implements ErrorEvent {
    @Override
    public @Nonnull String getAggregateId() {
        return walletId();
    }
}""");

        JavaFileObject invalidCurrencyErrorEvent = JavaFileObjects.forSourceString("org.elasticsoftware.akcestest.aggregate.wallet.InvalidCurrencyErrorEvent", """
                package org.elasticsoftware.akcestest.aggregate.wallet;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.ErrorEvent;

import javax.annotation.Nonnull;

@DomainEventInfo(type = "InvalidCurrencyError")
public record InvalidCurrencyErrorEvent(
        @NotNull @AggregateIdentifier String walletId,
        @NotNull String currency,
        String referenceId
) implements ErrorEvent {
    public InvalidCurrencyErrorEvent(@NotNull String walletId, @NotNull String currency) {
        this(walletId, currency, null);
    }

    @Nonnull
    @Override
    public String getAggregateId() {
        return walletId();
    }
}""");

        JavaFileObject placeBuyOrderCommand = JavaFileObjects.forSourceString("org.elasticsoftware.akcestest.aggregate.orders.PlaceBuyOrderCommand", """
                package org.elasticsoftware.akcestest.aggregate.orders;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.CommandInfo;
import org.elasticsoftware.akces.commands.Command;

import java.math.BigDecimal;

@CommandInfo(type = "PlaceBuyOrder", version = 1)
public record PlaceBuyOrderCommand(
        @NotNull @AggregateIdentifier String userId,
        @NotNull FxMarket market,
        @NotNull BigDecimal quantity,
        @NotNull BigDecimal limitPrice,
        @NotNull String clientReference
) implements Command {
    @Override
    @NotNull
    public String getAggregateId() {
        return userId();
    }
}""");

        JavaFileObject userOrderProcessesCreatedEvent = JavaFileObjects.forSourceString("org.elasticsoftware.akcestest.aggregate.orders.UserOrderProcessesCreatedEvent", """
                package org.elasticsoftware.akcestest.aggregate.orders;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.DomainEvent;

@DomainEventInfo(type = "UserOrderProcessesCreated", version = 1)
public record UserOrderProcessesCreatedEvent(@NotNull @AggregateIdentifier String userId) implements DomainEvent {
    @Override
    public String getAggregateId() {
        return userId();
    }
}""");
        JavaFileObject amountReservedEvent = JavaFileObjects.forSourceString("org.elasticsoftware.akcestest.aggregate.wallet.AmountReservedEvent", """
                package org.elasticsoftware.akcestest.aggregate.wallet;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.DomainEvent;

import java.math.BigDecimal;

@DomainEventInfo(type = "AmountReserved", version = 1)
public record AmountReservedEvent(
        @NotNull @AggregateIdentifier String userId,
        @NotNull String currency,
        @NotNull BigDecimal amount,
        @NotNull String referenceId
) implements DomainEvent {
    @Override
    public String getAggregateId() {
        return userId();
    }
}""");

        JavaFileObject reserverAmountCommand = JavaFileObjects.forSourceString("org.elasticsoftware.akcestest.aggregate.wallet.ReserveAmountCommand", """
                package org.elasticsoftware.akcestest.aggregate.wallet;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.CommandInfo;
import org.elasticsoftware.akces.commands.Command;

import java.math.BigDecimal;

@CommandInfo(type = "ReserveAmount", version = 1)
public record ReserveAmountCommand(
        @NotNull @AggregateIdentifier String userId,
        @NotNull String currency,
        @NotNull BigDecimal amount,
        @NotNull String referenceId
) implements Command {
    @Override
    @NotNull
    public String getAggregateId() {
        return userId();
    }
}""");

        // compile the files
        Compilation compilation = javac()
            .withProcessors(new EventCatalogProcessor())
            .compile(accountAggregateFile, accountAggregateStateFile, accountCommandFile, accountEventFile, accountEventFileV2,
                    orderProcessFile, buyOrderProcessFile, orderProcessManager, orderProcessManagerState, fxMarket,
                    buyOrderCreatedEvent, buyOrderPlacedEvent, buyOrderRejectedEvent, insufficientFundsErrorEvent, invalidCurrencyErrorEvent,
                    placeBuyOrderCommand, userOrderProcessesCreatedEvent, amountReservedEvent, reserverAmountCommand);

        // Verify successful compilation
        assertThat(compilation).succeeded();

        // check for files created
        assertThat(compilation).generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/eventcatalog/services/AccountAggregate/commands/CreateAccount/index.mdx");
        assertThat(compilation).generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/eventcatalog/services/AccountAggregate/commands/CreateAccount/schema.json");
        assertThat(compilation).generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/eventcatalog/services/AccountAggregate/events/AccountCreated/index.mdx");
        assertThat(compilation).generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/eventcatalog/services/AccountAggregate/events/AccountCreated/schema.json");
        assertThat(compilation).generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/eventcatalog/services/OrderProcessManagerAggregate/commands/PlaceBuyOrder/index.mdx");
        assertThat(compilation).generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/eventcatalog/services/OrderProcessManagerAggregate/commands/PlaceBuyOrder/schema.json");
        assertThat(compilation).generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/eventcatalog/services/OrderProcessManagerAggregate/events/BuyOrderCreated/index.mdx");
        assertThat(compilation).generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/eventcatalog/services/OrderProcessManagerAggregate/events/BuyOrderCreated/schema.json");
        assertThat(compilation).generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/eventcatalog/services/OrderProcessManagerAggregate/events/BuyOrderPlaced/index.mdx");
        assertThat(compilation).generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/eventcatalog/services/OrderProcessManagerAggregate/events/BuyOrderPlaced/schema.json");
        assertThat(compilation).generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/eventcatalog/services/OrderProcessManagerAggregate/events/BuyOrderRejected/index.mdx");
        assertThat(compilation).generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/eventcatalog/services/OrderProcessManagerAggregate/events/BuyOrderRejected/schema.json");
        assertThat(compilation).generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/eventcatalog/services/OrderProcessManagerAggregate/events/UserOrderProcessesCreated/index.mdx");
        assertThat(compilation).generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/eventcatalog/services/OrderProcessManagerAggregate/events/UserOrderProcessesCreated/schema.json");


    }
}
