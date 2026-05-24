package com.electionsplugin.discord;

import com.electionsplugin.ElectionsPlugin;
import com.electionsplugin.cabinet.CabinetService;
import com.electionsplugin.config.PluginConfig;
import com.electionsplugin.database.Database;
import com.electionsplugin.election.CandidateRegistration;
import com.electionsplugin.election.DiscordElectionBridge;
import com.electionsplugin.election.ElectionRecord;
import com.electionsplugin.election.ElectionResult;
import com.electionsplugin.election.ElectionService;
import com.electionsplugin.impeachment.DiscordImpeachmentBridge;
import com.electionsplugin.impeachment.ImpeachmentRecord;
import com.electionsplugin.impeachment.ImpeachmentService;
import com.electionsplugin.policy.DiscordPolicyBridge;
import com.electionsplugin.policy.PolicyResult;
import com.electionsplugin.policy.PolicyService;
import com.electionsplugin.policy.ProposalRecord;
import com.electionsplugin.role.RoleSyncService;
import com.electionsplugin.verification.VerificationService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionRemoveEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.jetbrains.annotations.NotNull;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Logger;

public final class DiscordBotService extends ListenerAdapter implements DiscordElectionBridge, DiscordPolicyBridge, DiscordImpeachmentBridge {
    private static final Emoji THUMBS_UP = Emoji.fromUnicode("U+1F44D");
    private static final Emoji THUMBS_DOWN = Emoji.fromUnicode("U+1F44E");
    private static final String POLICY_SELECT_FILE = "policy:file-select";
    private static final String POLICY_FILES_PREV = "policy:files-prev";
    private static final String POLICY_FILES_NEXT = "policy:files-next";
    private static final String POLICY_PREVIEW_PREV = "policy:preview-prev";
    private static final String POLICY_PREVIEW_NEXT = "policy:preview-next";
    private static final String POLICY_EDIT = "policy:edit";
    private static final String POLICY_BACK = "policy:back";
    private static final String POLICY_CANCEL = "policy:cancel";
    private static final String POLICY_MODAL = "policy:modal";
    private static final int FILES_PER_PAGE = 25;
    private static final int MAX_BROWSER_FILES = 1000;
    private static final int PREVIEW_CHARS = 1400;
    private static final int MODAL_VALUE_LIMIT = 4000;

    private final ElectionsPlugin plugin;
    private PluginConfig config;
    private final VerificationService verificationService;
    private final ElectionService electionService;
    private final CabinetService cabinetService;
    private final PolicyService policyService;
    private final ImpeachmentService impeachmentService;
    private final RoleSyncService roleSyncService;
    private final ScheduledExecutorService worker;
    private final Logger logger;
    private JDA jda;
    private Guild guild;
    private volatile boolean ready;
    private final Map<String, PolicyBrowserSession> policySessions = new ConcurrentHashMap<>();

    public DiscordBotService(
        ElectionsPlugin plugin,
        PluginConfig config,
        Database database,
        VerificationService verificationService,
        ElectionService electionService,
        CabinetService cabinetService,
        PolicyService policyService,
        ImpeachmentService impeachmentService,
        RoleSyncService roleSyncService,
        ScheduledExecutorService worker,
        Logger logger
    ) {
        this.plugin = plugin;
        this.config = config;
        this.verificationService = verificationService;
        this.electionService = electionService;
        this.cabinetService = cabinetService;
        this.policyService = policyService;
        this.impeachmentService = impeachmentService;
        this.roleSyncService = roleSyncService;
        this.worker = worker;
        this.logger = logger;
    }

    public void start() {
        try {
            this.jda = JDABuilder.createDefault(config.discordToken())
                .enableIntents(
                    GatewayIntent.GUILD_MESSAGES,
                    GatewayIntent.GUILD_MESSAGE_REACTIONS,
                    GatewayIntent.GUILD_MEMBERS,
                    GatewayIntent.MESSAGE_CONTENT
                )
                .setActivity(Activity.playing("elections"))
                .addEventListeners(this)
                .build();
        } catch (Exception exception) {
            logger.severe("Failed to start ElectionBot: " + exception.getMessage());
        }
    }

    public void shutdown() {
        ready = false;
        if (jda != null) {
            jda.shutdownNow();
        }
    }

    public void reloadConfig(PluginConfig config) {
        this.config = config;
    }

    public boolean isReady() {
        return ready && guild != null;
    }

