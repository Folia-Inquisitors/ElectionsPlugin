# Election Plugin

Election plugin with a bundled Discord bot for running server elections, voting, role assignment, and policy proposals through Discord.

The plugin is designed to allow players to vote in monthly elections, support or oppose proposals, and participate in server government while the plugin handles tracking, validation, roles, and approved changes.

## What It Does

Election Plugin supports:

- Discord and Minecraft account verification
- Monthly election tracking
- 👍 / 👎 voting with net-score results
- President role assignment
- LuckPerms group assignment for verified winners
- Cabinet tier assignment and removal
- Impeachment posts and special elections
- Policy proposal voting
- YAML and JSON validation for proposed changes
- Secret/token blocking before changes are accepted
- Staged approved changes applied on normal restart
- Backup and revert behavior
- Configurable Discord forums, roles, LuckPerms groups, limits, and file paths

## Discord Bot

This plugin includes a bundled Discord bot. You create the bot in the Discord Developer Portal, add its token to the plugin config, and invite it to your Discord server.

The bot can manage election posts, voting, verification, policy proposals, impeachment posts, and server-government roles.

## Setup Overview

1. Put the jar into the Folia server's `plugins` folder.
2. Start the server once so `plugins/ElectionsPlugin/config.yml` is created.
3. Stop the server.
4. Fill in:
   - `discord.token`
   - `discord.serverId`
   - Discord forum IDs
   - approved-changes log channel ID
   - LuckPerms group names
   - excluded file paths
5. Make sure the Discord bot has these permissions:
   - View Channels
   - Send Messages
   - Send Messages in Threads
   - Read Message History
   - Add Reactions
   - Manage Messages
   - Manage Threads
   - Manage Roles
6. Start the server again.

## Building Instructions

./gradlew build

## Official Discord

https://discord.gg/aT9z7q7hX8

## Folia Inquisitors

<img src="https://github.com/Folia-Inquisitors.png" width=80 alt="Folia-Inquisitors">

## Commands

```text
/election verify <code>
/election status
/election reload
```

## Notes

- Voters only need to be in the Discord server; they do not need linked Minecraft accounts.
- Candidates may win with only the Discord role. If they verify later during their term, the LuckPerms group is applied.
- LuckPerms groups are not auto-created. Create them in LuckPerms and configure the names in `config.yml`.
- LuckPerms itself must be installed on the server for Minecraft permission groups to sync. If LuckPerms is absent, the plugin still runs, but only Discord roles are assigned.
- File proposals are staged and applied on the next normal restart, not instantly.
- Files matching secret regexes are blocked before any public diff is posted.
