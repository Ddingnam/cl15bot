package com.example.cl15bot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.requests.restaction.CommandCreateAction;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

class DiscordStudyCommandsTests {
    private static final String GUILD = "123456789012345678";
    private static final String ID = "414964289770881035";
    private final StudyRecordingService recording = mock(StudyRecordingService.class);
    private final SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
    private final ReplyCallbackAction reply = mock(ReplyCallbackAction.class, RETURNS_SELF);
    private final InteractionHook hook = mock(InteractionHook.class);
    @SuppressWarnings("unchecked")
    private final WebhookMessageEditAction<Message> edit = mock(WebhookMessageEditAction.class, RETURNS_SELF);
    private final Guild guild = mock(Guild.class);
    private final User user = mock(User.class);
    private final List<Runnable> tasks = new ArrayList<>();
    private final java.util.function.BiConsumer<String, String> dm = mock(java.util.function.BiConsumer.class);
    private DiscordStudyCommands commands;

    @BeforeEach void setup() {
        commands = new DiscordStudyCommands(new DiscordProperties("", GUILD, ""),
                new BotProperties("mm", "notion", "study", "스터디", "members"),
                new DiscordMemberMapping("{\"" + ID + "\":\"정명남\"}"), recording,
                Clock.fixed(Instant.parse("2026-10-13T14:00:00Z"), ZoneId.of("Asia/Seoul")), tasks::add,
                dm, new DiscordTasks());
        when(event.getName()).thenReturn("st");
        when(event.getGuild()).thenReturn(guild);
        when(guild.getId()).thenReturn(GUILD);
        when(event.getUser()).thenReturn(user);
        when(user.getId()).thenReturn(ID);
        when(user.getName()).thenReturn("jmn");
        when(event.getChannelId()).thenReturn("text-channel");
        when(event.reply(anyString())).thenReturn(reply);
        when(event.replyEmbeds(any(net.dv8tion.jda.api.entities.MessageEmbed.class))).thenReturn(reply);
        when(hook.editOriginal(anyString())).thenReturn(edit);
        when(hook.editOriginalEmbeds(any(net.dv8tion.jda.api.entities.MessageEmbed.class))).thenReturn(edit);
        doAnswer(invocation -> {
            Consumer<InteractionHook> success = invocation.getArgument(0);
            success.accept(hook);
            return null;
        }).when(reply).queue(any(), any());
        when(recording.processDiscord(any(), any(), any())).thenReturn(ResponseEntity.ok(
                new StudyCommandController.CommandResponse("ephemeral", "✅ 저장했습니다.")));
    }

    private void input(String value) {
        var option = mock(OptionMapping.class);
        when(option.getAsString()).thenReturn(value);
        when(event.getOption("입력")).thenReturn(option);
    }

    @Test void manualOneMinuteUsesMmParserAndAcknowledgesBeforeSaving() {
        input("1013 1900 1901 Java 복습");
        commands.onSlashCommandInteraction(event);
        verifyNoInteractions(recording);
        verify(reply).setEphemeral(true);
        verify(reply, never()).setEphemeral(false);
        assertEquals(1, tasks.size());
        tasks.getFirst().run();
        var entry = ArgumentCaptor.forClass(StudyEntry.class);
        verify(recording).processDiscord(entry.capture(), any(), any());
        assertEquals(1, entry.getValue().minutes());
        assertEquals("정명남", entry.getValue().memberName());
        assertEquals("Java 복습", entry.getValue().subject());
        assertEquals(ID, entry.getValue().userId());
        var card = ArgumentCaptor.forClass(net.dv8tion.jda.api.entities.MessageEmbed.class);
        verify(hook).editOriginalEmbeds(card.capture());
        assertEquals("공부 기록 완료", card.getValue().getTitle());
        assertEquals("저장했습니다.", card.getValue().getDescription());
        verify(event, never()).getChannel();
    }

    @Test void helpAndInfoArePrivateAndDoNotStore() {
        commands.onSlashCommandInteraction(event); // no input -> help
        verify(event).reply(contains("/st 입력:"));
        input("내정보");
        commands.onSlashCommandInteraction(event);
        verify(event).reply(contains("Discord 사용자 ID: " + ID));
        verify(reply, times(2)).setEphemeral(true);
        verifyNoInteractions(recording);
        assertTrue(tasks.isEmpty());
    }

    @Test void invalidAndFutureTimesAreRejectedPrivatelyBeforeQueue() {
        input("1013 2400 2500 Java");
        commands.onSlashCommandInteraction(event);
        verify(event).reply(contains("2026년에 존재하는 날짜와 시각"));
        input("1014 1900 2000 Java");
        commands.onSlashCommandInteraction(event);
        verify(event).reply(contains("미래 시각"));
        verify(reply, times(2)).setEphemeral(true);
        verifyNoInteractions(recording);
        assertTrue(tasks.isEmpty());
    }

    @Test void unmappedUserGetsDiscordAccountInstructions() {
        when(user.getId()).thenReturn("745646325558935654");
        input("1013 1900 2000 Java");
        commands.onSlashCommandInteraction(event);
        verify(event).reply(contains("Discord 계정에 연결된 Members 이름이 없습니다"));
        verify(reply).setEphemeral(true);
        verifyNoInteractions(recording);
    }

