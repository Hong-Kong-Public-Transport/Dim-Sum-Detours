package com.dimsumdetours.sim.model;

/**
 * Phase-8 task 7: event broadcast on the milestone SSE stream when a milestone newly flips
 * from locked to unlocked. Frontend listens and pops a celebratory toast.
 */
public record MilestoneEvent(Milestone milestone, long gameMinutes) {
}

