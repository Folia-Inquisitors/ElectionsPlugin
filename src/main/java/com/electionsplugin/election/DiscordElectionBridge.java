package com.electionsplugin.election;

public interface DiscordElectionBridge {
    void publishElectionOpened(ElectionRecord election);

    void publishElectionResult(ElectionResult result);

    void closeCandidatePosts(ElectionResult result);

    void applyGovernmentDiscordRoles(String presidentDiscordId);
}