    @Test void rejectsDifferentGuildOrDmWithoutSaving() {
        when(guild.getId()).thenReturn("999999999999999999");
        commands.onSlashCommandInteraction(event);
        when(event.getGuild()).thenReturn(null);
        commands.onSlashCommandInteraction(event);
        verify(reply, times(2)).setEphemeral(true);
        verifyNoInteractions(recording);
    }

    @Test void failedAcknowledgmentDoesNotSave() {
        input("1013 1900 2000 Java");
        doAnswer(invocation -> {
            Consumer<Throwable> failure = invocation.getArgument(1);
            failure.accept(new IllegalStateException());
            return null;
        }).when(reply).queue(any(), any());
        commands.onSlashCommandInteraction(event);
        assertTrue(tasks.isEmpty());
        verifyNoInteractions(recording);
    }

    @Test void queueRejectionEditsPrivateOriginalWithoutSaving() {
        commands = new DiscordStudyCommands(new DiscordProperties("", GUILD, ""),
                new BotProperties("mm", "notion", "study", "스터디", "members"),
                new DiscordMemberMapping("{\"" + ID + "\":\"정명남\"}"), recording,
                Clock.fixed(Instant.parse("2026-10-13T14:00:00Z"), ZoneId.of("Asia/Seoul")),
                task -> { throw new java.util.concurrent.RejectedExecutionException(); });
        input("1013 1900 2000 Java");
        commands.onSlashCommandInteraction(event);
        verify(hook).editOriginal(contains("접수하지 못했습니다"));
        verify(reply).setEphemeral(true);
        verifyNoInteractions(recording);
    }

    @Test void crossMidnightAndOverlapUseCommonRecordingService() {
        input("1012 2300 1013 0100 Java 복습");
        when(recording.processDiscord(any(), any(), any())).thenReturn(ResponseEntity.ok(
                new StudyCommandController.CommandResponse("ephemeral", "⚠️ 같은 멤버의 기존 기록과 공부 시간이 겹칩니다.")));
        commands.onSlashCommandInteraction(event);
        tasks.getFirst().run();
        var entry = ArgumentCaptor.forClass(StudyEntry.class);
        verify(recording).processDiscord(entry.capture(), any(), any());
        assertEquals(120, entry.getValue().minutes());
        assertEquals(12, entry.getValue().date().getDayOfMonth());
        verify(hook).editOriginal(contains("겹칩니다"));
        verify(edit).setEmbeds(List.of());
        verify(reply).setEphemeral(true);
    }

    @Test void registrationUpsertsOnlyStWithOptionalTextAndRetriesOnFailure() {
        var action = mock(CommandCreateAction.class);
        when(guild.upsertCommand(any(CommandData.class))).thenReturn(action);
        commands.register(guild);
        commands.register(guild);
        var data = ArgumentCaptor.forClass(CommandData.class);
        verify(guild, times(1)).upsertCommand(data.capture());
        assertEquals("st", data.getValue().getName());
        var slash = (net.dv8tion.jda.api.interactions.commands.build.SlashCommandData) data.getValue();
        assertEquals("입력", slash.getOptions().getFirst().getName());
        assertFalse(slash.getOptions().getFirst().isRequired());
        assertEquals(2000, slash.getOptions().getFirst().getMaxLength());
        verify(guild, never()).updateCommands();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<Throwable>> failure = ArgumentCaptor.forClass(Consumer.class);
        verify(action).queue(any(), failure.capture());
        failure.getValue().accept(new IllegalStateException());
        commands.register(guild);
        verify(guild, times(2)).upsertCommand(any(CommandData.class));
    }

    @Test void failedResultEditFallsBackToDmWithoutSavingAgain() {
        input("1013 1900 2000 Java");
        doAnswer(invocation -> {
            Consumer<Throwable> failure = invocation.getArgument(1);
            failure.accept(new IllegalStateException("expired interaction"));
            return null;
        }).when(edit).queue(any(), any());
        commands.onSlashCommandInteraction(event);
        tasks.getFirst().run();
        verify(recording, times(1)).processDiscord(any(), any(), any());
        verify(dm).accept(ID, "✅ 저장했습니다.");
    }

    @Test void synchronousEditFailureAlsoFallsBackToDm() {
        input("1013 1900 2000 Java");
        when(hook.editOriginalEmbeds(any(net.dv8tion.jda.api.entities.MessageEmbed.class))).thenThrow(new IllegalStateException());
        commands.onSlashCommandInteraction(event);
        tasks.getFirst().run();
        verify(recording, times(1)).processDiscord(any(), any(), any());
        verify(dm).accept(ID, "✅ 저장했습니다.");
    }

    @Test void acknowledgmentArrivingDuringShutdownDoesNotStartSave() {
        input("1013 1900 2000 Java");
        doAnswer(invocation -> {
            commands.stopAccepting();
            Consumer<InteractionHook> success = invocation.getArgument(0);
            success.accept(hook);
            return null;
        }).when(reply).queue(any(), any());
        commands.onSlashCommandInteraction(event);
        assertTrue(tasks.isEmpty());
        verifyNoInteractions(recording);
        verify(hook).editOriginal(contains("저장 요청을 보내지 않았습니다"));
    }
}
