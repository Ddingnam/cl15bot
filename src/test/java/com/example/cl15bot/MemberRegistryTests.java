package com.example.cl15bot;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MemberRegistryTests {
    private MemberRegistry registry(String mappings) {
        return new MemberRegistry(new BotProperties("", "", "", "스터디", mappings));
    }

    @Test
    void resolvesMembersSeparatelyAndNormalizesPageIds() {
        MemberRegistry members = registry("u1=00000000000000000000000000000001,u2=00000000-0000-0000-0000-000000000002");
        assertEquals("00000000-0000-0000-0000-000000000001", members.pageIdFor("u1").orElseThrow());
        assertEquals("00000000-0000-0000-0000-000000000002", members.pageIdFor("u2").orElseThrow());
        assertTrue(members.pageIdFor("unknown").isEmpty());
        assertTrue(registry("").pageIdFor("u1").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"u1=bad", "u1", "=00000000000000000000000000000001",
            "u1=00000000000000000000000000000001,", "u1=00000000000000000000000000000001,u1=00000000000000000000000000000002"})
    void rejectsMalformedOrDuplicateMappings(String mappings) {
        assertThrows(IllegalArgumentException.class, () -> registry(mappings));
    }
}
