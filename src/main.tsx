import { useEffect, useMemo, useRef, useState } from "react";
import type { FormEvent, KeyboardEvent as ReactKeyboardEvent } from "react";
import { createRoot } from "react-dom/client";
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
  Menu,
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
import CheckRoundedIcon from "@mui/icons-material/CheckRounded";
import CloseRoundedIcon from "@mui/icons-material/CloseRounded";
import ContentCopyRoundedIcon from "@mui/icons-material/ContentCopyRounded";
import OpenInNewRoundedIcon from "@mui/icons-material/OpenInNewRounded";
import MoreHorizRoundedIcon from "@mui/icons-material/MoreHorizRounded";
import StopRoundedIcon from "@mui/icons-material/StopRounded";
import "./style.css";
import { useAgentChat } from "./useAgentChat";
import { AgentActivityPanel } from "./AgentActivityPanel";
import { UserInputCard } from "./UserInputCard";
import { SafeMarkdown } from "./SafeMarkdown";
import { MainChatInputCaret, useMainChatCaretMode } from "./MainChatInputCaret";
import { useChatScroll } from "./useChatScroll";

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

function App() {
  const [docs, setDocs] = useState<HelpDoc[]>([]);
  const [selectedSlug, setSelectedSlug] = useState("");
  const [docsError, setDocsError] = useState("");
  const [chatOpen, setChatOpen] = useState(false);
  const chat = useAgentChat();
  const { messages, submitting } = chat;
  const [input, setInput] = useState("");
  const { messagesRef, messagesContentRef, showLatest, hasNewContent, scrollToLatest } =
    useChatScroll(chatOpen, `${chat.userId}:${chat.sessionId}`, messages, chat.inputRequest);
  const chatTriggerRef = useRef<HTMLButtonElement>(null);
  const [toolsAnchor, setToolsAnchor] = useState<HTMLElement | null>(null);
  const [copiedMessageId, setCopiedMessageId] = useState("");
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const mainInputHostRef = useRef<HTMLDivElement>(null);
  const mainChatCaretMode = useMainChatCaretMode();

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

  const openChat = () => {
    setChatOpen(true);
    window.setTimeout(() => inputRef.current?.focus(), 80);
  };

  const closeChat = () => {
    chat.cancel();
    setToolsAnchor(null);
    setChatOpen(false);
    window.requestAnimationFrame(() => chatTriggerRef.current?.focus());
  };

  const sendMessage = async (event?: FormEvent) => {
    event?.preventDefault();
    const content = input.trim();
    if (!content || submitting || chat.loading || !chat.sessionId) return;
    scrollToLatest();
    setInput("");
    window.requestAnimationFrame(() => inputRef.current?.focus());
    await chat.send(content);
  };

  const handleInputKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (event.nativeEvent.isComposing || event.nativeEvent.keyCode === 229) return;
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      event.currentTarget.closest("form")?.requestSubmit();
    }
  };

  const copyMessage = (messageId: string, content: string) => {
    if (!content.trim()) return;
    void navigator.clipboard?.writeText(content).catch(() => undefined);
    setCopiedMessageId(messageId);
    window.setTimeout(() => {
      setCopiedMessageId(current => current === messageId ? "" : current);
    }, 2200);
  };

  const estimatedTokens = useMemo(() => Math.ceil(input.trim().length * 0.7), [input]);

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
        <Fab ref={chatTriggerRef} variant="extended" onClick={openChat} aria-label="Open AI help bot" aria-haspopup="dialog" sx={{ position: "fixed", zIndex: 1200, right: { xs: 18, sm: 28 }, bottom: { xs: 18, sm: 26 }, bgcolor: colors.ink, color: "white", "&:hover": { bgcolor: colors.greenDark } }}>
          <AutoAwesomeRoundedIcon sx={{ mr: 1 }} /> Ask AI
        </Fab>
      )}

      {chatOpen && (
        <Paper
          className="chat-panel"
          role="dialog"
          aria-labelledby="chat-title"
          elevation={0}
          onKeyDown={event => {
            if (event.key === "Escape" && !event.defaultPrevented && !toolsAnchor
              && event.currentTarget.contains(event.target as Node)) {
              event.preventDefault();
              closeChat();
            }
          }}
          sx={{
            position: "fixed",
            zIndex: 1300,
            right: { xs: 10, sm: 26 },
            bottom: { xs: 10, sm: 24 },
            width: { xs: "calc(100% - 20px)", sm: 430 },
            height: { xs: "calc(100dvh - 20px)", sm: "min(680px, calc(100dvh - 40px))" },
            overflow: "hidden",
            border: 1,
            borderColor: "var(--chat-border)",
            borderRadius: 2.5,
            boxShadow: "var(--chat-floating-shadow)",
            containerType: "inline-size",
            containerName: "chat",
            display: "grid",
            gridTemplateColumns: "minmax(0, 1fr)",
            gridTemplateRows: "auto auto 1fr auto auto",
          }}
        >
          <Box component="header" className="chat-header" sx={{ minHeight: 68, p: 1.75, borderBottom: 1, borderColor: "divider", display: "flex", alignItems: "center", gap: 1.25 }}>
            <Avatar sx={{ width: 42, height: 42, bgcolor: colors.greenSoft, color: colors.greenDark }}>
              <AutoAwesomeRoundedIcon fontSize="small" />
            </Avatar>
            <Box sx={{ minWidth: 0, flex: 1 }}>
              <Typography id="chat-title" sx={{ fontSize: 15, fontWeight: 800 }}>Finance Assistant</Typography>
              <Box sx={{ mt: 0.25, display: "flex", alignItems: "center", gap: 0.75, color: "text.secondary" }}>
                <Box sx={{ width: 6, height: 6, borderRadius: "50%", bgcolor: "#23a66b", boxShadow: "0 0 0 3px #ddf4e8" }} />
                <Typography className="chat-subtitle" sx={{ fontSize: 11 }}>Help docs, portfolio queries & edits</Typography>
              </Box>
            </Box>
            <Tooltip title="Close">
              <IconButton onClick={closeChat} aria-label="Close chat"><CloseRoundedIcon /></IconButton>
            </Tooltip>
          </Box>

          <Box className="chat-controls" sx={{ px: 1.75, py: 1.25, borderBottom: 1, borderColor: "divider", bgcolor: "#fbfcfa" }}>
            <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
              <TextField select size="small" label="Demo user" value={chat.users.length ? chat.userId : ""}
                disabled={submitting || chat.memoryBusy} sx={{ flex: 1, minWidth: 0 }}
                onChange={event => { setInput(""); chat.setUserId(event.target.value); }}>
                {chat.users.map(user => <MenuItem key={user.id} value={user.id}>{user.username}</MenuItem>)}
              </TextField>
              <Button size="small" disabled={submitting || chat.loading} onClick={() => void chat.selectSession()}>New chat</Button>
              <Button className="chat-memory-button" size="small" disabled={submitting || chat.loading || chat.memoryBusy} onClick={() => void chat.openMemory()}>Memory</Button>
              <Tooltip title="更多操作">
                <IconButton className="chat-more-tools" size="small" sx={{ width: 38, height: 38 }} aria-label="更多聊天操作"
                  aria-controls={toolsAnchor ? "chat-tools-menu" : undefined} aria-haspopup="menu"
                  aria-expanded={!!toolsAnchor} onClick={event => setToolsAnchor(event.currentTarget)}>
                  <MoreHorizRoundedIcon />
                </IconButton>
              </Tooltip>
              <Menu id="chat-tools-menu" anchorEl={toolsAnchor} open={!!toolsAnchor} onClose={() => setToolsAnchor(null)}>
                <MenuItem disabled={submitting || chat.loading || chat.memoryBusy} onClick={() => { setToolsAnchor(null); void chat.openMemory(); }}>Memory</MenuItem>
                <MenuItem disabled={!chat.sessionId || submitting || chat.loading} onClick={() => { setToolsAnchor(null); void chat.exportSession(); }}>Export chat</MenuItem>
                <MenuItem disabled={!chat.sessionId || submitting || chat.loading} sx={{ color: "error.main" }} onClick={() => { setToolsAnchor(null); void chat.deleteSession(); }}>Delete chat</MenuItem>
                {chat.sessions.length >= 50 && <MenuItem disabled={chat.loading || submitting} onClick={() => { setToolsAnchor(null); void chat.loadMoreSessions(); }}>More conversations</MenuItem>}
              </Menu>
            </Box>
            {chat.sessions.length > 1 && <TextField select fullWidth size="small" label="Conversation"
              value={chat.sessionId} disabled={submitting || chat.loading} sx={{ mt: 1.25 }}
              onChange={event => void chat.selectSession(event.target.value)}>
              {chat.sessions.map(session => <MenuItem key={session.id} value={session.id}>{session.title}</MenuItem>)}
            </TextField>}
            {chat.error && <Typography role="alert" color="error" sx={{ mt: 1, fontSize: 12 }}>{chat.error}</Typography>}
            {chat.loading && <Typography sx={{ mt: 1, fontSize: 11, color: "text.secondary" }}>Loading conversation…</Typography>}
            <Box className="chat-secondary-tools" sx={{ display: "flex", flexWrap: "wrap", gap: 1, mt: 0.5 }}>
              {chat.sessions.length >= 50 && <Button size="small" disabled={chat.loading || submitting} onClick={() => void chat.loadMoreSessions()}>More conversations</Button>}
              <Button size="small" disabled={!chat.sessionId || submitting || chat.loading} onClick={() => void chat.exportSession()}>Export chat</Button>
              <Button size="small" color="error" disabled={!chat.sessionId || submitting || chat.loading} onClick={() => void chat.deleteSession()}>Delete chat</Button>
            </Box>
            {chat.storage && chat.storage.bytes >= chat.storage.quotaBytes * 0.9 && <Typography role="alert" color="error" sx={{ fontSize: 12 }}>
              History storage is nearly full. Export and delete old conversations or increase the storage quota.
            </Typography>}
          </Box>

          <Box sx={{ position: "relative", minHeight: 0 }}>
            <Box ref={messagesRef} className="chat-messages" tabIndex={0} aria-label="聊天消息"
              sx={{ height: "100%", overflowY: "auto", p: 2.25, bgcolor: "#fafaf8" }}>
              <div ref={messagesContentRef}>
                {chat.historyOffset > 0 && <Button fullWidth size="small" disabled={chat.historyBusy || submitting}
                  onClick={() => void chat.loadEarlier()}>{chat.historyBusy ? "Loading…" : "Load earlier messages"}</Button>}
                {messages.map((message) => (
                  <Box
                    key={message.id}
                    className={`chat-message-item chat-message-${message.role}`}
                    sx={{ mb: 2.5, display: "flex", flexDirection: "column", alignItems: message.role === "user" ? "flex-end" : "flex-start" }}
                  >
                    {message.role === "assistant" ? (
                      <Box sx={{ mb: 0.75, display: "inline-flex", alignItems: "center", gap: 0.85, userSelect: "none" }}>
                        <Box
                          component="span"
                          sx={{
                            width: 18,
                            height: 18,
                            borderRadius: "5px",
                            bgcolor: colors.greenDark,
                            color: "white",
                            display: "inline-flex",
                            alignItems: "center",
                            justifyContent: "center",
                            fontSize: 10,
                            fontFamily: "Georgia, serif",
                            fontWeight: 700,
                            boxShadow: "0 1px 2px rgba(12, 80, 51, 0.2)",
                          }}
                        >
                          ✦
                        </Box>
                        <Typography sx={{ color: colors.ink, fontSize: 11, fontWeight: 700, letterSpacing: ".01em" }}>
                          Help Bot
                        </Typography>
                      </Box>
                    ) : (
                      <Typography sx={{ mx: 0.5, mb: 0.5, color: "#8b948d", fontSize: 9, fontWeight: 800, letterSpacing: ".09em", textTransform: "uppercase" }}>
                        You
                      </Typography>
                    )}
                    {message.role === "assistant" && (message.pending || message.partial || !!message.activities?.length) &&
                      <AgentActivityPanel activities={message.activities || []} pending={message.pending}
                        partial={message.partial} status={message.status} startedAt={message.startedAt} endedAt={message.endedAt} />}
                    <Paper
                      variant="outlined"
                      sx={{
                        display: message.pending && !message.content ? "none" : undefined,
                        maxWidth: message.role === "user" ? "91%" : "100%",
                        minWidth: 0,
                        width: message.role === "assistant" ? "100%" : undefined,
                        px: message.role === "user" ? 1.85 : 0.25,
                        py: message.role === "user" ? 1.15 : 0.25,
                        border: message.role === "user" ? 1 : 0,
                        borderColor: message.role === "user" ? "rgba(18, 108, 70, 0.18)" : "transparent",
                        borderRadius: message.role === "user" ? "18px 18px 4px 18px" : 1.5,
                        bgcolor: message.role === "user" ? "#e9f2ec" : "transparent",
                        color: message.role === "user" ? colors.ink : "#26312a",
                        boxShadow: message.role === "user" ? "0 1px 2px rgba(24, 39, 29, 0.04)" : "none",
                      }}
                    >
                      {message.role === "assistant" ? (
                        <SafeMarkdown content={message.content} variant="message" streaming={message.pending} />
                      ) : (
                        <Typography sx={{ whiteSpace: "pre-wrap", fontSize: 13.5, lineHeight: 1.55, overflowWrap: "anywhere" }}>{message.content}</Typography>
                      )}
                    </Paper>
                    {!!message.sources?.length && (
                      <Box sx={{ width: "100%", mt: 1, display: "flex", flexWrap: "wrap", gap: 0.75 }}>
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
                    {message.role === "assistant" && !message.pending && !!message.content.trim() && (
                      <Box className="chat-message-actions" sx={{ mt: 0.5, display: "flex", alignItems: "center", gap: 0.75 }}>
                        <Button
                          size="small"
                          color="inherit"
                          onClick={() => copyMessage(message.id, message.content)}
                          startIcon={copiedMessageId === message.id
                            ? <CheckRoundedIcon sx={{ fontSize: "13px !important", color: "primary.main" }} />
                            : <ContentCopyRoundedIcon sx={{ fontSize: "13px !important" }} />}
                          sx={{
                            minWidth: 0,
                            px: 0.85,
                            py: 0.2,
                            height: 24,
                            borderRadius: "6px",
                            fontSize: 11,
                            fontWeight: 600,
                            color: copiedMessageId === message.id ? "primary.main" : "text.secondary",
                            "&:hover": { bgcolor: "rgba(33, 53, 40, 0.06)", color: "text.primary" },
                          }}
                        >
                          {copiedMessageId === message.id ? "Copied" : "Copy"}
                        </Button>
                      </Box>
                    )}
                  </Box>
                ))}
                {chat.inputRequest && <UserInputCard key={chat.inputRequest.requestId} request={chat.inputRequest}
                  busy={submitting || chat.loading} onAnswer={chat.answerInput} />}
              </div>
            </Box>
            {showLatest && <Button className="chat-latest" size="small" variant="outlined" onClick={scrollToLatest}
              sx={{ position: "absolute", bottom: 12, left: "50%", transform: "translateX(-50%)", borderRadius: 5,
                bgcolor: "white", whiteSpace: "nowrap", borderColor: "var(--chat-border)", boxShadow: "var(--chat-control-shadow)",
                "&:hover": { bgcolor: "#f3f7f3" } }}>
              {hasNewContent ? "有新内容 ↓" : "回到最新回答 ↓"}
            </Button>}
          </Box>

          <Paper
            className="chat-composer"
            component="form"
            onSubmit={(event) => void sendMessage(event)}
            variant="outlined"
            onClick={(event) => {
              if (event.target === event.currentTarget) inputRef.current?.focus();
            }}
            sx={{
              m: "10px 14px 6px",
              p: "10px 11px 8px 13px",
              borderColor: "transparent",
              borderRadius: "20px",
              bgcolor: "#ffffff",
              boxShadow: "var(--chat-composer-shadow)",
              display: "flex",
              flexDirection: "column",
              gap: 0.75,
              "&:hover": { boxShadow: "var(--chat-composer-shadow-hover)" },
              "&:focus-within": { boxShadow: "var(--chat-focus-ring)" },
            }}
          >
            <Box ref={mainInputHostRef} sx={{ position: "relative", width: "100%", minWidth: 0 }}>
              <TextField
                inputRef={inputRef}
                value={input}
                onChange={(event) => setInput(event.target.value)}
                onKeyDown={handleInputKeyDown}
                placeholder="查询组合，或试试：把 pf-001 改名为退休计划…"
                multiline
                maxRows={4}
                fullWidth
                variant="standard"
                slotProps={{ htmlInput: { maxLength: 2000 }, input: { disableUnderline: true } }}
                sx={{
                  "& textarea:not([aria-hidden='true'])": {
                    py: 0.4,
                    fontSize: 16,
                    lineHeight: 1.45,
                    ...(mainChatCaretMode === "animated" ? { caretColor: "transparent" } : {}),
                  },
                }}
              />
              <MainChatInputCaret
                hostRef={mainInputHostRef}
                textareaRef={inputRef}
                enabled={chatOpen}
                disabled={submitting || chat.loading}
              />
            </Box>
            <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 1, pt: 0.25, minWidth: 0 }}>
              <Typography className="chat-composer-hint" sx={{ color: "text.secondary", fontSize: 11, userSelect: "none", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                {submitting ? "正在生成回答…" : "Enter 发送 · Shift+Enter 换行"}
              </Typography>
              <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexShrink: 0 }}>
                {estimatedTokens > 0 && !submitting && (
                  <Typography
                    sx={{
                      fontSize: 10.5,
                      fontFamily: '"SFMono-Regular", Consolas, monospace',
                      fontVariantNumeric: "tabular-nums",
                      color: input.length > 1800 ? "error.main" : "text.secondary",
                      userSelect: "none",
                    }}
                    title="当前输入预估 Token"
                  >
                    ~{estimatedTokens} tok
                  </Typography>
                )}
                {submitting ? (
                  <Tooltip title="停止生成">
                    <IconButton
                      type="button"
                      onClick={chat.cancel}
                      aria-label="Stop response"
                      sx={{
                        width: 32,
                        height: 32,
                        borderRadius: "10px",
                        bgcolor: colors.ink,
                        color: "white",
                        transition: "transform 140ms cubic-bezier(.34, 1.3, .64, 1), background-color 140ms ease",
                        "&:hover": { bgcolor: "#2a382e" },
                        "&:active": { transform: "scale(0.94)" },
                      }}
                    >
                      <StopRoundedIcon sx={{ fontSize: 17 }} />
                    </IconButton>
                  </Tooltip>
                ) : (
                  <IconButton
                    type="submit"
                    disabled={chat.loading || !chat.sessionId || !input.trim()}
                    aria-label="Send message"
                    sx={{
                      width: 32,
                      height: 32,
                      flex: "0 0 auto",
                      borderRadius: "10px",
                      bgcolor: colors.greenDark,
                      color: "white",
                      transition: "transform 140ms cubic-bezier(.34, 1.3, .64, 1), background-color 140ms ease, opacity 140ms ease",
                      "&:hover": { bgcolor: colors.green },
                      "&:active": { transform: "scale(0.94)" },
                      "&.Mui-disabled": { bgcolor: "#d5ddd7", color: "white" },
                    }}
                  >
                    <ArrowUpwardRoundedIcon sx={{ fontSize: 17 }} />
                  </IconButton>
                )}
              </Box>
            </Box>
          </Paper>
          <Box className="sr-only" role="status" aria-live="polite" aria-atomic="true">
            {submitting ? "正在生成回答" : messages.at(-1)?.partial ? "回答已停止，已有内容已保留" : "回答已就绪"}
          </Box>
          <Typography sx={{ mx: 1.75, mb: 1.5, color: "text.secondary", fontSize: 10, textAlign: "center" }}>
            {submitting ? <Button size="small" onClick={chat.cancel} sx={{ py: 0, minHeight: 20, fontSize: 11 }}>Stop response</Button>
              : "Financial values are mock snapshots. Preferences are saved per demo user."}
          </Typography>
        </Paper>
      )}
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
