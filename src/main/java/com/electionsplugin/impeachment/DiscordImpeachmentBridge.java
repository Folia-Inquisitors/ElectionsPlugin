package com.electionsplugin.impeachment;

public interface DiscordImpeachmentBridge {
    void publishImpeachmentResult(ImpeachmentRecord impeachment, boolean passed);

    void clearGovernmentDiscordRolesAfterImpeachment();
}
