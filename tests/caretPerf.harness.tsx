import { createRoot } from "react-dom/client";
import { useRef, useState } from "react";
import { Box, TextField } from "@mui/material";
import { ThemeProvider, createTheme, CssBaseline } from "@mui/material";
import { MainChatInputCaret, useMainChatCaretMode } from "../src/AnimatedCaret";

function Harness() {
  const [value, setValue] = useState(() => {
    const fill = new URLSearchParams(window.location.search).get("fill");
    if (fill === "2000") return "x".repeat(2000);
    return "";
  });
  const hostRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const caretMode = useMainChatCaretMode();

  return (
    <ThemeProvider theme={createTheme()}>
      <CssBaseline />
      <Box sx={{ p: 3, maxWidth: 430 }}>
        <Box ref={hostRef} sx={{ position: "relative", border: "1px solid #cfd7d0", borderRadius: 2, p: 1 }}>
          <TextField
            inputRef={textareaRef}
            value={value}
            onChange={(e) => setValue(e.target.value)}
            multiline
            maxRows={4}
            fullWidth
            variant="standard"
            slotProps={{ htmlInput: { maxLength: 2000, "data-perf-input": "1" }, input: { disableUnderline: true } }}
            sx={{
              "& textarea:not([aria-hidden='true'])": {
                py: 0.8,
                fontSize: 12,
                lineHeight: 1.45,
                ...(caretMode === "animated" ? { caretColor: "transparent" } : {}),
              },
            }}
          />
          <MainChatInputCaret hostRef={hostRef} textareaRef={textareaRef} enabled />
        </Box>
      </Box>
    </ThemeProvider>
  );
}

const root = createRoot(document.body.appendChild(document.createElement("div")));
root.render(<Harness />);

declare global {
  interface Window {
    runCaretTypingBenchmark?: (samples?: number) => Promise<{ p50: number; p95: number; n: number }>;
  }
}

window.runCaretTypingBenchmark = async (samples = 80) => {
  const textarea = document.querySelector<HTMLTextAreaElement>("textarea[data-perf-input]");
  if (!textarea) throw new Error("Perf textarea not found");
  textarea.focus();
  const latencies: number[] = [];

  for (let i = 0; i < samples; i++) {
    const start = performance.now();
    const key = i % 2 === 0 ? "a" : "Backspace";
    textarea.dispatchEvent(new KeyboardEvent("keydown", { key, bubbles: true, cancelable: true }));
    if (key === "a") {
      const next = textarea.value.length < 2000 ? textarea.value + "a" : textarea.value;
      textarea.value = next;
      textarea.setSelectionRange(next.length, next.length);
      textarea.dispatchEvent(new Event("input", { bubbles: true }));
    } else if (textarea.value.length) {
      const next = textarea.value.slice(0, -1);
      textarea.value = next;
      textarea.setSelectionRange(next.length, next.length);
      textarea.dispatchEvent(new Event("input", { bubbles: true }));
    }
    await new Promise<void>((resolve) => {
      requestAnimationFrame(() => requestAnimationFrame(() => {
        latencies.push(performance.now() - start);
        resolve();
      }));
    });
  }

  latencies.sort((a, b) => a - b);
  const at = (p: number) => latencies[Math.min(latencies.length - 1, Math.floor(p * latencies.length))] ?? 0;
  return { p50: at(0.5), p95: at(0.95), n: latencies.length };
};

document.body.setAttribute("data-caret-perf-ready", "1");
