package com.example.cl15bot;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

class MattermostResponseSenderTests {
    @Test
    void sendsPrivateJsonResultWithoutNotionOrSlashCredentials() {
        var builder = MattermostResponseSender.clientBuilder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var sender = new MattermostResponseSender(builder.build());
        URI uri = MattermostResponseSender.responseUri("https://mm.example.com/hooks/commands/callback123");
        server.expect(requestTo(uri)).andExpect(method(HttpMethod.POST))
                .andExpect(headerDoesNotExist("Authorization"))
                .andExpect(headerDoesNotExist("Notion-Version"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.response_type").value("ephemeral"))
                .andExpect(jsonPath("$.text").value("✅ 저장 완료"))
                .andRespond(withSuccess());
        sender.send(uri, new StudyCommandController.CommandResponse("ephemeral", "✅ 저장 완료"));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"timeout", "permission", "server"})
    void failedDeliveryDoesNotThrowOrRetry(String scenario) {
        var builder = MattermostResponseSender.clientBuilder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var expected = server.expect(requestTo("https://mm.example.com/hooks/commands/callback123"));
        switch (scenario) {
            case "timeout" -> expected.andRespond(withException(new java.net.SocketTimeoutException("private-url-token")));
            case "permission" -> expected.andRespond(withForbiddenRequest());
            default -> expected.andRespond(withServerError());
        }
        var sender = new MattermostResponseSender(builder.build());
        assertDoesNotThrow(() -> sender.send(URI.create("https://mm.example.com/hooks/commands/callback123"),
                new StudyCommandController.CommandResponse("ephemeral", "✅ 저장 완료")));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://mm.example.com/hooks/commands/token123",
        "http://mm.internal:8065/hooks/commands/token123", "https://mm.example.com/team/hooks/commands/token123"})
    void acceptsMattermostCallbackUrls(String url) {
        assertEquals(url, MattermostResponseSender.responseUri(url).toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://mm.example.com/other", "file:///hooks/commands/token123",
        "https://user:secret@mm.example.com/hooks/commands/token123", "https://mm.example.com/hooks/commands/token123?secret=1",
        "https://mm.example.com/hooks/commands/token123#fragment", "not a url", "/hooks/commands/token123"})
    void rejectsMalformedCallbacks(String url) {
        assertThrows(IllegalArgumentException.class, () -> MattermostResponseSender.responseUri(url));
    }
}
