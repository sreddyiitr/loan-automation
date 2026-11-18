package com.funding.domain;

/** One expected-vs-actual line in a result's details (AS-10.4). Values are display strings. */
public record Comparison(String name, String expected, String actual, boolean ok) {}