    @Override
    public void onReady(@NotNull ReadyEvent event) {
        this.guild = event.getJDA().getGuildById(config.serverId());
        if (guild == null) {
            logger.severe("Discord serverId is not configured correctly. ElectionBot is connected but cannot manage the server.");
            logger.info("Invite ElectionBot with: " + inviteUrl(event.getJDA().getSelfUser().getId()));
            return;
        }
        ensureDiscordRoles();
        checkStartupPermissions();
        registerSlashCommands();
        ready = true;
        logger.info("ElectionBot is ready in guild " + guild.getName() + ".");
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (!isReady() || event.getAuthor().isBot() || !(event.getChannel() instanceof ThreadChannel thread)) {
            return;
        }
        if (thread.getParentChannel() == null) {
            return;
        }
        if (!event.getMessageId().equals(thread.getId())) {
            return;
        }

        long parentId = thread.getParentChannel().getIdLong();
        if (parentId == config.electionsForumId()) {
            worker.execute(() -> {
                CandidateRegistration registration = electionService.registerCandidate(
                    event.getAuthor().getId(),
                    thread.getId(),
                    event.getMessageId()
                );
                if (registration.accepted()) {
                    event.getMessage().addReaction(THUMBS_UP).queue();
                    event.getMessage().addReaction(THUMBS_DOWN).queue();
                } else {
                    notifyElectionPostRejected(event.getAuthor().getAsMention(), registration.message());
                    thread.delete().queue(null, failure -> logger.warning("Failed to delete out-of-window election post: " + failure.getMessage()));
                }
            });
        } else if (parentId == config.impeachmentForumId()) {
            worker.execute(() -> {
                impeachmentService.registerImpeachment(event.getAuthor().getId(), thread.getId(), event.getMessageId());
                event.getMessage().addReaction(THUMBS_UP).queue();
                event.getMessage().addReaction(THUMBS_DOWN).queue();
            });
        }
    }

    @Override
    public void onMessageReactionAdd(@NotNull MessageReactionAddEvent event) {
        if (!isReady() || event.getUserId().equals(jda.getSelfUser().getId())) {
            return;
        }
        int value = reactionValue(event.getEmoji().getName());
        if (value == 0) {
            return;
        }
        worker.execute(() -> {
            electionService.recordCandidateVote(event.getMessageId(), event.getUserId(), value, false);
            impeachmentService.recordVote(event.getMessageId(), event.getUserId(), value, false);
            policyService.recordProposalVote(event.getMessageId(), event.getUserId(), value, false);
        });
    }

