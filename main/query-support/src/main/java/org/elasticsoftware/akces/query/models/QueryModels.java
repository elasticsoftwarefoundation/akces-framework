package org.elasticsoftware.akces.query.models;

import org.elasticsoftware.akces.query.QueryModel;
import org.elasticsoftware.akces.query.QueryModelState;

import java.util.concurrent.CompletionStage;

public interface QueryModels {
    <S extends QueryModelState> CompletionStage<S> getHydratedState(Class<? extends QueryModel<S>> modelClass, String id);
}
