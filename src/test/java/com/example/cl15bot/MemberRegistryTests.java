package com.example.cl15bot;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class MemberRegistryTests {
    @ParameterizedTest
    @ValueSource(strings = {"found", "missing", "duplicate", "has-more", "malformed"})
    void queriesExactExistingTitleAndRequiresExactlyOneMember(String scenario) {
        BotProperties properties = new BotProperties("slash", "notion-secret", "study-source", "스터디", "members-source");
        RestClient.Builder builder = BotConfiguration.notionClientBuilder(properties);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MemberRegistry registry = new MemberRegistry(new NotionStudyClient(properties, builder.build()));
        String response = switch (scenario) {
            case "found" -> "{\"results\":[{\"id\":\"member-1\"}],\"has_more\":false}";
            case "missing" -> "{\"results\":[],\"has_more\":false}";
            case "duplicate" -> "{\"results\":[{\"id\":\"member-1\"},{\"id\":\"member-2\"}],\"has_more\":false}";
            case "has-more" -> "{\"results\":[{\"id\":\"member-1\"}],\"has_more\":true}";
            default -> "{}";
        };
        server.expect(requestTo("https://api.notion.com/v1/data_sources/members-source/query"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer notion-secret"))
                .andExpect(header("Notion-Version", "2025-09-03"))
                .andExpect(jsonPath("$.filter.property").value("title"))
                .andExpect(jsonPath("$.filter.title.equals").value("정명남"))
                .andExpect(jsonPath("$.page_size").value(2))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        switch (scenario) {
            case "found" -> assertEquals("member-1", registry.pageIdFor("정명남").orElseThrow());
            case "missing" -> assertTrue(registry.pageIdFor("정명남").isEmpty());
            case "malformed" -> assertThrows(org.springframework.web.client.RestClientException.class,
                    () -> registry.pageIdFor("정명남"));
            default -> assertThrows(MemberRegistry.DuplicateMemberException.class,
                    () -> registry.pageIdFor("정명남"));
        }
        server.verify();
    }
}