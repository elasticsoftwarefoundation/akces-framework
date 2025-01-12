package org.elasticsoftware.akces.queries.models;

import org.elasticsoftware.akces.queries.QueryModel;
import org.elasticsoftware.akces.queries.QueryModelState;

import java.util.concurrent.CompletionStage;

public interface QueryModels {
    <S extends QueryModelState> CompletionStage<S> getHydratedState(Class<? extends QueryModel<S>> modelClass, String id);
}
