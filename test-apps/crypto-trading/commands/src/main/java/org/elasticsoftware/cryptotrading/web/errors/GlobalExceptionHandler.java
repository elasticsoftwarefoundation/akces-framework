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

package org.elasticsoftware.cryptotrading.web.errors;

import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.ErrorEvent;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(ErrorEventException.class)
    protected ResponseEntity<ErrorEventResponse> handleErrorEvent(ErrorEventException ex) {
        ErrorEvent errorEvent = ex.getErrorEvent();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorEventResponse(errorEvent.getClass().getAnnotation(DomainEventInfo.class).type()));
    }
}
