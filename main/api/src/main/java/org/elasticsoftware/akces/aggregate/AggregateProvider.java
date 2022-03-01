package org.elasticsoftware.akces.aggregate;

import java.util.ServiceLoader;

public class AggregateProvider {
    private static AggregateProvider aggregateProvider;
    private final ServiceLoader<AggregateBuilderFactory> loader;

    private AggregateProvider() {
        loader = ServiceLoader.load(AggregateBuilderFactory.class);
    }

    public static AggregateProvider getInstance() {
        if(aggregateProvider == null) {
            aggregateProvider = new AggregateProvider();
        }
        return aggregateProvider;
    }

    public <S extends AggregateState> AggregateBuilder<S> aggregate(Aggregate<S> aggregate) {
        return loader.findFirst().orElseThrow(() -> new IllegalStateException("No AggregateBuilderFactory implementation found")).create(aggregate);
    }
}