    @Override
    public void onMessageReactionRemove(@NotNull MessageReactionRemoveEvent event) {
        if (!isReady() || event.getUserId().equals(jda.getSelfUser().getId())) {
            return;
        }
        int value = reactionValue(event.getEmoji().getName());
        if (value == 0) {
            return;
        }
        worker.execute(() -> {
            electionService.recordCandidateVote(event.getMessageId(), event.getUserId(), value, true);
            impeachmentService.recordVote(event.getMessageId(), event.getUserId(), value, true);
            policyService.recordProposalVote(event.getMessageId(), event.getUserId(), value, true);
        });
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!"election".equals(event.getName())) {
            return;
        }
        String subcommand = event.getSubcommandName();
        if (subcommand == null) {
            event.reply("Choose an election subcommand.").setEphemeral(true).queue();
            return;
        }
        switch (subcommand) {
            case "verify" -> handleVerify(event);
            case "status" -> event.reply(electionService.statusLine()).setEphemeral(true).queue();
            case "cabinet-set" -> handleCabinetSet(event);
            case "cabinet-remove" -> handleCabinetRemove(event);
            case "propose" -> handlePolicyProposal(event);
            case "propose-upload" -> handlePolicyUploadProposal(event);
            case "smoke-test" -> handleSmokeTest(event);
            default -> event.reply("Unknown election subcommand.").setEphemeral(true).queue();
        }
    }

    @Override
    public void onStringSelectInteraction(@NotNull StringSelectInteractionEvent event) {
        if (!POLICY_SELECT_FILE.equals(event.getComponentId())) {
            return;
        }
        worker.execute(() -> {
            PolicyBrowserSession session = policySessions.computeIfAbsent(event.getUser().getId(), ignored -> new PolicyBrowserSession());
            List<PolicyService.FileEntry> files = policyService.listEditableFiles(MAX_BROWSER_FILES);
            int selectedIndex = parseInt(event.getValues().isEmpty() ? "-1" : event.getValues().getFirst(), -1);
            if (selectedIndex < 0 || selectedIndex >= files.size()) {
                event.reply("That file selection expired. Run `/election propose` again.").setEphemeral(true).queue();
                return;
            }
            session.selectedPath = files.get(selectedIndex).relativePath();
            session.previewPage = 0;
            replyPreview(event, session);
        });
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith("policy:")) {
            return;
        }
        worker.execute(() -> {
            PolicyBrowserSession session = policySessions.computeIfAbsent(event.getUser().getId(), ignored -> new PolicyBrowserSession());
            switch (id) {
                case POLICY_FILES_PREV -> {
                    session.filePage = Math.max(0, session.filePage - 1);
                    replyFileBrowser(event, session);
                }
                case POLICY_FILES_NEXT -> {
                    session.filePage++;
                    replyFileBrowser(event, session);
                }
                case POLICY_PREVIEW_PREV -> {
                    session.previewPage = Math.max(0, session.previewPage - 1);
                    replyPreview(event, session);
                }
                case POLICY_PREVIEW_NEXT -> {
                    session.previewPage++;
                    replyPreview(event, session);
                }
                case POLICY_BACK -> replyFileBrowser(event, session);
                case POLICY_CANCEL -> {
                    policySessions.remove(event.getUser().getId());
                    event.reply("Policy file browser closed.").setEphemeral(true).queue();
                }
                case POLICY_EDIT -> replyEditModal(event, session);
                default -> event.reply("Unknown policy action.").setEphemeral(true).queue();
            }
        });
    }

    @Override
    public void onModalInteraction(@NotNull ModalInteractionEvent event) {
        if (!POLICY_MODAL.equals(event.getModalId())) {
            return;
        }
        PolicyBrowserSession session = policySessions.get(event.getUser().getId());
        if (session == null || session.selectedPath == null) {
            event.reply("Your policy editor session expired. Run `/election propose` again.").setEphemeral(true).queue();
            return;
        }
        String proposedContent = event.getValues().stream()
            .filter(value -> "content".equals(value.getCustomId()))
            .findFirst()
            .map(value -> value.getAsString())
            .orElse("");
        event.deferReply(true).queue();
        worker.execute(() -> createAndPostProposal(event.getUser().getId(), session.selectedPath, proposedContent, response ->
            event.getHook().sendMessage(response).setEphemeral(true).queue()
        ));
    }

    @Override
    public void publishElectionOpened(ElectionRecord election) {
        if (!isReady()) {
            return;
        }
        ForumChannel forum = guild.getForumChannelById(config.electionsForumId());
        if (forum == null) {
            logger.warning("Cannot publish election opening because the elections forum is not configured.");
            return;
        }
        String content = "The presidential election is now open." +
            "\nCreate a post in this forum to run for president." +
            "\nVoting uses thumbs up and thumbs down reactions." +
            "\nElection closes: " + format(Instant.ofEpochMilli(election.endsAt()).atZone(config.zoneId()));
        forum.createForumPost("Election Open - " + election.periodKey(), MessageCreateData.fromContent(content))
            .queue(null, failure -> logger.warning("Failed to publish election opening: " + failure.getMessage()));
    }

    @Override
    public void publishElectionResult(ElectionResult result) {
        if (!isReady()) {
            return;
        }
        ForumChannel forum = guild.getForumChannelById(config.electionsForumId());
        if (forum == null) {
            logger.warning("Cannot publish election result because the elections forum is not configured.");
            return;
        }
        String content;
        if (result.winner() == null) {
            content = "Election closed with no winner" + (result.tie() ? " because the top result was tied." : ".") +
                "\nNext election starts: " + format(result.nextStart()) +
                "\nNext election ends: " + format(result.nextEnd());
        } else {
            content = "Election winner: <@" + result.winner().discordId() + ">" +
                "\nNet score: " + result.winner().netScore() +
                "\nThumbs up: " + result.winner().upvotes() +
                "\nThumbs down: " + result.winner().downvotes() +
                "\nNext election starts: " + format(result.nextStart()) +
                "\nNext election ends: " + format(result.nextEnd());
        }
        forum.createForumPost("Election Result - " + result.election().periodKey(), MessageCreateData.fromContent(content))
            .queue(null, failure -> logger.warning("Failed to publish election result: " + failure.getMessage()));
    }

    @Override
    public void closeCandidatePosts(ElectionResult result) {
        if (!isReady()) {
            return;
        }
        for (var candidate : result.candidates()) {
            ThreadChannel thread = guild.getThreadChannelById(candidate.threadId());
            if (thread != null) {
                thread.getManager().setLocked(true).setArchived(true).queue(null, failure ->
                    logger.warning("Failed to lock/archive candidate post " + candidate.threadId() + ": " + failure.getMessage())
                );
            }
        }
    }

    @Override
    public void applyGovernmentDiscordRoles(String presidentDiscordId) {
        if (!isReady()) {
            return;
        }
        Role president = roleByName(config.presidentRoleName());
        List<Role> cabinetRoles = List.of(roleByName(config.cabinetRoleName(1)), roleByName(config.cabinetRoleName(2)), roleByName(config.cabinetRoleName(3)));
        if (president != null) {
            removeRoleFromAllMembers(president);
        }
        for (Role role : cabinetRoles) {
            if (role != null) {
                removeRoleFromAllMembers(role);
            }
        }
        if (presidentDiscordId != null && president != null) {
            guild.retrieveMemberById(presidentDiscordId).queue(member ->
                guild.addRoleToMember(member, president).queue(null, failure ->
                    logger.warning("Failed to add president Discord role: " + failure.getMessage())
                )
            );
        }
    }

    @Override
    public void publishPolicyApproved(ProposalRecord proposal) {
        if (!isReady() || config.approvedChangesLogChannelId() == 0L) {
            return;
        }
        TextChannel channel = guild.getTextChannelById(config.approvedChangesLogChannelId());
        if (channel == null) {
            logger.warning("Approved changes log channel is not a text channel or is not configured.");
            return;
        }
        channel.sendMessage("Approved policy change staged for next restart: `" + proposal.relativePath() + "` by <@" + proposal.proposerDiscordId() + ">.").queue();
    }

    @Override
    public void publishImpeachmentResult(ImpeachmentRecord impeachment, boolean passed) {
        if (!isReady()) {
            return;
        }
        ForumChannel forum = guild.getForumChannelById(config.impeachmentForumId());
        if (forum == null) {
            logger.warning("Cannot publish impeachment result because the impeachment forum is not configured.");
            return;
        }
        String content = (passed ? "Impeachment passed." : "Impeachment failed.") +
            "\nThumbs up: " + impeachment.upvotes() +
            "\nThumbs down: " + impeachment.downvotes() +
            "\nNet score: " + impeachment.netScore() +
            (passed ? "\nA special election has started in the elections forum." : "\nThe president remains in office.");
        ThreadChannel thread = guild.getThreadChannelById(impeachment.threadId());
        if (thread != null) {
            thread.getManager().setLocked(true).setArchived(true).queue();
        }
        forum.createForumPost("Impeachment Result #" + impeachment.id(), MessageCreateData.fromContent(content))
            .queue(null, failure -> logger.warning("Failed to publish impeachment result: " + failure.getMessage()));
    }

    @Override
    public void clearGovernmentDiscordRolesAfterImpeachment() {
        applyGovernmentDiscordRoles(null);
    }

    private void handleVerify(SlashCommandInteractionEvent event) {
        String code = verificationService.createCode(event.getUser().getId());
        event.reply("Run this in Minecraft within 15 minutes: `/election verify " + code + "`")
            .setEphemeral(true)
            .queue();
    }

    private void handleCabinetSet(SlashCommandInteractionEvent event) {
        var userOption = event.getOption("user");
        var tierOption = event.getOption("tier");
        if (userOption == null || tierOption == null) {
            event.reply("Missing user or tier.").setEphemeral(true).queue();
            return;
        }
        String memberId = userOption.getAsUser().getId();
        int tier = tierOption.getAsInt();
        worker.execute(() -> {
            CabinetService.CabinetResult result = cabinetService.assign(event.getUser().getId(), memberId, tier);
            if (result.success()) {
                syncCabinetDiscordRole(memberId, tier);
            }
            event.reply(result.message()).setEphemeral(true).queue();
        });
    }

    private void handleCabinetRemove(SlashCommandInteractionEvent event) {
        var userOption = event.getOption("user");
        if (userOption == null) {
            event.reply("Missing user.").setEphemeral(true).queue();
            return;
        }
        String memberId = userOption.getAsUser().getId();
        worker.execute(() -> {
            CabinetService.CabinetResult result = cabinetService.remove(event.getUser().getId(), memberId);
            if (result.success()) {
                removeCabinetDiscordRoles(memberId);
            }
            event.reply(result.message()).setEphemeral(true).queue();
        });
    }

    private void handlePolicyProposal(SlashCommandInteractionEvent event) {
        var fileOption = event.getOption("file");
        var contentOption = event.getOption("content");
        if (fileOption == null && contentOption == null) {
            openPolicyBrowser(event);
            return;
        }
        if (fileOption == null || contentOption == null) {
            event.reply("Use both `file` and `content`, or use `/election propose` with no options to open the GUI.").setEphemeral(true).queue();
            return;
        }
        event.deferReply(true).queue();
        worker.execute(() -> createAndPostProposal(event.getUser().getId(), fileOption.getAsString(), contentOption.getAsString(), response ->
            event.getHook().sendMessage(response).setEphemeral(true).queue()
        ));
    }

    private void handlePolicyUploadProposal(SlashCommandInteractionEvent event) {
        var fileOption = event.getOption("file");
        var attachmentOption = event.getOption("attachment");
        if (fileOption == null || attachmentOption == null) {
            event.reply("Missing file path or attachment.").setEphemeral(true).queue();
            return;
        }
        var attachment = attachmentOption.getAsAttachment();
        if (attachment.getSize() > config.policyMaxFileBytes()) {
            event.reply("That attachment is larger than the configured max file size.").setEphemeral(true).queue();
            return;
        }
        event.deferReply(true).queue();
        attachment.getProxy().download(0, (int) config.policyMaxFileBytes()).thenAccept(inputStream -> worker.execute(() -> {
            try (InputStream stream = inputStream) {
                String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                createAndPostProposal(event.getUser().getId(), fileOption.getAsString(), content, response ->
                    event.getHook().sendMessage(response).setEphemeral(true).queue()
                );
            } catch (Exception exception) {
                event.getHook().sendMessage("Could not read uploaded file: " + exception.getMessage()).setEphemeral(true).queue();
            }
        })).exceptionally(exception -> {
            event.getHook().sendMessage("Could not download uploaded file: " + exception.getMessage()).setEphemeral(true).queue();
            return null;
        });
    }

    private void handleSmokeTest(SlashCommandInteractionEvent event) {
        Member member = event.getMember();
        if (member == null || !member.hasPermission(Permission.MANAGE_SERVER)) {
            event.reply("Only members with Manage Server can run the ElectionBot smoke test.").setEphemeral(true).queue();
            return;
        }
        event.deferReply(true).queue();
        worker.execute(() -> {
            List<String> checks = new ArrayList<>();
            checks.add(checkForum("elections forum", config.electionsForumId()));
            checks.add(checkForum("impeachment forum", config.impeachmentForumId()));
            checks.add(checkForum("policy proposals forum", config.policyForumId()));
            checks.add(checkForum("polls forum", config.pollsForumId()));
            checks.add(checkTextChannel("approved changes log", config.approvedChangesLogChannelId()));
            checks.add(checkRole(config.presidentRoleName()));
            checks.add(checkRole(config.cabinetRoleName(1)));
            checks.add(checkRole(config.cabinetRoleName(2)));
            checks.add(checkRole(config.cabinetRoleName(3)));

            ForumChannel electionsForum = guild.getForumChannelById(config.electionsForumId());
            if (electionsForum == null) {
                event.getHook().sendMessage("Smoke test failed: elections forum is not configured.\n" + String.join("\n", checks)).setEphemeral(true).queue();
                return;
            }

            String content = "ElectionBot smoke test." +
                "\nIf you can see this post, the bot can post in the elections forum." +
                "\nThe bot will add thumbs up and thumbs down reactions to this message.";
            electionsForum.createForumPost("ElectionBot Smoke Test", MessageCreateData.fromContent(content))
                .queue(post -> {
                    post.getMessage().addReaction(THUMBS_UP).queue();
                    post.getMessage().addReaction(THUMBS_DOWN).queue();
                    String response = "Smoke test posted in the elections forum.\n\n" +
                        String.join("\n", checks) +
                        "\n\nRun `/election propose` next to test the file-browser GUI.";
                    event.getHook().sendMessage(response).setEphemeral(true).queue();
                }, failure -> event.getHook().sendMessage("Smoke test failed to post: " + failure.getMessage()).setEphemeral(true).queue());
        });
    }

    private void openPolicyBrowser(SlashCommandInteractionEvent event) {
        if (!policyService.canSubmitPolicy(event.getUser().getId())) {
            event.reply("Only the president or cabinet members can submit policy proposals.").setEphemeral(true).queue();
            return;
        }
        event.deferReply(true).queue();
        worker.execute(() -> {
            PolicyBrowserSession session = new PolicyBrowserSession();
            policySessions.put(event.getUser().getId(), session);
            BrowserMessage message = buildFileBrowserMessage(session);
            event.getHook().sendMessage(message.content()).setEphemeral(true).addComponents(message.components()).queue();
        });
    }

    private void replyFileBrowser(ButtonInteractionEvent event, PolicyBrowserSession session) {
        BrowserMessage message = buildFileBrowserMessage(session);
        event.reply(message.content()).setEphemeral(true).addComponents(message.components()).queue();
    }

    private BrowserMessage buildFileBrowserMessage(PolicyBrowserSession session) {
        List<PolicyService.FileEntry> files = policyService.listEditableFiles(MAX_BROWSER_FILES);
        if (files.isEmpty()) {
            return new BrowserMessage("No editable `.yml`, `.yaml`, or `.json` files were found with the current config.", List.of());
        }
        int totalPages = Math.max(1, (int) Math.ceil((double) files.size() / FILES_PER_PAGE));
        session.filePage = Math.max(0, Math.min(session.filePage, totalPages - 1));
        int start = session.filePage * FILES_PER_PAGE;
        int end = Math.min(files.size(), start + FILES_PER_PAGE);

        StringSelectMenu.Builder select = StringSelectMenu.create(POLICY_SELECT_FILE)
            .setPlaceholder("Choose a file to preview and edit")
            .setRequiredRange(1, 1);
        for (int index = start; index < end; index++) {
            PolicyService.FileEntry file = files.get(index);
            select.addOption(shorten(file.relativePath(), 95), String.valueOf(index), humanSize(file.size()));
        }

        List<net.dv8tion.jda.api.components.MessageTopLevelComponent> components = new ArrayList<>();
        components.add(ActionRow.of(select.build()));
        components.add(ActionRow.of(
            button(Button.secondary(POLICY_FILES_PREV, "Previous"), session.filePage <= 0),
            button(Button.secondary(POLICY_FILES_NEXT, "Next"), session.filePage >= totalPages - 1),
            Button.danger(POLICY_CANCEL, "Close")
        ));
        String content = "Policy file browser\nPage " + (session.filePage + 1) + " of " + totalPages +
            "\nShowing files allowed by the current ElectionsPlugin config.";
        return new BrowserMessage(content, components);
    }

    private void replyPreview(StringSelectInteractionEvent event, PolicyBrowserSession session) {
        BrowserMessage message = buildPreviewMessage(session);
        event.reply(message.content()).setEphemeral(true).addComponents(message.components()).queue();
    }

    private void replyPreview(ButtonInteractionEvent event, PolicyBrowserSession session) {
        BrowserMessage message = buildPreviewMessage(session);
        event.reply(message.content()).setEphemeral(true).addComponents(message.components()).queue();
    }

    private BrowserMessage buildPreviewMessage(PolicyBrowserSession session) {
        if (session.selectedPath == null) {
            return buildFileBrowserMessage(session);
        }
        PolicyService.FilePreview preview = policyService.previewFile(session.selectedPath, session.previewPage, PREVIEW_CHARS);
        if (!preview.success()) {
            return new BrowserMessage(preview.message(), List.of(ActionRow.of(
                Button.secondary(POLICY_BACK, "Back to files"),
                Button.danger(POLICY_CANCEL, "Close")
            )));
        }
        session.previewPage = preview.page();
        String pageContent = preview.pageContent().isBlank() ? "# empty file" : escapeCodeBlock(preview.pageContent());
        String content = "Previewing `" + preview.relativePath() + "`\nPage " + (preview.page() + 1) + " of " + preview.totalPages() +
            "\n```yaml\n" + pageContent + "\n```";
        List<net.dv8tion.jda.api.components.MessageTopLevelComponent> components = List.of(ActionRow.of(
            button(Button.secondary(POLICY_PREVIEW_PREV, "Previous"), preview.page() <= 0),
            button(Button.secondary(POLICY_PREVIEW_NEXT, "Next"), preview.page() >= preview.totalPages() - 1),
            Button.primary(POLICY_EDIT, "Edit"),
            Button.secondary(POLICY_BACK, "Files"),
            Button.danger(POLICY_CANCEL, "Close")
        ));
        return new BrowserMessage(content, components);
    }

    private void replyEditModal(ButtonInteractionEvent event, PolicyBrowserSession session) {
        if (session.selectedPath == null) {
            event.reply("Choose a file first.").setEphemeral(true).queue();
            return;
        }
        PolicyService.FilePreview preview = policyService.previewFile(session.selectedPath, 0, MODAL_VALUE_LIMIT);
        if (!preview.success()) {
            event.reply(preview.message()).setEphemeral(true).queue();
            return;
        }
        TextInput.Builder input = TextInput.create("content", TextInputStyle.PARAGRAPH)
            .setRequired(true)
            .setMinLength(1)
            .setMaxLength(MODAL_VALUE_LIMIT)
            .setPlaceholder("Paste the full replacement contents for " + session.selectedPath);
        if (preview.fullContent().length() <= MODAL_VALUE_LIMIT) {
            input.setValue(preview.fullContent());
        }
        Modal modal = Modal.create(POLICY_MODAL, "Propose file replacement")
            .addComponents(Label.of("Replacement file contents", input.build()))
            .build();
        event.replyModal(modal).queue();
    }

    private void createAndPostProposal(String userId, String relativePath, String proposedContent, ProposalResponder responder) {
        PolicyResult result = policyService.createProposal(userId, relativePath, proposedContent);
        if (!result.success()) {
            responder.reply(result.message());
            return;
        }
        postPolicyProposal(result.proposal());
        responder.reply("Proposal posted for public voting.");
    }

    private void postPolicyProposal(ProposalRecord proposal) {
        ForumChannel forum = guild.getForumChannelById(config.pollsForumId());
        if (forum == null) {
            logger.warning("Cannot post policy proposal because polls forum is not configured.");
            return;
        }
        String diff = proposal.diff();
        String visibleDiff = diff.length() > 1600 ? diff.substring(0, 1600) + "\n... diff truncated ..." : diff;
        String content = "Policy proposal by <@" + proposal.proposerDiscordId() + ">\n" +
            "File: `" + proposal.relativePath() + "`\n" +
            "Voting closes: " + format(Instant.ofEpochMilli(proposal.closesAt()).atZone(config.zoneId())) + "\n\n" +
            "```diff\n" + visibleDiff + "\n```";
        forum.createForumPost("Policy Proposal #" + proposal.id() + " - " + proposal.relativePath(), MessageCreateData.fromContent(content))
            .queue(post -> {
                ThreadChannel thread = post.getThreadChannel();
                String messageId = post.getMessage().getId();
                policyService.markProposalPosted(proposal.id(), thread.getId(), messageId);
                post.getMessage().addReaction(THUMBS_UP).queue();
                post.getMessage().addReaction(THUMBS_DOWN).queue();
            }, failure -> logger.warning("Failed to post policy proposal: " + failure.getMessage()));
    }

    private void notifyElectionPostRejected(String mention, String message) {
        ForumChannel forum = guild.getForumChannelById(config.electionsForumId());
        if (forum == null) {
            return;
        }
        forum.createForumPost("Election Notice", MessageCreateData.fromContent(mention + " " + message))
            .queue(null, failure -> logger.warning("Failed to post election notice: " + failure.getMessage()));
    }

    private void ensureDiscordRoles() {
        if (!config.autoCreateDiscordRoles()) {
            return;
        }
        getOrCreateRole(config.presidentRoleName());
        getOrCreateRole(config.cabinetRoleName(1));
        getOrCreateRole(config.cabinetRoleName(2));
        getOrCreateRole(config.cabinetRoleName(3));
    }

    private Role getOrCreateRole(String name) {
        Role existing = roleByName(name);
        if (existing != null || guild == null) {
            return existing;
        }
        try {
            return guild.createRole().setName(name).complete();
        } catch (Exception exception) {
            logger.warning("Failed to create Discord role '" + name + "': " + exception.getMessage());
            return null;
        }
    }

    private Role roleByName(String name) {
        if (guild == null || name == null || name.isBlank()) {
            return null;
        }
        List<Role> roles = guild.getRolesByName(name, true);
        return roles.isEmpty() ? null : roles.getFirst();
    }

    private void removeRoleFromAllMembers(Role role) {
        guild.findMembersWithRoles(role).onSuccess(members -> {
            for (Member member : members) {
                guild.removeRoleFromMember(member, role).queue(null, failure ->
                    logger.warning("Failed to remove Discord role " + role.getName() + ": " + failure.getMessage())
                );
            }
        });
    }

    private void syncCabinetDiscordRole(String discordId, int tier) {
        guild.retrieveMemberById(discordId).queue(member -> {
            removeCabinetDiscordRoles(member.getId());
            Role role = roleByName(config.cabinetRoleName(tier));
            if (role != null) {
                guild.addRoleToMember(member, role).queue(null, failure ->
                    logger.warning("Failed to add cabinet Discord role: " + failure.getMessage())
                );
            }
        }, failure -> logger.warning("Failed to retrieve cabinet member: " + failure.getMessage()));
    }

    private void removeCabinetDiscordRoles(String discordId) {
        guild.retrieveMemberById(discordId).queue(member -> {
            for (int tier = 1; tier <= 3; tier++) {
                Role role = roleByName(config.cabinetRoleName(tier));
                if (role != null && member.getRoles().contains(role)) {
                    guild.removeRoleFromMember(member, role).queue();
                }
            }
        }, failure -> logger.warning("Failed to retrieve member for cabinet role removal: " + failure.getMessage()));
    }

    private void registerSlashCommands() {
        guild.updateCommands().addCommands(
            Commands.slash("election", "Election, cabinet, verification, and policy tools.")
                .addSubcommands(
                    new SubcommandData("verify", "Generate a Minecraft verification code."),
                    new SubcommandData("status", "Show the election status."),
                    new SubcommandData("cabinet-set", "Appoint or replace a cabinet member.")
                        .addOption(OptionType.USER, "user", "The cabinet member.", true)
                        .addOption(OptionType.INTEGER, "tier", "Cabinet tier: 1, 2, or 3.", true),
                    new SubcommandData("cabinet-remove", "Remove a cabinet member.")
                        .addOption(OptionType.USER, "user", "The cabinet member to remove.", true),
                    new SubcommandData("propose", "Open the policy file browser or directly propose a change.")
                        .addOption(OptionType.STRING, "file", "Relative server file path for direct mode.", false)
                        .addOption(OptionType.STRING, "content", "Full replacement content for direct mode.", false),
                    new SubcommandData("propose-upload", "Propose a change using an uploaded replacement file.")
                        .addOption(OptionType.STRING, "file", "Relative server file path.", true)
                        .addOption(OptionType.ATTACHMENT, "attachment", "Replacement .yml, .yaml, or .json file.", true),
                    new SubcommandData("smoke-test", "Post a harmless demo message and check ElectionBot setup.")
                )
        ).queue(null, failure -> logger.warning("Failed to register Discord slash commands: " + failure.getMessage()));
    }

    private void checkStartupPermissions() {
        Member self = guild.getSelfMember();
        List<Permission> missing = new ArrayList<>();
        for (Permission permission : List.of(
            Permission.VIEW_CHANNEL,
            Permission.MESSAGE_SEND,
            Permission.MESSAGE_SEND_IN_THREADS,
            Permission.MESSAGE_HISTORY,
            Permission.MESSAGE_ADD_REACTION,
            Permission.MESSAGE_MANAGE,
            Permission.MANAGE_THREADS,
            Permission.MANAGE_ROLES
        )) {
            if (!self.hasPermission(permission)) {
                missing.add(permission);
            }
        }
        if (!missing.isEmpty()) {
            logger.warning("ElectionBot is missing Discord permissions: " + missing);
        }
    }

    private int reactionValue(String emojiName) {
        if ("👍".equals(emojiName) || "U+1F44D".equalsIgnoreCase(emojiName)) {
            return 1;
        }
        if ("👎".equals(emojiName) || "U+1F44E".equalsIgnoreCase(emojiName)) {
            return -1;
        }
        return 0;
    }

    private Button button(Button button, boolean disabled) {
        return disabled ? button.asDisabled() : button;
    }

    private String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " KB";
        }
        return (bytes / (1024 * 1024)) + " MB";
    }

    private String shorten(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return "..." + value.substring(value.length() - maxLength + 3);
    }

    private String escapeCodeBlock(String value) {
        return value.replace("```", "` ` `");
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private String inviteUrl(String clientId) {
        long permissions = Permission.getRaw(
            Permission.VIEW_CHANNEL,
            Permission.MESSAGE_SEND,
            Permission.MESSAGE_SEND_IN_THREADS,
            Permission.MESSAGE_HISTORY,
            Permission.MESSAGE_ADD_REACTION,
            Permission.MESSAGE_MANAGE,
            Permission.MANAGE_THREADS,
            Permission.MANAGE_ROLES,
            Permission.USE_APPLICATION_COMMANDS
        );
        return "https://discord.com/oauth2/authorize?client_id=" + clientId +
            "&permissions=" + permissions +
            "&scope=bot%20applications.commands";
    }

    private String checkForum(String label, long channelId) {
        return (channelId != 0L && guild.getForumChannelById(channelId) != null ? "[OK] " : "[MISSING] ") + label;
    }

    private String checkTextChannel(String label, long channelId) {
        return (channelId != 0L && guild.getTextChannelById(channelId) != null ? "[OK] " : "[MISSING] ") + label;
    }

    private String checkRole(String roleName) {
        return (roleByName(roleName) != null ? "[OK] role " : "[MISSING] role ") + roleName;
    }

    private String format(java.time.ZonedDateTime value) {
        return value.format(DateTimeFormatter.ofPattern("MMMM d, yyyy h:mm a z"));
    }

    private record BrowserMessage(String content, List<net.dv8tion.jda.api.components.MessageTopLevelComponent> components) {
    }

    private static final class PolicyBrowserSession {
        private int filePage;
        private String selectedPath;
        private int previewPage;
    }

    @FunctionalInterface
    private interface ProposalResponder {
        void reply(String message);
    }
}
