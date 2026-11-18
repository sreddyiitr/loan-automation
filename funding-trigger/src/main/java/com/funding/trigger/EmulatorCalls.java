package com.funding.trigger;

import java.util.function.Supplier;
import software.amazon.awssdk.core.exception.SdkServiceException;

/** Bootstrap calls fail fast, naming service and operation, on HTTP 501/503 (homehub V-17). */
public final class EmulatorCalls {
    private EmulatorCalls() {}

    public static <T> T call(String service, String operation, Supplier<T> body) {
        try {
            return body.get();
        } catch (SdkServiceException e) {
            if (e.statusCode() == 501 || e.statusCode() == 503) {
                throw new IllegalStateException("emulator returned HTTP " + e.statusCode() + " for " + service + ":" + operation
                        + ": operation not implemented in the emulator. Stop and propose a workaround.", e);
            }
            throw e;
        }
    }
}
