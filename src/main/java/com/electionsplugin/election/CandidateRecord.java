package com.electionsplugin.election;

public record CandidateRecord(
    long id,
    long electionId,
    String discordId,
    String threadId,
    String messageId,
    int upvotes,
    int downvotes,
    int netScore
) {
}
