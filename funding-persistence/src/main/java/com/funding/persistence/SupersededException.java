package com.funding.persistence;

/** The version is no longer latest (AS-9.3): the write was refused inside its transaction. */
public class SupersededException extends RuntimeException {
    public SupersededException(long versionId) {
        super("application version " + versionId + " is no longer the latest version");
    }
}
