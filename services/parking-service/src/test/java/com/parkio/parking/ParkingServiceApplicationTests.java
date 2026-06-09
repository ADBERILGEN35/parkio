package com.parkio.parking;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.infrastructure.lifecycle.ParkingExpiryJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ParkingServiceApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextLoads() {
        // Verifies the Spring application context starts cleanly.
    }

    @Test
    void parkingExpirySchedulerIsDisabledInTestProfile() {
        assertThat(applicationContext.getBeansOfType(ParkingExpiryJob.class)).isEmpty();
    }
}
