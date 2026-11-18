package com.funding.workflows;

/** How the run ended: FUNDED, REJECTED, EXPIRED or SUPERSEDED (AS-9.5). */
public record FundingResult(String result) {}
