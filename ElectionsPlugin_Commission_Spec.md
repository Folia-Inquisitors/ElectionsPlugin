# ElectionsPlugin Commission Spec

## Overview

Build `ElectionsPlugin`, a Folia-compatible Minecraft plugin with a bundled Discord bot named `ElectionBot`.

The plugin will run the Discord bot from inside the Minecraft plugin, store state in a local SQLite database, manage Discord forum-based elections, assign/remove Discord roles and configured LuckPerms groups, support cabinet appointments, handle impeachment, and allow approved president/cabinet file-change proposals to be staged for the next normal server restart.

The plugin is for a single Folia server.

## Core Architecture

- Platform: Folia Minecraft server.
- Plugin name: `ElectionsPlugin`.
- Main command: `/election`.
- Discord bot display name: `ElectionBot`.
- Database: SQLite file stored inside the plugin folder.
- Discord bot: bundled inside the plugin jar.
- Permissions system: LuckPerms integration.
- Discord interface: forum posts, reactions, slash commands, buttons, select menus, and modals.

Important Folia requirement:

- Discord API work, file parsing, diff generation, and SQLite operations must run asynchronously.
- Minecraft player/entity work must use Folia-safe scheduling.
- Global server/plugin tasks must use Folia-compatible global scheduling.
- The implementation must not rely on legacy Bukkit scheduler assumptions.

## Discord Setup

All relevant Discord channel/forum IDs should be configurable.

Required Discord areas:

- Elections forum
- Impeachment forum
- Policy proposal forum
- Polls forum
- Approved-changes log channel

The bot should check on startup that it has the required Discord permissions:

- View Channels
- Send Messages
- Read Message History
- Add Reactions
- Manage Messages
- Manage Threads
- Manage Roles

If permissions are missing, the plugin should warn clearly in console and disable affected features.

## Discord Roles

Discord roles should be created automatically if missing.

Default role names:

- `President`
- `Cabinet Tier 1`
- `Cabinet Tier 2`
- `Cabinet Tier 3`

Role names must be configurable.

If a configured role already exists, the bot should reuse it instead of creating a duplicate.

## LuckPerms Groups

LuckPerms groups should not be auto-created by default.

The plugin should assign and remove configured LuckPerms groups.

Default group names:

- President: `president`
- Cabinet Tier 1: `cabinet_tier_1`
- Cabinet Tier 2: `cabinet_tier_2`
- Cabinet Tier 3: `cabinet_tier_3`

If a configured LuckPerms group does not exist, the plugin should log a warning.

## Account Verification

Voters do not need linked Minecraft accounts. They only need to be members of the Discord server.

Candidates and cabinet members may verify their Minecraft accounts to receive LuckPerms roles.

Verification flow:

1. Discord user uses a verify command/button.
2. Bot generates a short verification code.
3. Player runs `/election verify <code>` in Minecraft.
4. Plugin links Discord user ID to Minecraft UUID.

If someone wins president without verifying, they still receive the Discord president role.

If they verify later during their term, they should then receive the configured LuckPerms president group.

At the end of the term, both Discord and LuckPerms roles/groups must be removed.

Term limits should track both:

- Discord user ID
- Minecraft UUID, when available

## Monthly Presidential Elections

Election schedule:

- Starts on the 1st day of each month.
- Ends on the 7th day of each month.
- Timezone default: `America/New_York`.
- Timezone must be configurable.
- New president takes office when votes are counted on the 7th.

Candidate registration:

- Any user who creates a post in the configured elections forum during the active election window is an official candidate.
- The bot should automatically add thumbs-up and thumbs-down reactions to candidate posts.
- Voting uses reactions only.

Out-of-window posts:

- If someone posts in the elections forum outside the active election window, the bot should delete the post.
- The bot should mention the user in the elections forum and tell them when the next election starts.

Voting:

- Election winner is determined by net score.
- Net score = thumbs up minus thumbs down.
- Voters may change votes until the election closes.
- Votes after counting are ignored.
- Ties do not pass automatically.

After election close:

- Bot counts votes.
- Bot announces the winner.
- Bot announces the next election start and end dates.
- Bot assigns the Discord president role.
- Plugin assigns the configured LuckPerms president group if the winner has verified.
- Bot locks/archives old election posts so late reactions cannot affect visible results.
- Old president and cabinet Discord roles and LuckPerms groups are removed.

