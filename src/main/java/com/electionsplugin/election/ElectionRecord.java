package com.electionsplugin.election;

public record ElectionRecord(
    long id,
    String type,
    String periodKey,
    long startsAt,
    long endsAt,
    String status,
    String winnerDiscordId
) {
}
