package com.funding.seed.mock;

/** One response in a stub's call sequence. The last one repeats for every further call. */
public record Interaction(int status, String contentType, String body, long delayMillis) {}