Term limit:

- Default max presidential wins: `2`.
- This means a user can be president at most twice forever.
- Configurable.
- `-1` means unlimited.
- Losing an election does not count against the limit.

## Cabinet System

The president may appoint cabinet members through the Discord bot GUI.

Defaults:

- Maximum cabinet members: `3`.
- Configurable.
- President is separate from cabinet and does not count against this limit.
- Cabinet members can be replaced during the term.
- All cabinet members may be in the same tier if the president chooses.

Cabinet tiers:

- Tier 1
- Tier 2
- Tier 3

Each tier maps to:

- A Discord role.
- A configured LuckPerms group.

Cabinet members may submit policy/file-change proposals.

Proposal limits:

- Cabinet members default to 1 proposal per 7 days.
- Configurable.
- President has unlimited proposals by default.

## Impeachment

Any Discord server member may start an impeachment post in the configured impeachment forum.

Voting:

- Reactions only: thumbs up and thumbs down.
- Default duration: 3 days from original posting.
- Minimum required votes should be configurable.
- Impeachment passes if thumbs up is greater than thumbs down and quorum/minimum vote rules are met.
- Ties fail.

If impeachment passes:

- President is removed.
- President Discord role is removed.
- President LuckPerms group is removed if assigned.
- Cabinet roles/groups are removed.
- A special election is started.

If impeachment fails:

- President remains in office.
- No suspension happens during the impeachment vote.

Special election:

- Default duration: 7 days.
- Configurable.
- Same net-score voting rules as normal elections.

## Policy And File Change Proposals

Policy/file changes are made through Discord GUI only.

Allowed submitters:

- President
- Cabinet members

Users should not directly modify live server files through Discord.

Instead, they propose changes that are publicly voted on before being staged.

File browsing/editing flow:

1. President or cabinet member opens the Discord file browser.
2. Bot shows only allowed files.
3. User selects a file.
4. Bot displays the current file contents in pages/chunks if needed.
5. User pastes edited contents through Discord GUI/modal.
6. Plugin validates file path, file type, file contents, and secret rules.
7. Plugin generates a diff showing old content vs proposed content.
8. Bot posts the proposal and diff in the polls forum.
9. Players vote with thumbs up or thumbs down.
10. If approved, the change is staged for the next normal restart.
11. On restart, plugin backs up the old file and applies the staged file.
12. If applying fails, plugin reverts to the old config and logs the failure.
13. Failed changes require a new proposal and new vote.

Policy voting:

- Default duration: 7 days.
- Minimum votes configurable.
- Proposal passes if thumbs up is greater than thumbs down and minimum vote rules are met.
- Ties fail.
- Voters may change votes until the vote closes.

Diff display:

- Show changed lines with surrounding context.
- If the diff is large, attach or split the full diff.

Approved-changes log:

- The configured log channel should post approved changes.
- Rejected proposals do not need to be posted in the approved-changes log.

## File Safety Rules

Primary editable file types:

- `.yml`
- `.yaml`
- `.json`

Other file types should be blocked by default unless explicitly allowed.

The server owner should configure excluded paths.

Examples of paths that should commonly be excluded:

- Plugin jar files
- `plugins/ElectionsPlugin/`
- LuckPerms data/configs
- Files containing secrets
- Database files
- World folders
- Server operator/ban/whitelist files
- Any path outside the server root

The plugin should protect against:

- Path traversal such as `../`
- Symlink escape
- Editing binary files
- Editing jar files
- Editing SQLite/database files
- Editing files outside allowed roots
- Invalid YAML
- Invalid JSON
- Secret/token/password patterns

Secret detection:

- Use configurable regex rules.
- If a proposed file appears to contain secrets, block the proposal automatically.
- Block before public diff posting so secrets are not leaked into Discord.

## Apply-On-Restart Behavior

Approved file changes should not be applied instantly.

Approved changes are staged and applied on the next normal server restart.

On startup/restart:

1. Plugin loads pending approved changes from SQLite.
2. Plugin validates the target path again.
3. Plugin validates YAML/JSON syntax again.
4. Plugin creates a backup of the current file.
5. Plugin applies the staged replacement.
6. Plugin verifies the written file.
7. Plugin marks the change as applied.
8. If something fails, plugin restores the backup and logs the failure.

Backups:

