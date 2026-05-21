package com.electionsplugin.database;

import java.util.UUID;

public record LinkedAccount(String discordId, UUID minecraftUuid, String minecraftName) {
}
