package com.parkio.notification.presentation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.parkio.notification.infrastructure.smartreturn.SmartReturnScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InternalSmartReturnControllerTest {

    private SmartReturnScheduler scheduler;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        scheduler = mock(SmartReturnScheduler.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new InternalSmartReturnController(scheduler))
                .build();
    }

    @Test
    void triggerMorningPromptRunsSchedulerTick() throws Exception {
        mockMvc.perform(post("/internal/notifications/smart-return/trigger-morning-prompt"))
                .andExpect(status().isAccepted());

        verify(scheduler).sendMorningPrompts();
    }

    @Test
    void triggerReturnCheckRunsSchedulerTick() throws Exception {
        mockMvc.perform(post("/internal/notifications/smart-return/trigger-return-check"))
                .andExpect(status().isAccepted());

        verify(scheduler).runReturnChecks();
    }
}
