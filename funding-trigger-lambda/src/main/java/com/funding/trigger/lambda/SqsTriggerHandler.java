package com.funding.trigger.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import java.time.Duration;
import java.util.Objects;

/**
 * Lambda {@code funding-trigger} (java21). Configuration from env: {@code FUNDING_API_BASE_URL},
 * {@code FUNDING_TRIGGER_API_KEY}, {@code HTTP_TIMEOUT_MS} (default 5000). The Lambda timeout (30 s)
 * stays below the queue's 60 s visibility timeout. Locally the same handler is called by
 * {@code LocalLambdaRunner} (the emulator can't run java21 Lambdas; V-2, V-4).
 */
public final class SqsTriggerHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

    private final TriggerProcessor processor;

    /** Used by the Lambda runtime. */
    public SqsTriggerHandler() {
        this(env("FUNDING_API_BASE_URL"), env("FUNDING_TRIGGER_API_KEY"),
                Duration.ofMillis(Long.parseLong(Objects.requireNonNullElse(System.getenv("HTTP_TIMEOUT_MS"), "5000"))));
    }

    public SqsTriggerHandler(String apiBaseUrl, String apiKey, Duration httpTimeout) {
        this.processor = new TriggerProcessor(new HttpStartApi(apiBaseUrl, apiKey, httpTimeout), httpTimeout);
    }

    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        return processor.process(event.getRecords(), context::getRemainingTimeInMillis);
    }

    private static String env(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(name + " is not set");
        }
        return v;
    }
}
