package com.example.cl15bot;

import static org.mockito.Mockito.*;
import java.util.List;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.guild.GuildAvailableEvent;
import net.dv8tion.jda.api.events.guild.GuildUnavailableEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.StatusChangeEvent;
import net.dv8tion.jda.api.events.session.SessionResumeEvent;
import net.dv8tion.jda.api.events.session.SessionRecreateEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import static org.junit.jupiter.api.Assertions.*;

class DiscordBotTests {
    private static final String GUILD = "123456789012345678";
    private static final String ID = "414964289770881035";
    private final JDA jda = mock(JDA.class);
    private final Guild guild = mock(Guild.class);
    private final VoiceChannel channel = mock(VoiceChannel.class);
    private final StudyRecordingService recording = mock(StudyRecordingService.class);
    private final DiscordStudySessionsTests.MutableClock clock = new DiscordStudySessionsTests.MutableClock();
    private DiscordBot bot;
    private Member member;

    @AfterEach void stop() { bot.stop(); }

    @BeforeEach void setup() {
        clock.set("2026-10-13T10:00:00Z");
        bot = new DiscordBot(new DiscordProperties("", GUILD, "{\"" + ID + "\":\"정명남\"}"),
                new BotProperties("", "notion", "study", "스터디", "members"), recording, clock, Runnable::run);
        bot.initialize(new DiscordMemberMapping("{\"" + ID + "\":\"정명남\"}"));
        when(jda.getGuildById(GUILD)).thenReturn(guild);
        when(guild.getId()).thenReturn(GUILD);
        when(guild.upsertCommand(any(net.dv8tion.jda.api.interactions.commands.build.CommandData.class)))
                .thenReturn(mock(net.dv8tion.jda.api.requests.restaction.CommandCreateAction.class));
        when(guild.getVoiceChannels()).thenReturn(List.of(channel));
        when(channel.getId()).thenReturn("voice");
        member = member(ID, false);
        var botMember = member("123456789012345679", true);
        when(channel.getMembers()).thenReturn(List.of(member, botMember));
        when(recording.processDiscordAutomatic(any())).thenReturn(ResponseEntity.ok(
                new StudyCommandController.CommandResponse("ephemeral", "✅ 저장했습니다.")));
    }

    private Member member(String id, boolean isBot) {
        var member = mock(Member.class);
        var user = mock(User.class);
        when(member.getId()).thenReturn(id);
        when(member.getUser()).thenReturn(user);
        when(user.isBot()).thenReturn(isBot);
        when(user.getName()).thenReturn("username");
        return member;
    }

    @Test void readyStartsExistingHumanAndDepartureSavesOnce() {
        var ready = mock(ReadyEvent.class);
        when(ready.getJDA()).thenReturn(jda);
        bot.onReady(ready);
        clock.set("2026-10-13T10:03:00Z");
        var departure = mock(GuildVoiceUpdateEvent.class);
        when(departure.getGuild()).thenReturn(guild);
        when(departure.getMember()).thenReturn(member);
        bot.onGuildVoiceUpdate(departure);
        bot.onGuildVoiceUpdate(departure);
        var entry = ArgumentCaptor.forClass(StudyEntry.class);
        verify(recording, times(1)).processDiscordAutomatic(entry.capture());
        assertEquals(ID, entry.getValue().userId());
        assertEquals(3, entry.getValue().minutes());
    }

    @Test void unavailableGuildClosesThenAvailabilityStartsFresh() {
        var available = mock(GuildAvailableEvent.class);
        when(available.getGuild()).thenReturn(guild);
        when(available.getJDA()).thenReturn(jda);
        bot.onGuildAvailable(available);
        clock.set("2026-10-13T10:03:00Z");
        bot.onGatewayPing(mock(net.dv8tion.jda.api.events.GatewayPingEvent.class));
        var unavailable = mock(GuildUnavailableEvent.class);
        when(unavailable.getGuild()).thenReturn(guild);
        clock.set("2026-10-13T10:05:00Z");
        bot.onGuildUnavailable(unavailable);
        bot.onGuildUnavailable(unavailable);
        clock.set("2026-10-13T10:10:00Z");
        bot.onGuildAvailable(available);
        clock.set("2026-10-13T10:13:00Z");
        var departure = mock(GuildVoiceUpdateEvent.class);
        when(departure.getGuild()).thenReturn(guild);
        when(departure.getMember()).thenReturn(member);
        bot.onGuildVoiceUpdate(departure);
        var entries = ArgumentCaptor.forClass(StudyEntry.class);
        verify(recording, times(2)).processDiscordAutomatic(entries.capture());
        assertEquals(3, entries.getAllValues().get(0).endTime().getMinute());
        assertEquals(10, entries.getAllValues().get(1).startTime().getMinute());
    }

