# ElectionsPlugin

Folia-compatible Minecraft governance plugin with a bundled Discord bot named `ElectionBot`.

This first implementation includes:

- Bundled JDA Discord bot startup from inside the plugin.
- SQLite state stored in `plugins/ElectionsPlugin/elections.db`.
- Configurable Discord forum/channel IDs.
- Auto-created or reused Discord roles:
  - `President`
  - `Cabinet Tier 1`
  - `Cabinet Tier 2`
  - `Cabinet Tier 3`
- Configured LuckPerms group assignment/removal.
- Discord-to-Minecraft verification using `/election verify <code>`.
- Monthly election windows using net-score thumbs-up/thumbs-down voting.
- Candidate registration by posting in the configured elections forum.
- Out-of-window election post deletion with a forum notice.
- Election result announcements and next-window announcements.
- Old candidate thread locking/archiving after results are counted.
- Presidential term limits with `-1` for unlimited.
- Cabinet appointment/removal through Discord slash commands.
- Policy proposal creation for YAML/JSON files.
- Discord GUI policy file browser with file select menus, preview pages, and edit modals.
- Attachment-based policy proposals for files too large for Discord modals.
- Secret-pattern blocking before public diffs are posted.
- YAML/JSON validation before voting.
- Diff-based public proposal posts.
- Approved proposal staging for the next normal restart.
- Backup/revert behavior when staged changes apply on startup.

## Build

```powershell
.\gradlew.bat build
```

The plugin jar is written to:

```text
build/libs/ElectionsPlugin-0.1.0-SNAPSHOT.jar
```

## Install

1. Put the jar into the Folia server's `plugins` folder.
2. Start the server once so `plugins/ElectionsPlugin/config.yml` is created.
3. Stop the server.
4. Fill in:
   - `discord.token`
   - `discord.guildId`
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

## Discord Commands

The bot registers one slash command:

```text
/election verify
/election status
/election cabinet-set user:<user> tier:<1|2|3>
/election cabinet-remove user:<user>
/election propose
/election propose file:<relative path> content:<full proposed file contents>
/election propose-upload file:<relative path> attachment:<replacement file>
```

Use `/election propose` with no options to open the Discord GUI file browser. It shows allowed files, lets the president/cabinet preview the file in chunks, and opens a modal to paste replacement contents. Discord modal text is limited, so use `/election propose-upload` for larger config files.

If the bot starts but is not in the configured guild yet, the console logs an OAuth2 invite URL. The bot token is never sent through Discord; copy it from the Discord Developer Portal into `config.yml`.

## Minecraft Commands

```text
/election verify <code>
/election status
/election reload
```

## Notes

- Voters only need to be in the Discord server; they do not need linked Minecraft accounts.
- Candidates may win with only the Discord role. If they verify later during their term, the LuckPerms group is applied.
- LuckPerms groups are not auto-created. Create them in LuckPerms and configure the names in `config.yml`.
- File proposals are staged and applied on the next normal restart, not instantly.
- Files matching secret regexes are blocked before any public diff is posted.
