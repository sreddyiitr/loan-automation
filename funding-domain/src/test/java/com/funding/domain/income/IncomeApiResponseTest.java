package com.funding.domain.income;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class IncomeApiResponseTest {

    @Test
    void verifiedAndNotFound() {
        assertThat(IncomeApiResponse.classify(200, "{\"status\":\"VERIFIED\",\"totalVerifiedAnnualIncome\":\"100000.00\",\"extra\":1}", null))
                .isEqualTo(new IncomeApiResponse.Ok(IncomeVerification.verified(new java.math.BigDecimal("100000.00"))));
        assertThat(IncomeApiResponse.classify(200, "{\"status\":\"NOT_FOUND\"}", null))
                .isEqualTo(new IncomeApiResponse.Ok(IncomeVerification.notFound()));
    }

    @Test
    void malformedAndSchemaInvalidAreFatal() {
        assertThat(IncomeApiResponse.classify(200, "{\"status\":", null)).isInstanceOf(IncomeApiResponse.Fatal.class);
        assertThat(IncomeApiResponse.classify(200, "{\"status\":\"VERIFIED\"}", null)).isInstanceOf(IncomeApiResponse.Fatal.class);
        assertThat(IncomeApiResponse.classify(200, "{\"status\":\"VERIFIED\",\"totalVerifiedAnnualIncome\":\"1,000.00\"}", null))
                .isInstanceOf(IncomeApiResponse.Fatal.class);
    }

    @Test
    void C_13_rateLimitIsRetryableWithRetryAfter() {
        assertThat(IncomeApiResponse.classify(429, "{}", "2")).isEqualTo(new IncomeApiResponse.Retry("rate limited (429)", Duration.ofSeconds(2)));
        assertThat(((IncomeApiResponse.Retry) IncomeApiResponse.classify(429, "{}", "Wed, 21 Oct 2026 07:28:00 GMT")).retryAfter()).isNull();
    }

    @Test
    void serverErrorsRetryClientErrorsDont() {
        assertThat(IncomeApiResponse.classify(503, "", null)).isInstanceOf(IncomeApiResponse.Retry.class);
        assertThat(IncomeApiResponse.classify(400, "", null)).isInstanceOf(IncomeApiResponse.Fatal.class);
        assertThat(IncomeApiResponse.classify(422, "", null)).isInstanceOf(IncomeApiResponse.Fatal.class);
    }
}
