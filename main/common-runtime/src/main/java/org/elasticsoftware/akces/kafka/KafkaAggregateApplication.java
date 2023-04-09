package org.elasticsoftware.akces.kafka;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requirements;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.ApplicationConfiguration;
import io.micronaut.runtime.EmbeddedApplication;
import io.micronaut.runtime.Micronaut;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.admin.AdminClient;
import org.elasticsoftware.akces.aggregate.AggregateRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
@Requirements(value = {
        @Requires(property = "kafka.enabled", value = "true"),
        @Requires(bean = KafkaAggregateRuntime.class)
}
)
public class KafkaAggregateApplication implements EmbeddedApplication<KafkaAggregateApplication> {
    private static final Logger LOG = LoggerFactory.getLogger(KafkaAggregateApplication.class);
    private final ApplicationContext applicationContext;

    private final ApplicationConfiguration applicationConfiguration;
    private final AggregateRuntime runtime;
    private final AdminClient adminClient;

    public KafkaAggregateApplication(ApplicationContext applicationContext,
                                     ApplicationConfiguration applicationConfiguration,
                                     AggregateRuntime runtime,
                                     AdminClient adminClient) {
        this.applicationContext = applicationContext;
        this.applicationConfiguration = applicationConfiguration;
        this.runtime = runtime;
        this.adminClient = adminClient;
    }

    @Override
    public ApplicationContext getApplicationContext() {
        return applicationContext;
    }

    @Override
    public ApplicationConfiguration getApplicationConfiguration() {
        return applicationConfiguration;
    }

    @Override
    public boolean isRunning() {
        return applicationContext.isRunning();
    }

    @Override
    public boolean isServer() {
        return true;
    }

    @Override
    public KafkaAggregateApplication start() {
        LOG.info("Starting {} Aggregate", runtime.getName());
        // send service configuration to control topic

        // load service configurations from control topic

        // resolve topics

        // determine cluster size and position

        // determine total partitions (AdminClient)

        // determine local partitions

        // start partitions

        return this;
    }

    @Override
    public KafkaAggregateApplication stop() {
        System.out.println("{} Aggregate stopping");
        // stop partitions

        return this;
    }
}
