package com.example.cl15bot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class Cl15botApplicationTests {
    @Autowired
    MockMvc mvc;

	@Test
	void contextLoads() {
	}

    @Test
    void localTestEndpointsAreUnavailableWithoutLocalProfile() throws Exception {
        mvc.perform(get("/local-test")).andExpect(status().isNotFound());
        mvc.perform(get("/local-test/status")).andExpect(status().isNotFound());
    }

}
