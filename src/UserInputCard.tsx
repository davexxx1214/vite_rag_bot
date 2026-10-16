import { useEffect, useState } from "react";
import { Box, Button, FormControlLabel, Paper, Radio, RadioGroup, TextField, Typography } from "@mui/material";
import { canAnswer, makeInputAnswer } from "./inputRequest";
import type { InputAnswer, InputRequest } from "./inputRequest";

export function UserInputCard({ request, busy, onAnswer }: {
  request: InputRequest; busy: boolean; onAnswer: (answer: InputAnswer, display: string) => Promise<void>;
}) {
  const [selected, setSelected] = useState("");
  const [text, setText] = useState("");
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const timer = window.setTimeout(() => setNow(Date.now()), Math.max(0, Date.parse(request.expiresAt) - Date.now()) + 1);
    return () => window.clearTimeout(timer);
  }, [request.expiresAt]);
  const actionable = canAnswer(request, now);
  const answer = makeInputAnswer(request, selected, text);
  const status = request.status === "answered" ? "已提交回答" : request.status === "cancelled" ? "已取消"
    : actionable ? "等待你的选择" : "问题已过期，请重新发起请求";
  if (request.status === "answered" || request.status === "cancelled") return <Paper component="details" variant="outlined"
    sx={{ p: 1.25, mb: 2, borderColor: "var(--chat-border)", borderRadius: "14px", bgcolor: "#fafbf9" }}>
    <Typography component="summary" sx={{ fontSize: 11, color: "text.secondary", cursor: "pointer", fontWeight: 600 }}>
      {status} · 查看原问题
    </Typography>
    <Typography sx={{ mt: 1, fontSize: 12, overflowWrap: "anywhere" }}>{request.question}</Typography>
  </Paper>;
  return <Paper
    variant="outlined"
    sx={{
      mb: 2,
      overflow: "hidden",
      borderColor: actionable ? "rgba(18, 108, 70, 0.36)" : "var(--chat-border)",
      borderRadius: "16px",
      bgcolor: "white",
      boxShadow: actionable ? "0 4px 16px rgba(18, 108, 70, 0.08)" : "none",
    }}
  >
    <Box sx={{
      px: 1.75, py: 1, borderBottom: 1, borderColor: "rgba(33, 53, 40, 0.08)",
      bgcolor: "#f7faf7", display: "flex", alignItems: "center", justifyContent: "space-between", gap: 1,
    }}>
      <Box sx={{ display: "inline-flex", alignItems: "center", gap: 0.85 }}>
        <Box sx={{
          width: 6, height: 6, borderRadius: "50%",
          bgcolor: actionable ? "primary.main" : "#9aa39c",
          boxShadow: actionable ? "0 0 0 3px #ddf4e8" : "none",
        }} />
        <Typography sx={{ fontSize: 11, fontWeight: 700, color: actionable ? "primary.main" : "text.secondary" }}>
          {status}
        </Typography>
      </Box>
      <Typography sx={{ fontSize: 10, color: "text.secondary", fontFamily: '"SFMono-Regular", Consolas, monospace' }}>
        {request.reason === "clarification" ? "CLARIFY" : request.reason === "unknown_outcome" ? "VERIFY" : "CONFIRM"}
      </Typography>
    </Box>
    <Box sx={{ p: 1.75 }}>
      <Typography sx={{ fontSize: 13, fontWeight: 700, lineHeight: 1.5, overflowWrap: "anywhere" }}>
        {request.question}
      </Typography>
    {actionable && <Box component="form" onSubmit={event => {
      event.preventDefault();
      if (!answer || busy) return;
      const label = answer.text ?? request.options.find(option => option.id === answer.optionId)?.label ?? "回答问题";
      void onAnswer(answer, label);
    }}>
      <RadioGroup aria-label="纠错选项" value={selected} onChange={event => setSelected(event.target.value)} sx={{ mt: 1.25, gap: 0.75 }}>
        {request.options.map(option => <FormControlLabel key={option.id} value={option.id}
          disabled={busy}
          sx={{
            m: 0,
            px: 1,
            py: 0.65,
            borderRadius: "10px",
            border: 1,
            borderColor: selected === option.id ? "rgba(18, 108, 70, 0.42)" : "rgba(33, 53, 40, 0.1)",
            bgcolor: selected === option.id ? "rgba(231, 241, 234, 0.55)" : "#fbfcfa",
            transition: "border-color 140ms ease, background-color 140ms ease",
            "&:hover": { bgcolor: selected === option.id ? "rgba(231, 241, 234, 0.7)" : "#f3f6f3" },
          }}
          control={<Radio size="small" sx={{ p: 0.5, mr: 0.5 }} />}
          label={<Box sx={{ minWidth: 0 }}>
            <Typography sx={{ fontSize: 13, fontWeight: selected === option.id ? 700 : 500, overflowWrap: "anywhere" }}>
              {option.label}
            </Typography>
            {!!option.description && <Typography sx={{ fontSize: 11, color: "text.secondary" }}>{option.description}</Typography>}
          </Box>} />)}
        {request.allowFreeText && <FormControlLabel
          value="custom"
          disabled={busy}
          sx={{
            m: 0,
            px: 1,
            py: 0.55,
            borderRadius: "10px",
            border: 1,
            borderColor: selected === "custom" ? "rgba(18, 108, 70, 0.42)" : "rgba(33, 53, 40, 0.1)",
            bgcolor: selected === "custom" ? "rgba(231, 241, 234, 0.55)" : "#fbfcfa",
          }}
          control={<Radio size="small" sx={{ p: 0.5, mr: 0.5 }} />}
          label={<Typography sx={{ fontSize: 13, fontWeight: selected === "custom" ? 700 : 500 }}>自行输入</Typography>}
        />}
      </RadioGroup>
      {request.allowFreeText && <TextField fullWidth size="small" label="你的回答" value={text} disabled={busy}
        onFocus={() => setSelected("custom")} onChange={event => { setText(event.target.value); setSelected("custom"); }}
        slotProps={{ htmlInput: { maxLength: 500 } }} sx={{ mt: 1.25, "& .MuiOutlinedInput-root": { borderRadius: "10px" } }} />}
      <Box sx={{ display: "flex", gap: 1, flexWrap: "wrap", mt: 1.5, pt: 1.25, borderTop: 1, borderColor: "rgba(33, 53, 40, 0.08)" }}>
        <Button type="submit" size="small" variant="contained" disabled={busy || !answer}
          sx={{ borderRadius: "8px", "&:active": { transform: "scale(0.97)" } }}>
          {request.reason === "clarification" ? "提交并继续" : request.reason === "unknown_outcome" ? "核对结果" : "提交并继续修改"}
        </Button>
        <Button size="small" color="inherit" disabled={busy} onClick={() => void onAnswer({ cancelled: true }, "取消本次请求")}
          sx={{ color: "text.secondary", borderRadius: "8px" }}>
          {request.reason === "unknown_outcome" ? "暂不核对" : "取消本次请求"}
        </Button>
      </Box>
    </Box>}
    </Box>
  </Paper>;
}
