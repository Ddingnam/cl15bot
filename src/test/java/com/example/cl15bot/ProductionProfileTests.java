package com.example.cl15bot;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"prod", "local"})
class ProductionProfileTests {
    @Autowired
    MockMvc mvc;

    @Test
    void productionBlocksLocalToolsEvenWhenLocalIsAlsoRequested() throws Exception {
        mvc.perform(get("/local-test")).andExpect(status().isNotFound());
        mvc.perform(get("/local-test/status")).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
