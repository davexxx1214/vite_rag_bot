export type Activity = {
  id: string;
  kind: "tool" | "phase";
  name?: string;
  message: string;
  detail?: string;
  state: "running" | "completed" | "failed" | "cancelled";
  startedAt: number;
  endedAt?: number;
  elapsedMs?: number;
};

export type ActivityEvent =
  | { type: "activity_start" | "tool_start"; id: string; name?: string; message: string; detail?: string }
  | { type: "activity_update"; id: string; message: string }
  | { type: "activity_end" | "tool_end"; id: string; ok: boolean; elapsedMs: number };

// Identify each invocation separately: a retry of the same tool is a new step.
export function applyActivityEvent(activities: Activity[], event: ActivityEvent, now: number): Activity[] {
  if (event.type === "activity_start" || event.type === "tool_start") {
    if (activities.some(activity => activity.id === event.id)) return activities;
    return [...activities, {
      id: event.id, kind: event.type === "tool_start" ? "tool" : "phase",
      name: event.name, message: event.message, detail: event.detail,
      state: "running", startedAt: now,
    }];
  }
  if (event.type === "activity_update") {
    return activities.map(activity => activity.id === event.id && activity.state === "running"
      ? { ...activity, message: event.message } : activity);
  }
  if (event.type === "activity_end" || event.type === "tool_end") {
    return activities.map(activity => activity.id === event.id && activity.state === "running"
      ? { ...activity, state: event.ok ? "completed" : "failed", endedAt: now,
        elapsedMs: Math.max(0, event.elapsedMs) } : activity);
  }
  return activities;
}

export function finishActivities(activities: Activity[], state: "completed" | "failed" | "cancelled", now: number): Activity[] {
  return activities.map(activity => activity.state === "running"
    ? { ...activity, state, endedAt: now, elapsedMs: Math.max(0, now - activity.startedAt) } : activity);
}
