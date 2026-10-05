package com.parkio.parking.externalsource;

import java.time.Instant;

/**
 * Whether a feed without source timestamps returned exactly the records of its previous run: the same
 * external ids with the same raw record hashes (CL-F22, owner option C). It is for operators only.
 * Readings keep their fetch time, so public freshness and counts do not depend on it.
 *
 * @param unchanged true when the feed repeated its previous run exactly
 * @param previousRunFetchedAt fetch time of the previous run the feed was compared with
 * @param fetchedAt fetch time of this run
 */
public record MunicipalFeedChange(boolean unchanged, Instant previousRunFetchedAt, Instant fetchedAt) {}
