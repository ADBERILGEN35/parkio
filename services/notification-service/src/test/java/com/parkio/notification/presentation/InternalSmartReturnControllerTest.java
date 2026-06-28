package com.parkio.notification.presentation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.parkio.notification.infrastructure.smartreturn.SmartReturnScheduler;
import com.parkio.notification.infrastructure.smartreturn.SmartReturnSchedulerTickSummary;
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
        when(scheduler.sendMorningPrompts()).thenReturn(
                new SmartReturnSchedulerTickSummary(true, 1, 1, 0, 0, 0, 1, 0));

        mockMvc.perform(post("/internal/notifications/smart-return/trigger-morning-prompt"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.eligibleUsers").value(1))
                .andExpect(jsonPath("$.promptedUsers").value(1))
                .andExpect(jsonPath("$.notificationsCreated").value(1));

        verify(scheduler).sendMorningPrompts();
    }

    @Test
    void triggerReturnCheckRunsSchedulerTick() throws Exception {
        when(scheduler.runReturnChecks()).thenReturn(
                new SmartReturnSchedulerTickSummary(true, 1, 0, 1, 0, 1, 0, 0));

        mockMvc.perform(post("/internal/notifications/smart-return/trigger-return-check"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.eligibleUsers").value(1))
                .andExpect(jsonPath("$.returnChecksClaimed").value(1))
                .andExpect(jsonPath("$.noSpots").value(1))
                .andExpect(jsonPath("$.notificationsCreated").value(0));

        verify(scheduler).runReturnChecks();
    }
}
