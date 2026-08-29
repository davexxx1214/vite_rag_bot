import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { FormEvent, KeyboardEvent as ReactKeyboardEvent } from "react";
import { createRoot } from "react-dom/client";
import DOMPurify from "dompurify";
import { marked } from "marked";
import {
  AppBar,
  Avatar,
  Box,
  Button,
  Chip,
  CircularProgress,
  CssBaseline,
  Divider,
  Fab,
  IconButton,
  Link,
  List,
  ListItemButton,
  ListItemText,
  Paper,
  Skeleton,
  TextField,
  ThemeProvider,
  Toolbar,
  Tooltip,
  Typography,
  createTheme,
} from "@mui/material";
import ArrowUpwardRoundedIcon from "@mui/icons-material/ArrowUpwardRounded";
import AutoAwesomeRoundedIcon from "@mui/icons-material/AutoAwesomeRounded";
import CloseRoundedIcon from "@mui/icons-material/CloseRounded";
import OpenInNewRoundedIcon from "@mui/icons-material/OpenInNewRounded";
import "./style.css";

type HelpDoc = {
  slug: string;
  title: string;
  summary: string;
  source: string;
  content: string;
};

type Source = {
  citation: string;
  title: string;
  section: string;
  source: string;
  score: number;
};

type ChatMessage = {
  id: string;
  role: "user" | "assistant";
  content: string;
  sources?: Source[];
};

type ChatStreamEvent =
  | { type: "status"; message: string }
  | { type: "sources"; sources: Source[] }
  | { type: "delta"; text: string }
  | { type: "error"; error: string }
  | { type: "done" };

const colors = {
  ink: "#17211a",
  muted: "#69736c",
  green: "#126c46",
  greenDark: "#0c5033",
  greenSoft: "#e7f1ea",
  canvas: "#f7f8f4",
};

const theme = createTheme({
  palette: {
    mode: "light",
    primary: { main: colors.greenDark },
    background: { default: colors.canvas, paper: "#ffffff" },
    text: { primary: colors.ink, secondary: colors.muted },
    divider: "#dfe4dd",
  },
  shape: { borderRadius: 12 },
  typography: {
    fontFamily:
      'Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
    button: { textTransform: "none", fontWeight: 700 },
  },
  components: {
    MuiButton: { defaultProps: { disableElevation: true } },
    MuiPaper: { styleOverrides: { root: { backgroundImage: "none" } } },
  },
});

marked.setOptions({ gfm: true, breaks: true });

