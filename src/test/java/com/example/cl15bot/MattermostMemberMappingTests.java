package com.example.cl15bot;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MattermostMemberMappingTests {
    @Test void stableIdMapsToNameAndUnknownAccountIsNotGuessed() {
        var mapping=new MattermostMemberMapping("{\"u1\":\" 정명남 \",\"u2\":\"홍 길동\"}");
        assertEquals("정명남",mapping.nameFor("u1").orElseThrow());
        assertEquals("홍 길동",mapping.nameFor("u2").orElseThrow());
        assertTrue(mapping.nameFor("unknown").isEmpty());
        assertTrue(new MattermostMemberMapping("").nameFor("u1").isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"invalid", "[]", "{\"u1\":123}", "{\"u1\":\"\"}","{\"\":\"이름\"}"})
    void invalidConfigurationFailsWithSafeMessage(String json) {
        var ex=assertThrows(IllegalArgumentException.class,()->new MattermostMemberMapping(json));
        assertTrue(ex.getMessage().contains("MATTERMOST_MEMBER_MAPPINGS"));
        assertNull(ex.getCause());
    }
}
