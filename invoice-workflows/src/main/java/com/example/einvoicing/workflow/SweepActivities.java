package com.example.einvoicing.workflow;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface SweepActivities {

    /**
     * Starts the workflow of every unfinished invoice that has no running workflow. Safe to repeat:
     * an invoice that already has a running workflow is left alone.
     *
     * @return how many workflows were started
     */
    int restartStuckInvoices();
}
