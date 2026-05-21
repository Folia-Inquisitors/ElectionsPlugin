package com.electionsplugin.election;

import java.time.ZonedDateTime;

public record CandidateRegistration(boolean accepted, String message, ZonedDateTime nextElectionStart) {
    public static CandidateRegistration accepted(String message) {
        return new CandidateRegistration(true, message, null);
    }

    public static CandidateRegistration rejected(String message, ZonedDateTime nextElectionStart) {
        return new CandidateRegistration(false, message, nextElectionStart);
    }
}
