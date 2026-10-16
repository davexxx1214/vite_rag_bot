import { useEffect, useState } from "react";
import { Box, Typography } from "@mui/material";
import CheckCircleOutlineRoundedIcon from "@mui/icons-material/CheckCircleOutlineRounded";
import ErrorOutlineRoundedIcon from "@mui/icons-material/ErrorOutlineRounded";
import KeyboardArrowDownRoundedIcon from "@mui/icons-material/KeyboardArrowDownRounded";
import StopCircleOutlinedIcon from "@mui/icons-material/StopCircleOutlined";
import type { Activity } from "./agentActivity";
import { ThinkingDots } from "./ThinkingDots";

const duration = (ms: number) => `${(Math.max(0, ms) / 1000).toFixed(1)}s`;
const labels = { running: "进行中", completed: "完成", failed: "失败", cancelled: "已停止" };

export function AgentActivityPanel({ activities, pending, partial, status, startedAt, endedAt }:
  { activities: Activity[]; pending?: boolean; partial?: boolean; status?: string; startedAt?: number; endedAt?: number }) {
  const [now, setNow] = useState(Date.now());
  const [expanded, setExpanded] = useState(false);
  useEffect(() => {
    if (!pending) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [pending]);
  const running = [...activities].reverse().find(activity => activity.state === "running");
  const toolCalls = activities.filter(activity => activity.kind === "tool").length;
  const failures = activities.filter(activity => activity.state === "failed").length;
  // Keep open while live or when failures occur so streaming steps never jump unexpectedly.
  const isOpen = !!pending || expanded || failures > 0;
  return <Box
    className="cds-activity-panel"
    sx={{
      width: "100%",
      mb: 1.25,
      border: 1,
      borderColor: pending ? "rgba(18, 108, 70, 0.24)" : "var(--chat-border)",
      borderRadius: "12px",
      bgcolor: pending ? "rgba(231, 241, 234, 0.55)" : "rgba(241, 245, 241, 0.65)",
      px: 1.5,
      py: 1,
      transition: "border-color 180ms ease, background-color 180ms ease",
    }}
  >
    {partial && !pending && <Typography role="status" sx={{ fontSize: 11, color: "warning.dark", mb: activities.length ? 0.75 : 0 }}>
      任务未全部完成，已保留现有结果，可以继续提问。
    </Typography>}
    {pending && <Box role="status" sx={{ display: "flex", gap: 1, alignItems: "center", mb: activities.length ? 0.75 : 0 }}>
      <ThinkingDots />
      <Box sx={{ minWidth: 0 }}>
        <Typography sx={{ fontSize: 12, fontWeight: 600, color: "#1d3528", overflowWrap: "anywhere" }}>
          {running?.message || status || "正在处理请求…"}
        </Typography>
        {(running?.name || running?.detail) && <Typography sx={{ fontSize: 10, color: "text.secondary", overflowWrap: "anywhere" }}>
          {[running.name, running.detail].filter(Boolean).join(" · ")}
        </Typography>}
      </Box>
    </Box>}
    {!!activities.length && <Box>
      <Box
        component="button"
        type="button"
        aria-expanded={isOpen}
        disabled={!!pending}
        onClick={() => { if (!pending) setExpanded(prev => !prev); }}
        sx={{
          width: "100%",
          p: 0,
          border: 0,
          bgcolor: "transparent",
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          gap: 1,
          cursor: pending ? "default" : "pointer",
          color: "text.secondary",
          textAlign: "left",
          fontFamily: "inherit",
          fontSize: 11,
          fontVariantNumeric: "tabular-nums",
          "&:hover": pending ? undefined : { color: "text.primary" },
        }}
      >
        <Box component="span" sx={{ display: "inline-flex", alignItems: "center", gap: 0.85, minWidth: 0 }}>
          <Box component="span" sx={{
            width: 6, height: 6, borderRadius: "50%", flexShrink: 0,
            bgcolor: pending ? "primary.main" : failures ? "error.main" : "#88968c",
          }} />
          <Box component="span" sx={{ overflowWrap: "anywhere" }}>
            执行记录 · {toolCalls} 次工具调用
            {!!failures && ` · ${failures} 个步骤失败`}
            {startedAt !== undefined && ` · ${duration((endedAt ?? now) - startedAt)}`}
          </Box>
        </Box>
        {!pending && <KeyboardArrowDownRoundedIcon sx={{
          fontSize: 16,
          flexShrink: 0,
          transition: "transform 200ms cubic-bezier(0.19, 1, 0.22, 1)",
          transform: isOpen ? "rotate(180deg)" : "rotate(0deg)",
        }} />}
      </Box>
      <Box sx={{
        display: "grid",
        gridTemplateRows: isOpen ? "1fr" : "0fr",
        transition: "grid-template-rows 200ms cubic-bezier(0.19, 1, 0.22, 1)",
      }}>
        <Box sx={{ minHeight: 0, overflow: "hidden" }}>
          <Box component="ol" sx={{
            listStyle: "none", p: 0, m: 0, mt: 1, pl: 1.35, ml: 0.3,
            borderLeft: "2px solid rgba(18, 108, 70, 0.22)",
          }}>
            {activities.map(activity => <Box component="li" key={activity.id} sx={{ display: "flex", alignItems: "flex-start", gap: 0.8, py: 0.55 }}>
              {activity.state === "running" ? <ThinkingDots />
                : activity.state === "completed" ? <CheckCircleOutlineRoundedIcon sx={{ fontSize: 15, mt: 0.1, color: "primary.main" }} />
                : activity.state === "failed" ? <ErrorOutlineRoundedIcon sx={{ fontSize: 15, mt: 0.1, color: "error.main" }} />
                : <StopCircleOutlinedIcon sx={{ fontSize: 15, mt: 0.1, color: "text.secondary" }} />}
              <Box sx={{ minWidth: 0, flex: 1 }}>
                <Typography sx={{ fontSize: 11, fontWeight: activity.state === "running" ? 600 : 400, overflowWrap: "anywhere" }}>
                  {activity.message.replace(/…$/, "")}
                </Typography>
                {(activity.name || activity.detail) && <Typography sx={{ fontSize: 10, color: "text.secondary", fontFamily: '"SFMono-Regular", Consolas, monospace', overflowWrap: "anywhere", whiteSpace: "pre-wrap" }}>
                  {[activity.name, activity.detail].filter(Boolean).join(" · ")}
                </Typography>}
              </Box>
              <Typography sx={{ fontSize: 10, whiteSpace: "nowrap", fontVariantNumeric: "tabular-nums", color: activity.state === "failed" ? "error.main" : "text.secondary" }}>
                {labels[activity.state]} · {duration(activity.elapsedMs ?? (now - activity.startedAt))}
              </Typography>
            </Box>)}
          </Box>
        </Box>
      </Box>
    </Box>}
  </Box>;
}
