import { useEffect, useState } from "react";
import { Box, CircularProgress, Typography } from "@mui/material";
import CheckCircleOutlineRoundedIcon from "@mui/icons-material/CheckCircleOutlineRounded";
import ErrorOutlineRoundedIcon from "@mui/icons-material/ErrorOutlineRounded";
import StopCircleOutlinedIcon from "@mui/icons-material/StopCircleOutlined";
import type { Activity } from "./agentActivity";

const duration = (ms: number) => `${(Math.max(0, ms) / 1000).toFixed(1)}s`;
const labels = { running: "进行中", completed: "完成", failed: "失败", cancelled: "已停止" };

export function AgentActivityPanel({ activities, pending, status, startedAt, endedAt }:
  { activities: Activity[]; pending?: boolean; status?: string; startedAt?: number; endedAt?: number }) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    if (!pending) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [pending]);
  const running = [...activities].reverse().find(activity => activity.state === "running");
  const toolCalls = activities.filter(activity => activity.kind === "tool").length;
  const failures = activities.filter(activity => activity.state === "failed").length;
  return <Box sx={{ width: "91%", mb: 1, border: 1, borderColor: "#dde3dd", borderRadius: 1.5, bgcolor: "#f1f5f1", p: 1.25 }}>
    {pending && <Box role="status" sx={{ display: "flex", gap: 1, alignItems: "center", mb: activities.length ? 1 : 0 }}>
      <CircularProgress size={13} sx={{ flexShrink: 0 }} />
      <Box sx={{ minWidth: 0 }}>
        <Typography sx={{ fontSize: 12, overflowWrap: "anywhere" }}>{running?.message || status || "正在处理请求…"}</Typography>
        {(running?.name || running?.detail) && <Typography sx={{ fontSize: 10, color: "text.secondary", overflowWrap: "anywhere" }}>
          {[running.name, running.detail].filter(Boolean).join(" · ")}
        </Typography>}
      </Box>
    </Box>}
    {!!activities.length && <Box component="details">
      <Box component="summary" sx={{ cursor: "pointer", fontSize: 11, color: "text.secondary" }}>
        执行记录 · {toolCalls} 次工具调用
        {!!failures && ` · ${failures} 个步骤失败`}
        {startedAt !== undefined && ` · ${duration((endedAt ?? now) - startedAt)}`}
      </Box>
      <Box component="ol" sx={{ listStyle: "none", p: 0, m: 0, mt: 1 }}>
        {activities.map(activity => <Box component="li" key={activity.id} sx={{ display: "flex", alignItems: "flex-start", gap: 0.8, py: 0.65 }}>
          {activity.state === "running" ? <CircularProgress size={13} sx={{ mt: 0.3, flexShrink: 0 }} />
            : activity.state === "completed" ? <CheckCircleOutlineRoundedIcon sx={{ fontSize: 16, color: "primary.main" }} />
            : activity.state === "failed" ? <ErrorOutlineRoundedIcon sx={{ fontSize: 16, color: "error.main" }} />
            : <StopCircleOutlinedIcon sx={{ fontSize: 16, color: "text.secondary" }} />}
          <Box sx={{ minWidth: 0, flex: 1 }}>
            <Typography sx={{ fontSize: 11, overflowWrap: "anywhere" }}>{activity.message.replace(/…$/, "")}</Typography>
            {(activity.name || activity.detail) && <Typography sx={{ fontSize: 10, color: "text.secondary", overflowWrap: "anywhere", whiteSpace: "pre-wrap" }}>
              {[activity.name, activity.detail].filter(Boolean).join(" · ")}
            </Typography>}
          </Box>
          <Typography sx={{ fontSize: 10, whiteSpace: "nowrap", color: activity.state === "failed" ? "error.main" : "text.secondary" }}>
            {labels[activity.state]} · {duration(activity.elapsedMs ?? (now - activity.startedAt))}
          </Typography>
        </Box>)}
      </Box>
    </Box>}
  </Box>;
}
