package com.example.einvoicing.service.temporal;

import com.example.einvoicing.workflow.InvoiceSweepWorkflow;
import io.quarkus.logging.Log;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.schedules.Schedule;
import io.temporal.client.schedules.ScheduleActionStartWorkflow;
import io.temporal.client.schedules.ScheduleAlreadyRunningException;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleIntervalSpec;
import io.temporal.client.schedules.ScheduleOptions;
import io.temporal.client.schedules.SchedulePolicy;
import io.temporal.client.schedules.ScheduleSpec;
import io.temporal.client.schedules.ScheduleUpdate;
import io.temporal.api.enums.v1.ScheduleOverlapPolicy;
import io.temporal.serviceclient.WorkflowServiceStubs;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Keeps the Temporal Schedule that runs the recovery sweep in line with this code. The schedule
 * lives on the Temporal server, so it is created if missing and updated if it already exists.
 *
 * <p>The app must start even when Temporal is down (that is the situation the sweep recovers
 * from), so this retries in the background instead of failing startup.
 */
@ApplicationScoped
public class SweepSchedule {

    private static final Duration RETRY_DELAY = Duration.ofSeconds(10);

    @ConfigProperty(name = "app.sweep.interval", defaultValue = "PT1M")
    Duration interval;

    private ScheduledExecutorService executor;

    /** Returns at once; the schedule is created or updated once Temporal answers. */
    void ensureInBackground(WorkflowServiceStubs service, String taskQueue) {
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "sweep-schedule");
            thread.setDaemon(true);
            return thread;
        });
        executor.execute(() -> tryOnce(ScheduleClient.newInstance(service), taskQueue));
    }

    void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void tryOnce(ScheduleClient client, String taskQueue) {
        try {
            createOrUpdate(client, taskQueue, interval);
            Log.infof("Sweep schedule '%s' is in place (every %s)", InvoiceSweepWorkflow.SCHEDULE_ID, interval);
        } catch (RuntimeException e) {
            Log.warnf("Could not set up the sweep schedule, retrying in %s: %s", RETRY_DELAY, e.getMessage());
            executor.schedule(() -> tryOnce(client, taskQueue), RETRY_DELAY.toSeconds(), TimeUnit.SECONDS);
        }
    }

    static void createOrUpdate(ScheduleClient client, String taskQueue, Duration interval) {
        var schedule = Schedule.newBuilder()
                .setAction(ScheduleActionStartWorkflow.newBuilder()
                        .setWorkflowType(InvoiceSweepWorkflow.class)
                        .setOptions(WorkflowOptions.newBuilder()
                                .setWorkflowId(InvoiceSweepWorkflow.SCHEDULE_ID)
                                .setTaskQueue(taskQueue)
                                .build())
                        .build())
                .setSpec(ScheduleSpec.newBuilder()
                        .setIntervals(List.of(new ScheduleIntervalSpec(interval)))
                        .build())
                // If a run is still going when the next one is due, skip it: the sweep is idempotent
                // and the following tick picks up whatever is left.
                .setPolicy(SchedulePolicy.newBuilder()
                        .setOverlap(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP)
                        .build())
                .build();
        try {
            client.createSchedule(InvoiceSweepWorkflow.SCHEDULE_ID, schedule, ScheduleOptions.newBuilder().build());
        } catch (ScheduleAlreadyRunningException e) {
            client.getHandle(InvoiceSweepWorkflow.SCHEDULE_ID).update(input -> new ScheduleUpdate(schedule));
        }
    }
}
