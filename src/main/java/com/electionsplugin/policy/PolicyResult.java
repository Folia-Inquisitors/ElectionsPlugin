package com.electionsplugin.policy;

public record PolicyResult(boolean success, String message, ProposalRecord proposal) {
    public static PolicyResult success(String message, ProposalRecord proposal) {
        return new PolicyResult(true, message, proposal);
    }

    public static PolicyResult failure(String message) {
        return new PolicyResult(false, message, null);
    }
}
