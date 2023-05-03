package org.elasticsoftware.akces.control;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.elasticsoftware.akces.aggregate.CommandType;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.commands.Command;

public interface AkcesRegistry {
    CommandType<?> resolveType(@Nonnull Class<? extends Command> commandClass);

    String resolveTopic(@Nonnull Class<? extends Command> commandClass);

    String resolveTopic(@Nonnull CommandType<?> commandType);

    String resolveTopic(@Nonnull DomainEventType<?> externalDomainEventType);

    Integer resolvePartition(@Nonnull String aggregateId);
}
