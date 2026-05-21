package com.electionsplugin.policy;

public record ProposalRecord(
    long id,
    String proposerDiscordId,
    String relativePath,
    String proposedContent,
    String diff,
    String pollThreadId,
    String pollMessageId,
    long createdAt,
    long closesAt,
    String status,
    int upvotes,
    int downvotes,
    int netScore
) {
}