function newId() {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }

  if (typeof crypto !== "undefined" && typeof crypto.getRandomValues === "function") {
    const values = crypto.getRandomValues(new Uint32Array(2));
    return `message-${Date.now()}-${values[0].toString(16)}${values[1].toString(16)}`;
  }

  return `message-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function SafeMarkdown({ content, variant }: { content: string; variant: "doc" | "message" }) {
  const rootRef = useRef<HTMLDivElement>(null);
  const html = useMemo(() => {
    const parsed = marked.parse(content, { async: false }) as string;
    return DOMPurify.sanitize(parsed, { USE_PROFILES: { html: true } });
  }, [content]);

  useEffect(() => {
    rootRef.current?.querySelectorAll("a").forEach((anchor) => {
      anchor.target = "_blank";
      anchor.rel = "noopener noreferrer";
    });
  }, [html]);

  return (
    <Box
      ref={rootRef}
      className={`markdown markdown-${variant}`}
      dangerouslySetInnerHTML={{ __html: html }}
    />
  );
}

const initialMessages: ChatMessage[] = [
  {
    id: newId(),
    role: "assistant",
    content:
      "Hi! I answer questions using the Help Center documents. Try asking: **What is the difference between paper and live trading?**",
  },
];

function App() {
  const [docs, setDocs] = useState<HelpDoc[]>([]);
  const [selectedSlug, setSelectedSlug] = useState("");
  const [docsError, setDocsError] = useState("");
  const [chatOpen, setChatOpen] = useState(false);
  const [messages, setMessages] = useState<ChatMessage[]>(initialMessages);
  const [input, setInput] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const activeRequest = useRef<AbortController | null>(null);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  const selectedDoc = useMemo(
    () => docs.find((doc) => doc.slug === selectedSlug) ?? docs[0],
    [docs, selectedSlug],
  );

  useEffect(() => {
    const controller = new AbortController();

    void fetch("/api/docs", { signal: controller.signal })
      .then(async (response) => {
        if (!response.ok) throw new Error("Unable to load help documents");
        return (await response.json()) as HelpDoc[];
      })
      .then((loadedDocs) => {
        setDocs(loadedDocs);
        setSelectedSlug(loadedDocs[0]?.slug ?? "");
      })
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === "AbortError") return;
        setDocsError(error instanceof Error ? error.message : "Unable to load help documents");
      });

    return () => controller.abort();
  }, []);

  useEffect(() => {
    if (chatOpen) messagesEndRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
  }, [chatOpen, messages]);

  useEffect(() => () => activeRequest.current?.abort(), []);

  const updateMessage = useCallback((id: string, patch: Partial<ChatMessage>) => {
    setMessages((current) =>
      current.map((message) => (message.id === id ? { ...message, ...patch } : message)),
    );
  }, []);

  const openChat = () => {
    setChatOpen(true);
    window.setTimeout(() => inputRef.current?.focus(), 80);
  };

  const closeChat = () => {
    activeRequest.current?.abort();
    setChatOpen(false);
  };

  const sendMessage = async (event?: FormEvent) => {
    event?.preventDefault();
    const content = input.trim();
    if (!content || submitting) return;

    const history = messages.slice(-6).map(({ role, content: messageContent }) => ({
      role,
      content: messageContent,
    }));
    const thinkingId = newId();
    setMessages((current) => [
      ...current,
      { id: newId(), role: "user", content },
      { id: thinkingId, role: "assistant", content: "Searching the help documents…" },
    ]);
    setInput("");
    setSubmitting(true);

    const controller = new AbortController();
    activeRequest.current = controller;
    let timedOut = false;
    const timeout = window.setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, 45_000);

    try {
      const response = await fetch("/api/chat", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message: content, history }),
        signal: controller.signal,
      });

      if (!response.ok) {
        const payload = (await response.json().catch(() => null)) as { error?: string } | null;
        throw new Error(payload?.error || "The bot could not answer");
      }
      if (!response.body) throw new Error("The server returned no response stream");

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      let answer = "";
      let receivedText = false;
      let streamDone = false;

      while (!streamDone) {
        const { done, value } = await reader.read();
        buffer += decoder.decode(value, { stream: !done });
        const lines = buffer.split("\n");
        buffer = lines.pop() ?? "";

        for (const line of lines) {
          if (!line.trim()) continue;
          const streamEvent = JSON.parse(line) as ChatStreamEvent;

          if (streamEvent.type === "status" && !receivedText) {
            updateMessage(thinkingId, { content: streamEvent.message });
          } else if (streamEvent.type === "sources") {
            updateMessage(thinkingId, { sources: streamEvent.sources });
          } else if (streamEvent.type === "delta") {
            receivedText = true;
            answer += streamEvent.text;
            updateMessage(thinkingId, { content: answer });
          } else if (streamEvent.type === "error") {
            throw new Error(streamEvent.error);
          } else if (streamEvent.type === "done") {
            streamDone = true;
          }
        }

        if (done) break;
      }

      if (!receivedText) throw new Error("The bot returned an empty answer");
    } catch (error) {
      if (error instanceof DOMException && error.name === "AbortError") {
        updateMessage(thinkingId, {
          content: timedOut ? "The request timed out. Please try again." : "The request was cancelled.",
        });
      } else {
        updateMessage(thinkingId, {
          content: error instanceof Error ? `Sorry, ${error.message}.` : "Sorry, something went wrong.",
        });
      }
    } finally {
      window.clearTimeout(timeout);
      if (activeRequest.current === controller) activeRequest.current = null;
      setSubmitting(false);
      window.setTimeout(() => inputRef.current?.focus(), 0);
    }
  };

  const handleInputKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      event.currentTarget.closest("form")?.requestSubmit();
    }
  };

  return (
    <Box sx={{ minWidth: 320, minHeight: "100vh" }}>
      <AppBar
        position="sticky"
        color="transparent"
        elevation={0}
        sx={{ borderBottom: 1, borderColor: "divider", bgcolor: "rgba(255,255,255,.92)", backdropFilter: "blur(14px)" }}
      >
        <Toolbar sx={{ minHeight: { xs: 64, sm: 72 }, px: { xs: 2.25, sm: 5.25 } }}>
          <Link href="#" underline="none" color="inherit" sx={{ display: "flex", alignItems: "center", gap: 1.5 }}>
            <Avatar variant="rounded" sx={{ width: 36, height: 36, bgcolor: colors.ink, fontFamily: "Georgia, serif", fontStyle: "italic", fontSize: 20 }}>
              A
            </Avatar>
            <Box>
              <Typography sx={{ fontSize: 14, fontWeight: 800, lineHeight: 1.2 }}>Trading API</Typography>
              <Typography sx={{ mt: 0.25, color: "text.secondary", fontSize: 10, letterSpacing: ".12em", textTransform: "uppercase" }}>
                Help Center
              </Typography>
            </Box>
          </Link>
          <Box sx={{ ml: "auto", display: { xs: "none", sm: "flex" }, alignItems: "center", gap: 1, color: "text.secondary" }}>
            <Box sx={{ width: 7, height: 7, borderRadius: "50%", bgcolor: "#23a66b", boxShadow: "0 0 0 3px #ddf4e8" }} />
            <Typography sx={{ fontSize: 12 }}>Local RAG demo</Typography>
          </Box>
        </Toolbar>
      </AppBar>

      <Box sx={{ minHeight: "calc(100vh - 72px)", display: { xs: "block", md: "grid" }, gridTemplateColumns: "280px minmax(0, 1fr)" }}>
        <Box
          component="aside"
          sx={{
            position: { xs: "static", md: "sticky" },
            top: 72,
            height: { xs: "auto", md: "calc(100vh - 72px)" },
            p: { xs: 2.25, md: "42px 26px 30px 42px" },
            borderRight: { md: 1 },
            borderBottom: { xs: 1, md: 0 },
            borderColor: "divider",
            bgcolor: "#f2f4ef",
            display: "flex",
            flexDirection: "column",
          }}
        >
          <Typography sx={{ mb: 1.5, color: "#89928b", fontSize: 10, fontWeight: 800, letterSpacing: ".14em", textTransform: "uppercase" }}>
            Documentation
          </Typography>
          <List aria-label="Help documents" disablePadding sx={{ display: { xs: "flex", md: "block" }, overflowX: { xs: "auto", md: "visible" }, gap: 0.5 }}>
            {docs.length === 0 && !docsError
              ? Array.from({ length: 4 }, (_, index) => <Skeleton key={index} height={43} sx={{ minWidth: { xs: 150, md: "auto" } }} />)
              : docs.map((doc, index) => (
                  <ListItemButton
                    key={doc.slug}
                    selected={selectedDoc?.slug === doc.slug}
                    onClick={() => {
                      setSelectedSlug(doc.slug);
                      window.scrollTo({ top: 0, behavior: "smooth" });
                    }}
                    sx={{
                      flex: { xs: "0 0 auto", md: "initial" },
                      minWidth: { xs: 190, md: 0 },
                      mb: { md: 0.5 },
                      px: 1.5,
                      py: 1,
                      borderRadius: 1.5,
                      color: "#4e5851",
                      "&.Mui-selected": { bgcolor: "white", color: colors.ink, boxShadow: "0 1px 4px rgba(26,39,30,.08)" },
                      "&.Mui-selected:hover": { bgcolor: "white" },
                    }}
                  >
                    <Typography sx={{ mr: 1.25, color: selectedDoc?.slug === doc.slug ? colors.green : "#a1aaa3", fontSize: 10, fontVariantNumeric: "tabular-nums" }}>
                      {String(index + 1).padStart(2, "0")}
                    </Typography>
                    <ListItemText
                      primary={doc.title}
                      slotProps={{
                        primary: {
                          noWrap: true,
                          sx: {
                            fontSize: 13,
                            fontWeight: selectedDoc?.slug === doc.slug ? 700 : 400,
                          },
                        },
                      }}
                    />
                  </ListItemButton>
                ))}
          </List>

          <Paper variant="outlined" sx={{ mt: "auto", p: 2.25, display: { xs: "none", md: "block" }, borderColor: "#d7dfd7", bgcolor: "#e8eee8" }}>
            <Typography sx={{ fontSize: 12, fontWeight: 800 }}>AI-powered help</Typography>
            <Typography sx={{ my: 1, color: "text.secondary", fontSize: 12, lineHeight: 1.5 }}>
              Ask questions grounded in these documents.
            </Typography>
            <Button fullWidth variant="contained" onClick={openChat} sx={{ bgcolor: colors.ink, fontSize: 12, "&:hover": { bgcolor: colors.greenDark } }}>
              Ask the AI bot
            </Button>
          </Paper>
        </Box>

        <Box component="main" sx={{ width: "min(840px, 100%)", p: { xs: "44px 22px 100px", md: "70px 70px 100px" } }}>
          {docsError ? (
            <Typography color="error" sx={{ fontSize: 14 }}>{docsError}</Typography>
          ) : !selectedDoc ? (
            <Box><Skeleton width="70%" height={72} /><Skeleton width="90%" /><Skeleton width="82%" /></Box>
          ) : (
            <Box component="article">
              <Typography sx={{ mb: 1.75, color: "#89928b", fontSize: 10, fontWeight: 800, letterSpacing: ".14em", textTransform: "uppercase" }}>
                Guide
              </Typography>
              <Typography component="h1" sx={{ m: 0, fontFamily: 'Georgia, "Times New Roman", serif', fontSize: "clamp(38px, 5vw, 58px)", fontWeight: 500, letterSpacing: "-.035em", lineHeight: 1.05 }}>
                {selectedDoc.title}
              </Typography>
              <Typography sx={{ maxWidth: 650, mt: 2.75, color: "text.secondary", fontSize: 17, lineHeight: 1.7 }}>
                {selectedDoc.summary}
              </Typography>
              <Divider sx={{ width: 42, my: 4.75, borderBottomWidth: 2, borderColor: colors.green }} />
              <SafeMarkdown content={selectedDoc.content} variant="doc" />
              <Box component="footer" sx={{ mt: 6.75, pt: 2.75, borderTop: 1, borderColor: "divider", display: "flex", flexDirection: { xs: "column", sm: "row" }, justifyContent: "space-between", gap: 2, color: "#89928b" }}>
                <Typography sx={{ fontSize: 11 }}>Adapted for this RAG demo</Typography>
                <Link href={selectedDoc.source} target="_blank" rel="noreferrer" underline="hover" sx={{ color: colors.green, fontSize: 11, fontWeight: 700, display: "inline-flex", alignItems: "center", gap: 0.5 }}>
                  View original Alpaca documentation <OpenInNewRoundedIcon sx={{ fontSize: 14 }} />
                </Link>
              </Box>
            </Box>
          )}
        </Box>
      </Box>

      {!chatOpen && (
        <Fab variant="extended" onClick={openChat} aria-label="Open AI help bot" sx={{ position: "fixed", zIndex: 1200, right: { xs: 18, sm: 28 }, bottom: { xs: 18, sm: 26 }, bgcolor: colors.ink, color: "white", "&:hover": { bgcolor: colors.greenDark } }}>
          <AutoAwesomeRoundedIcon sx={{ mr: 1 }} /> Ask AI
        </Fab>
      )}

      {chatOpen && (
        <Paper
          role="dialog"
          aria-label="AI help bot"
          elevation={16}
          sx={{
            position: "fixed",
            zIndex: 1300,
            right: { xs: 10, sm: 26 },
            bottom: { xs: 10, sm: 24 },
            width: { xs: "calc(100vw - 20px)", sm: 430 },
            height: { xs: "calc(100vh - 20px)", sm: "min(680px, calc(100vh - 40px))" },
            overflow: "hidden",
            border: 1,
            borderColor: "#d8dfd9",
            borderRadius: 2.25,
            display: "grid",
            gridTemplateRows: "auto 1fr auto auto",
          }}
        >
          <Box component="header" sx={{ minHeight: 76, p: 1.75, borderBottom: 1, borderColor: "divider", display: "flex", alignItems: "center", gap: 1.5 }}>
            <Avatar sx={{ width: 42, height: 42, bgcolor: colors.greenSoft, color: colors.greenDark }}>
              <AutoAwesomeRoundedIcon fontSize="small" />
            </Avatar>
            <Box sx={{ minWidth: 0, flex: 1 }}>
              <Typography sx={{ fontSize: 15, fontWeight: 800 }}>Help Bot</Typography>
              <Box sx={{ mt: 0.25, display: "flex", alignItems: "center", gap: 0.75, color: "text.secondary" }}>
                <Box sx={{ width: 6, height: 6, borderRadius: "50%", bgcolor: "#23a66b", boxShadow: "0 0 0 3px #ddf4e8" }} />
                <Typography sx={{ fontSize: 10 }}>Answers from local docs</Typography>
              </Box>
            </Box>
            <Tooltip title="Close">
              <IconButton onClick={closeChat} aria-label="Close chat"><CloseRoundedIcon /></IconButton>
            </Tooltip>
          </Box>

          <Box aria-live="polite" sx={{ overflowY: "auto", p: 2.25, bgcolor: "#f7f8f5" }}>
            {messages.map((message) => (
              <Box key={message.id} sx={{ mb: 2.5, display: "flex", flexDirection: "column", alignItems: message.role === "user" ? "flex-end" : "flex-start" }}>
                <Typography sx={{ mx: 0.5, mb: 0.75, color: "#8b948d", fontSize: 9, fontWeight: 800, letterSpacing: ".09em", textTransform: "uppercase" }}>
                  {message.role === "assistant" ? "Help Bot" : "You"}
                </Typography>
                <Paper
                  variant="outlined"
                  sx={{
                    maxWidth: "91%",
                    px: 1.75,
                    py: 1.25,
                    borderColor: message.role === "user" ? colors.greenDark : "#dde3dd",
                    borderRadius: message.role === "user" ? "14px 4px 14px 14px" : "4px 14px 14px 14px",
                    bgcolor: message.role === "user" ? colors.greenDark : "white",
                    color: message.role === "user" ? "white" : "#344038",
                  }}
                >
                  {message.role === "assistant" ? (
                    <SafeMarkdown content={message.content} variant="message" />
                  ) : (
                    <Typography sx={{ whiteSpace: "pre-wrap", fontSize: 13, lineHeight: 1.55 }}>{message.content}</Typography>
                  )}
                </Paper>
                {!!message.sources?.length && (
                  <Box sx={{ width: "91%", mt: 1.25, display: "flex", flexWrap: "wrap", gap: 0.75 }}>
                    <Typography sx={{ width: "100%", color: "#8a938c", fontSize: 9, fontWeight: 800, letterSpacing: ".08em", textTransform: "uppercase" }}>
                      Sources
                    </Typography>
                    {message.sources.map((source) => (
                      <Chip
                        key={`${message.id}-${source.citation}`}
                        component="a"
                        href={source.source}
                        target="_blank"
                        rel="noopener noreferrer"
                        clickable
                        size="small"
                        variant="outlined"
                        label={`[${source.citation}] ${source.title} — ${source.section}`}
                        deleteIcon={<OpenInNewRoundedIcon />}
                        onDelete={() => window.open(source.source, "_blank", "noopener,noreferrer")}
                        sx={{ maxWidth: "100%", height: "auto", bgcolor: "white", color: colors.green, borderColor: "#dbe2dc", "& .MuiChip-label": { display: "block", py: 0.65, whiteSpace: "normal", fontSize: 10 }, "& .MuiChip-deleteIcon": { fontSize: 14, color: colors.green } }}
                      />
                    ))}
                  </Box>
                )}
              </Box>
            ))}
            <div ref={messagesEndRef} />
          </Box>

          <Paper component="form" onSubmit={(event) => void sendMessage(event)} variant="outlined" sx={{ m: "13px 14px 8px", p: "6px 7px 6px 12px", borderColor: "#cfd7d0", borderRadius: 1.75, display: "flex", alignItems: "flex-end", gap: 1, "&:focus-within": { borderColor: "#88a895", boxShadow: "0 0 0 3px #edf4ef" } }}>
            <TextField
              inputRef={inputRef}
              value={input}
              onChange={(event) => setInput(event.target.value)}
              onKeyDown={handleInputKeyDown}
              placeholder="Ask about paper trading, authentication…"
              multiline
              maxRows={4}
              fullWidth
              variant="standard"
              slotProps={{ htmlInput: { maxLength: 2000 }, input: { disableUnderline: true } }}
              sx={{ "& textarea": { py: 0.8, fontSize: 12, lineHeight: 1.45 } }}
            />
            <IconButton type="submit" disabled={submitting || !input.trim()} aria-label="Send message" sx={{ width: 38, height: 38, flex: "0 0 auto", borderRadius: 1.25, bgcolor: colors.greenDark, color: "white", "&:hover": { bgcolor: colors.green }, "&.Mui-disabled": { bgcolor: "#b7c1ba", color: "white" } }}>
              {submitting ? <CircularProgress size={17} color="inherit" /> : <ArrowUpwardRoundedIcon fontSize="small" />}
            </IconButton>
          </Paper>
          <Typography sx={{ mx: 1.75, mb: 1.5, color: "#9aa29c", fontSize: 9, textAlign: "center" }}>
            Demo answers may be incomplete. Verify details in the source docs.
          </Typography>
        </Paper>
      )}
    </Box>
  );
}

createRoot(document.getElementById("app")!).render(
  <ThemeProvider theme={theme}>
    <CssBaseline />
    <App />
  </ThemeProvider>,
);
