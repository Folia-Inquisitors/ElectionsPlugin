package com.electionsplugin.election;

import java.time.ZonedDateTime;
import java.util.List;

public record ElectionResult(
    ElectionRecord election,
    CandidateRecord winner,
    boolean tie,
    List<CandidateRecord> candidates,
    ZonedDateTime nextStart,
    ZonedDateTime nextEnd
) {
}
