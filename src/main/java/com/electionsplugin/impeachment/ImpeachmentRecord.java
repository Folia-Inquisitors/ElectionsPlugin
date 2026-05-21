package com.electionsplugin.impeachment;

public record ImpeachmentRecord(
    long id,
    String proposerDiscordId,
    String threadId,
    String messageId,
    long createdAt,
    long closesAt,
    String status,
    int upvotes,
    int downvotes,
    int netScore
) {
}
