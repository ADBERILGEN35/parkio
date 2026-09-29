package com.parkio.parking.application;

public class ConcurrentGenerationException extends RuntimeException {
    public ConcurrentGenerationException(String sourceFamilyPair) {
        super("candidate generation already running for " + sourceFamilyPair);
    }
}
