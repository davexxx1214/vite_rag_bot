import { lazy, Suspense, useEffect, useMemo, useRef, useState } from "react";
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
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
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
import { useAgentChat } from "./useAgentChat";
import { AgentActivityPanel } from "./AgentActivityPanel";
import { canVisualize } from "./visualAnswer";

function UnavailableVisualAnswer({ onClose }: { onClose: () => void }) {
  return <Dialog open onClose={onClose} fullWidth maxWidth="sm">
    <DialogTitle>可视化回答</DialogTitle>
    <DialogContent>预览组件暂时无法加载，完整文字回答仍保留在聊天中。</DialogContent>
    <DialogActions><Button onClick={onClose}>返回聊天</Button></DialogActions>
  </Dialog>;
}
const VisualAnswerDialog = lazy(() => import("./VisualAnswerDialog")
  .catch(() => ({ default: UnavailableVisualAnswer })));

type HelpDoc = {
  slug: string;
  title: string;
  summary: string;
  source: string;
  content: string;
};

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

function App() {
  const [docs, setDocs] = useState<HelpDoc[]>([]);
  const [selectedSlug, setSelectedSlug] = useState("");
  const [docsError, setDocsError] = useState("");
  const [chatOpen, setChatOpen] = useState(false);
  const chat = useAgentChat();
  const { messages, submitting } = chat;
  const [visualMessage, setVisualMessage] = useState<string | null>(null);
  const visualIndex = messages.findIndex(message => message.id === visualMessage);
  const selectedVisual = visualIndex >= 0 ? messages[visualIndex] : undefined;
  useEffect(() => setVisualMessage(null), [chat.userId, chat.sessionId]);
  const [input, setInput] = useState("");
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

  const openChat = () => {
    setChatOpen(true);
    window.setTimeout(() => inputRef.current?.focus(), 80);
  };

  const closeChat = () => {
    chat.cancel();
    setVisualMessage(null);
    setChatOpen(false);
  };

  const sendMessage = async (event?: FormEvent) => {
    event?.preventDefault();
    const content = input.trim();
    if (!content || submitting || chat.loading || !chat.sessionId) return;
    setInput("");
    await chat.send(content);
    window.setTimeout(() => inputRef.current?.focus(), 0);
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
            gridTemplateColumns: "minmax(0, 1fr)",
            gridTemplateRows: "auto auto 1fr auto auto",
          }}
        >
          <Box component="header" sx={{ minHeight: 76, p: 1.75, borderBottom: 1, borderColor: "divider", display: "flex", alignItems: "center", gap: 1.5 }}>
            <Avatar sx={{ width: 42, height: 42, bgcolor: colors.greenSoft, color: colors.greenDark }}>
              <AutoAwesomeRoundedIcon fontSize="small" />
            </Avatar>
            <Box sx={{ minWidth: 0, flex: 1 }}>
              <Typography sx={{ fontSize: 15, fontWeight: 800 }}>Finance Assistant</Typography>
              <Box sx={{ mt: 0.25, display: "flex", alignItems: "center", gap: 0.75, color: "text.secondary" }}>
                <Box sx={{ width: 6, height: 6, borderRadius: "50%", bgcolor: "#23a66b", boxShadow: "0 0 0 3px #ddf4e8" }} />
                <Typography sx={{ fontSize: 10 }}>Help docs, portfolio data & saved preferences</Typography>
              </Box>
            </Box>
            <Tooltip title="Close">
              <IconButton onClick={closeChat} aria-label="Close chat"><CloseRoundedIcon /></IconButton>
            </Tooltip>
          </Box>

          <Box sx={{ px: 1.75, py: 1.25, borderBottom: 1, borderColor: "divider" }}>
            <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
              <TextField select size="small" label="Demo user" value={chat.users.length ? chat.userId : ""}
                disabled={submitting || chat.memoryBusy} sx={{ flex: 1, minWidth: 0 }}
                onChange={event => { setInput(""); chat.setUserId(event.target.value); }}>
                {chat.users.map(user => <MenuItem key={user.id} value={user.id}>{user.username}</MenuItem>)}
              </TextField>
              <Button size="small" disabled={submitting || chat.loading} onClick={() => void chat.selectSession()}>New chat</Button>
              <Button size="small" disabled={submitting || chat.loading} onClick={() => void chat.openMemory()}>Memory</Button>
            </Box>
            {chat.sessions.length > 1 && <TextField select fullWidth size="small" label="Conversation"
              value={chat.sessionId} disabled={submitting || chat.loading} sx={{ mt: 1.25 }}
              onChange={event => void chat.selectSession(event.target.value)}>
              {chat.sessions.map(session => <MenuItem key={session.id} value={session.id}>{session.title}</MenuItem>)}
            </TextField>}
            {chat.error && <Typography role="alert" color="error" sx={{ mt: 1, fontSize: 12 }}>{chat.error}</Typography>}
            {chat.loading && <Typography sx={{ mt: 1, fontSize: 11, color: "text.secondary" }}>Loading conversation…</Typography>}
            <Box sx={{ display: "flex", flexWrap: "wrap", gap: 1, mt: 0.5 }}>
              {chat.sessions.length >= 50 && <Button size="small" disabled={chat.loading || submitting} onClick={() => void chat.loadMoreSessions()}>More conversations</Button>}
              <Button size="small" disabled={!chat.sessionId || submitting || chat.loading} onClick={() => void chat.exportSession()}>Export chat</Button>
              <Button size="small" color="error" disabled={!chat.sessionId || submitting || chat.loading} onClick={() => void chat.deleteSession()}>Delete chat</Button>
            </Box>
            {chat.storage && chat.storage.bytes >= chat.storage.quotaBytes * 0.9 && <Typography role="alert" color="error" sx={{ fontSize: 12 }}>
              History storage is nearly full. Export and delete old conversations or increase the storage quota.
            </Typography>}
          </Box>

          <Box aria-live="polite" sx={{ overflowY: "auto", p: 2.25, bgcolor: "#f7f8f5" }}>
            {chat.historyOffset > 0 && <Button fullWidth size="small" disabled={chat.historyBusy || submitting}
              onClick={() => void chat.loadEarlier()}>{chat.historyBusy ? "Loading…" : "Load earlier messages"}</Button>}
            {messages.map((message) => (
              <Box key={message.id} sx={{ mb: 2.5, display: "flex", flexDirection: "column", alignItems: message.role === "user" ? "flex-end" : "flex-start" }}>
                <Typography sx={{ mx: 0.5, mb: 0.75, color: "#8b948d", fontSize: 9, fontWeight: 800, letterSpacing: ".09em", textTransform: "uppercase" }}>
                  {message.role === "assistant" ? "Help Bot" : "You"}
                </Typography>
                {message.role === "assistant" && (message.pending || message.partial || !!message.activities?.length) &&
                  <AgentActivityPanel activities={message.activities || []} pending={message.pending}
                    partial={message.partial} status={message.status} startedAt={message.startedAt} endedAt={message.endedAt} />}
                <Paper
                  variant="outlined"
                  sx={{
                    display: message.pending && !message.content ? "none" : undefined,
                    maxWidth: "91%",
                    minWidth: 0,
                    overflowX: "auto",
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
                {message.role === "assistant" && canVisualize(message.content, message.pending) && (
                  <Button size="small" startIcon={<AutoAwesomeRoundedIcon />} onClick={() => setVisualMessage(message.id)}
                    sx={{ mt: 0.75, fontSize: 11, color: colors.greenDark }}>查看可视化</Button>
                )}
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
              placeholder="Ask about your portfolios, issuers or the help docs…"
              multiline
              maxRows={4}
              fullWidth
              variant="standard"
              slotProps={{ htmlInput: { maxLength: 2000 }, input: { disableUnderline: true } }}
              sx={{ "& textarea": { py: 0.8, fontSize: 12, lineHeight: 1.45 } }}
            />
            <IconButton type="submit" disabled={submitting || chat.loading || !chat.sessionId || !input.trim()} aria-label="Send message" sx={{ width: 38, height: 38, flex: "0 0 auto", borderRadius: 1.25, bgcolor: colors.greenDark, color: "white", "&:hover": { bgcolor: colors.green }, "&.Mui-disabled": { bgcolor: "#b7c1ba", color: "white" } }}>
              {submitting ? <CircularProgress size={17} color="inherit" /> : <ArrowUpwardRoundedIcon fontSize="small" />}
            </IconButton>
          </Paper>
          <Typography sx={{ mx: 1.75, mb: 1.5, color: "#9aa29c", fontSize: 9, textAlign: "center" }}>
            {submitting ? <Button size="small" onClick={chat.cancel}>Stop response</Button>
              : "Financial values are mock snapshots. Preferences are saved per demo user."}
          </Typography>
        </Paper>
      )}
      {selectedVisual && <Suspense fallback={<Dialog open onClose={() => setVisualMessage(null)} fullWidth maxWidth="sm">
        <DialogTitle>正在加载可视化回答…</DialogTitle>
        <DialogContent><CircularProgress size={24} /></DialogContent>
        <DialogActions><Button onClick={() => setVisualMessage(null)}>返回聊天</Button></DialogActions>
      </Dialog>}>
        <VisualAnswerDialog message={selectedVisual} question={messages[visualIndex - 1]?.role === "user" ? messages[visualIndex - 1].content : "回答概览"}
          onClose={() => setVisualMessage(null)} />
      </Suspense>}
      <Dialog open={chat.memoryOpen} onClose={() => !chat.memoryBusy && chat.setMemoryOpen(false)} fullWidth maxWidth="sm">
        <DialogTitle>Saved memory · {chat.users.find(user => user.id === chat.userId)?.username}</DialogTitle>
        <DialogContent>
          <Typography sx={{ mb: 2, fontSize: 13, color: "text.secondary" }}>Review your preferences and stable background. Both share a budget of about {chat.memoryBudget.toLocaleString()} tokens. Current balances and holdings are queried again.</Typography>
          <TextField fullWidth multiline minRows={5} maxRows={12} label="User preferences" value={chat.memoryDraft}
            onChange={event => chat.setMemoryDraft(event.target.value)} disabled={chat.memoryBusy}
            placeholder="Your saved preferences will appear here."
            slotProps={{ htmlInput: { maxLength: 64000, "aria-label": "Saved memory" } }} />
          <TextField fullWidth multiline minRows={4} maxRows={10} label="Stable background" value={chat.memoryNotes}
            onChange={event => chat.setMemoryNotes(event.target.value)} disabled={chat.memoryBusy} sx={{ mt: 2 }}
            slotProps={{ htmlInput: { maxLength: 64000, "aria-label": "Stable background" } }} />
          {chat.memoryError && <Typography role="alert" color="error" sx={{ mt: 1 }}>{chat.memoryError}</Typography>}
        </DialogContent>
        <DialogActions>
          <Button color="error" disabled={chat.memoryBusy || !(chat.memoryDraft.trim() || chat.memoryNotes.trim())} onClick={() => void chat.saveMemory(true)}>Clear memory</Button>
          <Box sx={{ flex: 1 }} />
          <Button disabled={chat.memoryBusy} onClick={() => chat.setMemoryOpen(false)}>Cancel</Button>
          <Button variant="contained" disabled={chat.memoryBusy} onClick={() => void chat.saveMemory()}>{chat.memoryBusy ? "Saving…" : "Save"}</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}

createRoot(document.getElementById("app")!).render(
  <ThemeProvider theme={theme}>
    <CssBaseline />
    <App />
  </ThemeProvider>,
);