- Keep backups by default.
- Backup retention can be configurable later.

## SQLite Storage

SQLite should store:

- Discord/Minecraft account links
- Election windows
- Candidate posts
- Vote snapshots/results
- Presidential term history
- Cabinet appointments
- Impeachment posts/results
- Policy proposals
- File diffs or staged file contents
- Proposal approval status
- Restart-apply status
- Audit/history records

The database should be treated as internal plugin state, not user-editable config.

## Suggested Commands

Minecraft:

- `/election verify <code>`
- `/election status`
- `/election reload`

Discord:

- `/election verify`
- `/election cabinet`
- `/election propose`
- `/election status`

Discord GUI should be preferred over requiring users to memorize commands.

## Suggested Config Shape

```yaml
timezone: "America/New_York"

discord:
  token: "PUT_TOKEN_HERE"
  serverId: "PUT_SERVER_ID_HERE"
  forums:
    elections: "PUT_FORUM_ID_HERE"
    impeachment: "PUT_FORUM_ID_HERE"
    policyProposals: "PUT_FORUM_ID_HERE"
    polls: "PUT_FORUM_ID_HERE"
  channels:
    approvedChangesLog: "PUT_CHANNEL_ID_HERE"

roles:
  autoCreateDiscordRoles: true
  president: "President"
  cabinetTier1: "Cabinet Tier 1"
  cabinetTier2: "Cabinet Tier 2"
  cabinetTier3: "Cabinet Tier 3"

luckPerms:
  autoCreateGroups: false
  groups:
    president: "president"
    cabinetTier1: "cabinet_tier_1"
    cabinetTier2: "cabinet_tier_2"
    cabinetTier3: "cabinet_tier_3"

elections:
  monthlyStartDay: 1
  monthlyEndDay: 7
  maxPresidentialWins: 2
  unlimitedWinsValue: -1
  minimumVotes: 0

impeachment:
  durationDays: 3
  minimumVotes: 0

specialElection:
  durationDays: 7
  minimumVotes: 0

cabinet:
  maxMembers: 3
  proposalLimit:
    amount: 1
    periodDays: 7
  presidentProposalLimit: -1

policy:
  durationDays: 7
  minimumVotes: 0
  allowedExtensions:
    - ".yml"
    - ".yaml"
    - ".json"
  excludedPaths:
    - "plugins/ElectionsPlugin/"
    - "plugins/LuckPerms/"
    - "world/"
    - "world_nether/"
    - "world_the_end/"
  secretPatterns:
    - "(?i)token\\s*[:=]"
    - "(?i)password\\s*[:=]"
    - "(?i)secret\\s*[:=]"
    - "(?i)api[_-]?key\\s*[:=]"
```

## MVP Build Order

Recommended implementation phases:

1. Folia plugin base, config, SQLite, startup checks.
2. Bundled Discord bot connection and role creation/reuse.
3. Account verification flow.
4. Monthly election forum tracking and net-score results.
5. LuckPerms president role/group assignment and removal.
6. Cabinet GUI and tiered role/group assignment.
7. Impeachment and special elections.
8. File browser, proposal creation, YAML/JSON validation, and diff posting.
9. Policy voting and staging approved changes.
10. Apply-on-restart backup/revert system.
11. Polish, edge cases, and admin-facing diagnostics.

## Acceptance Criteria

The plugin is complete when:

- It runs on Folia without unsafe scheduler usage.
- The Discord bot starts from inside the plugin.
- SQLite persists election/cabinet/policy state.
- Monthly elections automatically open and close.
- Candidate posts are tracked from the elections forum.
- Winner is chosen by net score.
- Old election posts are locked/archived after counting.
- Out-of-window election posts are deleted with a forum mention explaining the next election date.
- Discord president/cabinet roles are created or reused.
- LuckPerms groups are assigned/removed for verified users.
- Term limits enforce 2 presidential wins by default and support `-1` unlimited.
- Cabinet members can be appointed, tiered, and replaced.
- Impeachment can remove a president and trigger a special election.
- President/cabinet can submit allowed file changes through Discord GUI.
- Invalid YAML/JSON is blocked before voting.
- Secret-detected files are blocked before diff posting.
- Approved changes are staged and applied on next normal restart.
- Failed staged changes revert to backup and require a new vote.
- Approved changes are posted to the configured log channel.
