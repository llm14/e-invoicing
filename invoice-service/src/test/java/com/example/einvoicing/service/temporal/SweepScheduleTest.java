package com.example.einvoicing.service.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.einvoicing.workflow.InvoiceSweepWorkflow;
import io.quarkus.test.junit.QuarkusTest;
import io.temporal.api.enums.v1.ScheduleOverlapPolicy;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleDescription;
import jakarta.inject.Inject;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Needs the docker-compose stack (Postgres and Temporal) to be running. */
@QuarkusTest
class SweepScheduleTest {

    @Inject
    TemporalRuntime temporal;

    private ScheduleClient scheduleClient() {
        return ScheduleClient.newInstance(temporal.client().getWorkflowServiceStubs());
    }

    private ScheduleDescription describe() {
        return scheduleClient().getHandle(InvoiceSweepWorkflow.SCHEDULE_ID).describe();
    }

    private Duration interval() {
        return describe().getSchedule().getSpec().getIntervals().get(0).getEvery();
    }

    /** The schedule is created in the background shortly after startup. */
    private void awaitSchedule() {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(describe()).isNotNull());
    }

    @Test
    void theScheduleExistsShortlyAfterStartupAndSkipsOverlappingRuns() {
        awaitSchedule();

        assertThat(describe().getSchedule().getPolicy().getOverlap())
                .isEqualTo(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP);
    }

    @Test
    void anExistingScheduleIsUpdatedToMatchTheCode() {
        awaitSchedule();
        var original = interval();
        try {
            SweepSchedule.createOrUpdate(scheduleClient(), temporal.taskQueue(), Duration.ofMinutes(7));

            assertThat(interval()).isEqualTo(Duration.ofMinutes(7));
        } finally {
            SweepSchedule.createOrUpdate(scheduleClient(), temporal.taskQueue(), original);
        }
    }
}