    @Test void ignoresOtherGuilds() {
        var other = mock(Guild.class);
        when(other.getId()).thenReturn("999999999999999999");
        var update = mock(GuildVoiceUpdateEvent.class);
        when(update.getGuild()).thenReturn(other);
        bot.onGuildVoiceUpdate(update);
        verifyNoInteractions(recording);
        verify(update, never()).getMember();
    }

    private void readyAndDisconnect() {
        var ready = mock(ReadyEvent.class);
        when(ready.getJDA()).thenReturn(jda);
        bot.onReady(ready);
        clock.set("2026-10-13T10:05:00Z");
        bot.onGatewayPing(mock(net.dv8tion.jda.api.events.GatewayPingEvent.class));
        var status = mock(StatusChangeEvent.class);
        when(status.getOldStatus()).thenReturn(JDA.Status.CONNECTED);
        when(status.getNewStatus()).thenReturn(JDA.Status.DISCONNECTED);
        bot.onStatusChange(status);
        verifyNoInteractions(recording);
    }

    private void depart() {
        var departure = mock(GuildVoiceUpdateEvent.class);
        when(departure.getGuild()).thenReturn(guild);
        when(departure.getMember()).thenReturn(member);
        bot.onGuildVoiceUpdate(departure);
    }

    @Test void gatewayResumeReflectsReplayedDepartureWithoutWaitingForTimeout() {
        readyAndDisconnect();
        clock.set("2026-10-13T10:05:01Z");
        depart();
        when(channel.getMembers()).thenReturn(List.of());
        verifyNoInteractions(recording);
        var resume = mock(SessionResumeEvent.class);
        when(resume.getJDA()).thenReturn(jda);
        bot.onSessionResume(resume);
        bot.onSessionResume(resume);
        var entry = ArgumentCaptor.forClass(StudyEntry.class);
        verify(recording).processDiscordAutomatic(entry.capture());
        assertEquals(5, entry.getValue().minutes());
    }

    @Test void recreatedGatewayKeepsStartForAnOccupantStillInSameRoom() {
        readyAndDisconnect();
        clock.set("2026-10-13T10:05:01Z");
        var recreated = mock(SessionRecreateEvent.class);
        when(recreated.getJDA()).thenReturn(jda);
        bot.onSessionRecreate(recreated);
        verifyNoInteractions(recording);
        clock.set("2026-10-13T10:10:00Z");
        depart();
        var entry = ArgumentCaptor.forClass(StudyEntry.class);
        verify(recording).processDiscordAutomatic(entry.capture());
        assertEquals(10, entry.getValue().minutes());
    }

    @Test void gatewayResumeDoesNotReconcileAnUnavailableGuildAsEmpty() {
        readyAndDisconnect();
        clock.set("2026-10-13T10:05:01Z");
        when(jda.isUnavailable(Long.parseLong(GUILD))).thenReturn(true);
        when(channel.getMembers()).thenReturn(List.of());
        var resume = mock(SessionResumeEvent.class);
        when(resume.getJDA()).thenReturn(jda);
        bot.onSessionResume(resume);
        verifyNoInteractions(recording);
        when(jda.isUnavailable(Long.parseLong(GUILD))).thenReturn(false);
        when(channel.getMembers()).thenReturn(List.of(member));
        var available = mock(GuildAvailableEvent.class);
        when(available.getGuild()).thenReturn(guild);
        when(available.getJDA()).thenReturn(jda);
        bot.onGuildAvailable(available);
        clock.set("2026-10-13T10:10:00Z");
        depart();
        var entry = ArgumentCaptor.forClass(StudyEntry.class);
        verify(recording).processDiscordAutomatic(entry.capture());
        assertEquals(10, entry.getValue().minutes());
    }
}
