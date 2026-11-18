package com.funding.trigger.lambda;

import java.io.IOException;

/** Port to {@code POST /api/v1/funding-workflows}, so the processor is testable without Lambda. */
public interface StartApi {

    record Response(int status, String body) {}

    Response post(String jsonBody) throws IOException, InterruptedException;
}
