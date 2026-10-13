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
    sx={{ p: 1.25, mb: 2, borderColor: "#dfe4dd", borderRadius: 2 }}>
    <Typography component="summary" sx={{ fontSize: 11, color: "text.secondary", cursor: "pointer" }}>{status} · 查看原问题</Typography>
    <Typography sx={{ mt: 1, fontSize: 12, overflowWrap: "anywhere" }}>{request.question}</Typography>
  </Paper>;
  return <Paper variant="outlined" sx={{ p: 1.75, mb: 2, borderColor: "#88a895", borderRadius: 2 }}>
    <Typography sx={{ fontSize: 11, color: "text.secondary", mb: 0.75 }}>{status}</Typography>
    <Typography sx={{ fontSize: 13, fontWeight: 700, overflowWrap: "anywhere" }}>{request.question}</Typography>
    {actionable && <Box component="form" onSubmit={event => {
      event.preventDefault();
      if (!answer || busy) return;
      const label = answer.text ?? request.options.find(option => option.id === answer.optionId)?.label ?? "回答问题";
      void onAnswer(answer, label);
    }}>
      <RadioGroup aria-label="纠错选项" value={selected} onChange={event => setSelected(event.target.value)} sx={{ mt: 1 }}>
        {request.options.map(option => <FormControlLabel key={option.id} value={option.id}
          disabled={busy} control={<Radio size="small" />} label={<Box>
            <Typography sx={{ fontSize: 13, overflowWrap: "anywhere" }}>{option.label}</Typography>
            {!!option.description && <Typography sx={{ fontSize: 11, color: "text.secondary" }}>{option.description}</Typography>}
          </Box>} />)}
        {request.allowFreeText && <FormControlLabel value="custom" disabled={busy} control={<Radio size="small" />} label="自行输入" />}
      </RadioGroup>
      {request.allowFreeText && <TextField fullWidth size="small" label="你的回答" value={text} disabled={busy}
        onFocus={() => setSelected("custom")} onChange={event => { setText(event.target.value); setSelected("custom"); }}
        slotProps={{ htmlInput: { maxLength: 500 } }} sx={{ mt: 1 }} />}
      <Box sx={{ display: "flex", gap: 1, flexWrap: "wrap", mt: 1.5 }}>
        <Button type="submit" size="small" variant="contained" disabled={busy || !answer}>
          {request.reason === "clarification" ? "提交并继续" : request.reason === "unknown_outcome" ? "核对结果" : "提交并继续修改"}
        </Button>
        <Button size="small" disabled={busy} onClick={() => void onAnswer({ cancelled: true }, "取消本次请求")}>
          {request.reason === "unknown_outcome" ? "暂不核对" : "取消本次请求"}
        </Button>
      </Box>
    </Box>}
  </Paper>;
}
