package com.leetcode.dashboard.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FeaturesControllerTest {

    @WebMvcTest(FeaturesController.class)
    @TestPropertySource(properties = {
            "app.chat.enabled=true",
            "app.chat.provider=cli"
    })
    static class ChatEnabled {

        @Autowired
        private MockMvc mvc;

        @Test
        void returnsLiveFlagState() throws Exception {
            mvc.perform(get("/api/features"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.chatEnabled").value(true))
                    .andExpect(jsonPath("$.chatProvider").value("cli"));
        }
    }

    @WebMvcTest(FeaturesController.class)
    @TestPropertySource(properties = {
            "app.chat.enabled=false",
            "app.chat.provider=api"
    })
    static class ChatDisabled {

        @Autowired
        private MockMvc mvc;

        @Test
        void reportsDisabledAndProviderRegardless() throws Exception {
            mvc.perform(get("/api/features"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.chatEnabled").value(false))
                    .andExpect(jsonPath("$.chatProvider").value("api"));
        }
    }
}
