import { useCallback, useEffect, useRef, useState } from "react";
import { applyActivityEvent, finishActivities } from "./agentActivity";
import type { Activity, ActivityEvent } from "./agentActivity";
import type { InputAnswer, InputRequest } from "./inputRequest";
import { mergeEditResult, withEditResults } from "./editResultViews";
import type { EditResultView } from "./editResultViews";
import { createFrameBatch } from "./frameBatch";

export type Source = {
  citation: string;
  title: string;
  section: string;
  source: string;
  score: number;
};

export type ChatMessage = {
  id: string;
  role: "user" | "assistant";
  content: string;
  sources?: Source[];
  pending?: boolean;
  partial?: boolean;
  status?: string;
  activities?: Activity[];
  startedAt?: number;
  endedAt?: number;
};

type User = { id: string; username: string; displayName: string };
type Session = { id: string; title: string; updatedAt: string };
type SessionPage = { messages: ChatMessage[]; offset: number; nextOffset: number | null; total: number; inputRequest?: InputRequest | null };
type CoreMemory = { content: string; user: string; notes: string; estimatedTokens: number; budgetTokens: number };
type Storage = { bytes: number; quotaBytes: number; overQuota: boolean };
type StreamEvent =
  | ActivityEvent
  | { type: "status" | "context_compacted"; message: string }
  | { type: "sources"; sources: Source[] }
  | { type: "delta"; text: string }
  | { type: "answer_reset" }
  | { type: "user_input_required"; inputRequest: InputRequest }
  | { type: "edit_result"; operationId: string; markdown: string }
  | { type: "error"; error: string }
  | { type: "done"; partial?: boolean; reason?: string; toolCalls?: number };

const sessionKey = (userId: string) => `ragbot.session.${userId}`;
const id = () => `${Date.now()}-${Math.random().toString(16).slice(2)}`;
const welcome = (): ChatMessage[] => [{
  id: id(), role: "assistant",
  content: "Hi! I can search help documents, query portfolios and issuers, rename your portfolios, and remember your preferences. Try **把 pf-001 改名为退休计划**, **Compare my portfolios**, or **Remember that I prefer answers in Chinese**.",
}];

function stored(key: string) {
  try { return localStorage.getItem(key); } catch { return null; }
}

function persist(key: string, value: string) {
  try { localStorage.setItem(key, value); } catch { /* Server history remains available. */ }
}

async function api<T>(path: string, userId: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(`/api/agent${path}`, {
    ...options,
    headers: { "Content-Type": "application/json", "X-Mock-User-Id": userId, ...options.headers },
  });
  const payload: unknown = await response.json();
  if (!response.ok) {
    const error = payload as { error?: string };
    throw new Error(error.error || "Unable to complete this operation");
  }
  return payload as T;
}

export function useAgentChat() {
  const [users, setUsers] = useState<User[]>([]);
  const [userId, setUserId] = useState(stored("ragbot.user") || "user-001");
  const [sessions, setSessions] = useState<Session[]>([]);
  const [sessionId, setSessionId] = useState("");
  const [messages, setMessages] = useState<ChatMessage[]>(welcome);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");
  const [inputRequest, setInputRequest] = useState<InputRequest | null>(null);
  const [memoryOpen, setMemoryOpen] = useState(false);
  const [memoryDraft, setMemoryDraft] = useState("");
  const [memoryNotes, setMemoryNotes] = useState("");
  const [memoryBudget, setMemoryBudget] = useState(1800);
  const [historyOffset, setHistoryOffset] = useState(0);
  const [historyBusy, setHistoryBusy] = useState(false);
  const [storage, setStorage] = useState<Storage | null>(null);
  const [memoryBusy, setMemoryBusy] = useState(false);
  const [memoryError, setMemoryError] = useState("");
  const navigation = useRef(0);
  const active = useRef<{ controller: AbortController; userId: string; sessionId: string } | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    void fetch("/api/agent/users", { signal: controller.signal })
      .then(async response => {
        if (!response.ok) throw new Error("Unable to load demo users");
        return await response.json() as User[];
      })
      .then(loaded => {
        setUsers(loaded);
        if (!loaded.some(user => user.id === userId)) setUserId(loaded[0]?.id || "user-001");
      })
      .catch((cause: unknown) => {
        if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : "Unable to load users");
      });
    return () => controller.abort();
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    const version = ++navigation.current;
    setLoading(true);
    setError("");
    setSessionId("");
    setSessions([]);
    setStorage(null);
    setMessages(welcome());
    setInputRequest(null);
    setMemoryOpen(false);
    persist("ragbot.user", userId);
    void (async () => {
      let loaded = await api<Session[]>("/sessions", userId, { signal: controller.signal });
      const preferred = stored(sessionKey(userId));
      let selected = loaded.find(session => session.id === preferred)?.id || loaded[0]?.id;
      if (!selected) {
        selected = (await api<{ id: string }>("/sessions", userId, { method: "POST", signal: controller.signal })).id;
        loaded = [{ id: selected, title: "New conversation", updatedAt: new Date().toISOString() }, ...loaded];
      }
      const session = await api<SessionPage>(`/sessions/${selected}`, userId, { signal: controller.signal });
      const usage = await api<Storage>("/storage", userId, { signal: controller.signal });
      if (navigation.current !== version) return;
      setSessionId(selected);
      setSessions(loaded);
      setMessages(session.messages.length ? session.messages : welcome());
      setInputRequest(session.inputRequest ?? null);
      setHistoryOffset(session.offset);
      setStorage(usage);
      persist(sessionKey(userId), selected);
    })().catch((cause: unknown) => {
      if (!controller.signal.aborted && navigation.current === version) {
        setError(cause instanceof Error ? cause.message : "Unable to load conversation");
      }
    }).finally(() => {
      if (navigation.current === version) setLoading(false);
    });
    return () => controller.abort();
  }, [userId]);

  const cancel = useCallback(() => {
    const run = active.current;
    if (!run) return;
    run.controller.abort();
    void api(`/sessions/${run.sessionId}/cancel`, run.userId, { method: "POST" }).catch(() => undefined);
  }, []);

  useEffect(() => () => cancel(), [cancel]);

  const selectSession = async (selected?: string) => {
    if (submitting || loading) return;
    const version = ++navigation.current;
    setLoading(true);
    setError("");
    try {
      const chosen = selected || (await api<{ id: string }>("/sessions", userId, { method: "POST" })).id;
      const session = await api<SessionPage>(`/sessions/${chosen}`, userId);
      const loaded = await api<Session[]>("/sessions", userId);
      if (navigation.current !== version) return;
      setSessionId(chosen);
      setSessions(loaded);
      setMessages(session.messages.length ? session.messages : welcome());
      setInputRequest(session.inputRequest ?? null);
      setHistoryOffset(session.offset);
      persist(sessionKey(userId), chosen);
    } catch (cause) {
      if (navigation.current === version) setError(cause instanceof Error ? cause.message : "Unable to load conversation");
    } finally {
      if (navigation.current === version) setLoading(false);
    }
  };

  const loadEarlier = async () => {
    if (historyBusy || historyOffset <= 0 || submitting) return;
    const version = navigation.current;
    setHistoryBusy(true);
    try {
      const offset = Math.max(0, historyOffset - 50);
      const page = await api<SessionPage>(`/sessions/${sessionId}?offset=${offset}&limit=${historyOffset - offset}`, userId);
      if (navigation.current !== version) return;
      setMessages(current => [...page.messages, ...current]); setHistoryOffset(offset);
    } catch (cause) { setError(cause instanceof Error ? cause.message : "Unable to load history"); }
    finally { setHistoryBusy(false); }
  };

  const loadMoreSessions = async () => {
    const version = navigation.current;
    try {
      const page = await api<Session[]>(`/sessions?offset=${sessions.length}&limit=50`, userId);
      if (navigation.current === version) setSessions(current => [...current, ...page]);
    } catch (cause) { setError(cause instanceof Error ? cause.message : "Unable to load conversations"); }
  };

  const exportSession = async () => {
    try {
      const response = await fetch(`/api/agent/sessions/${sessionId}/export`, { headers: { "X-Mock-User-Id": userId } });
      if (!response.ok) throw new Error("Unable to export conversation");
      const url = URL.createObjectURL(await response.blob());
      const link = document.createElement("a"); link.href = url; link.download = `${sessionId}.jsonl`; link.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (cause) { setError(cause instanceof Error ? cause.message : "Unable to export conversation"); }
  };

  const deleteSession = async () => {
    if (!window.confirm("Delete this conversation and its saved history permanently?")) return;
    try { await api(`/sessions/${sessionId}`, userId, { method: "DELETE" }); await selectSession(); }
    catch (cause) { setError(cause instanceof Error ? cause.message : "Unable to delete conversation"); }
  };

  const update = (messageId: string, patch: Partial<ChatMessage>) => {
    setMessages(current => current.map(message => message.id === messageId ? { ...message, ...patch } : message));
  };

  const send = async (content: string, inputAnswer?: InputAnswer) => {
    if (active.current || submitting || loading || !sessionId || !content.trim() || (inputAnswer && !inputRequest)) return;
    const replyId = id();
    const startedAt = Date.now();
    setMessages(current => [...current, { id: id(), role: "user", content },
      { id: replyId, role: "assistant", content: "", pending: true, startedAt, status: "正在连接服务…" }]);
    setSubmitting(true);
    setError("");
    const controller = new AbortController();
    const run = { controller, userId, sessionId };
    const runNavigation = navigation.current;
    active.current = run;
    let timedOut = false;
    // Give the server's 240-second agent deadline time to deliver a terminal event.
    const timeout = window.setTimeout(() => { timedOut = true; cancel(); }, 270_000);
    let answer = "";
    let editViews: EditResultView[] = [];
    let complete = false;
    let partial = false;
    let activities: Activity[] = [];
    const answerUpdates = createFrameBatch(
      () => update(replyId, { content: withEditResults(answer, editViews) }),
      callback => window.requestAnimationFrame(callback),
      frame => window.cancelAnimationFrame(frame),
    );
    try {
      const requestPath = inputAnswer && inputRequest
        ? `/api/agent/sessions/${sessionId}/input-requests/${inputRequest.requestId}/answers` : "/api/chat";
      const response = await fetch(requestPath, {
        method: "POST", headers: { "Content-Type": "application/json", "X-Mock-User-Id": userId },
        body: JSON.stringify(inputAnswer ?? { sessionId, message: content }), signal: controller.signal,
      });
      if (!response.ok) {
        const payload = await response.json().catch(() => null) as { error?: string } | null;
        throw new Error(payload?.error || "The agent could not answer");
      }
      if (!response.body) throw new Error("The server returned no response stream");
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      const consume = (line: string) => {
        if (!line.trim()) return;
        const event = JSON.parse(line) as StreamEvent;
        if (event.type !== "delta") answerUpdates.flush();
        switch (event.type) {
          case "status": case "context_compacted": update(replyId, { status: event.message }); break;
          case "activity_start": case "activity_update": case "activity_end": case "tool_start": case "tool_end":
            activities = applyActivityEvent(activities, event, Date.now());
            update(replyId, { activities });
            break;
          case "sources": update(replyId, { sources: event.sources }); break;
          case "answer_reset": answer = ""; update(replyId, { content: withEditResults(answer, editViews) }); break;
          case "edit_result":
            editViews = mergeEditResult(editViews, event);
            update(replyId, { content: withEditResults(answer, editViews) });
            break;
          case "user_input_required": setInputRequest(event.inputRequest); update(replyId, { status: "等待你的选择" }); break;
          case "delta": answer += event.text; answerUpdates.schedule(); break;
          case "error": throw new Error(event.error);
          case "done": partial = !!event.partial; complete = true; break;
        }
      };
      while (!complete) {
        const { done, value } = await reader.read();
        buffer += decoder.decode(value, { stream: !done });
        const lines = buffer.split("\n");
        buffer = lines.pop() || "";
        for (const line of lines) consume(line);
        if (done) { consume(buffer); break; }
      }
      if (!complete || !withEditResults(answer, editViews).trim()) throw new Error("The response ended before the answer completed");
      answerUpdates.flush();
      const endedAt = Date.now();
      activities = finishActivities(activities, partial ? "cancelled" : "completed", endedAt);
      update(replyId, { pending: false, partial, activities, endedAt });
      // The agent is done; refreshing the sidebar is outside its execution deadline.
      window.clearTimeout(timeout);
      try {
        setSessions(await api<Session[]>("/sessions", userId));
        setStorage(await api<Storage>("/storage", userId));
      }
      catch { /* A list refresh failure must not turn a completed answer into an error. */ }
    } catch (cause) {
      answerUpdates.cancel();
      const cancelled = controller.signal.aborted;
      const message = cancelled ? (timedOut ? "The request timed out." : "The request was cancelled.")
        : cause instanceof Error ? cause.message : "Unable to complete your request";
      const endedAt = Date.now();
      activities = finishActivities(activities, cancelled && !timedOut ? "cancelled" : "failed", endedAt);
      const retained = withEditResults(answer, editViews);
      update(replyId, { pending: false, activities, endedAt, content: retained ? `${retained}\n\n_${message}_` : message });
    } finally {
      answerUpdates.cancel();
      window.clearTimeout(timeout);
      if (active.current === run) active.current = null;
      setSubmitting(false);
      // Reload even after a disconnected response: the edit/question may already have been committed.
      void api<SessionPage>(`/sessions/${sessionId}`, userId).then(page => {
        if (active.current === null && navigation.current === runNavigation) setInputRequest(page.inputRequest ?? null);
      }).catch(() => undefined);
    }
  };

  const openMemory = async () => {
    setMemoryOpen(true);
    setMemoryBusy(true);
    setMemoryError("");
    try {
      const memory = await api<CoreMemory>("/memory", userId);
      setMemoryDraft(memory.user); setMemoryNotes(memory.notes); setMemoryBudget(memory.budgetTokens);
    }
    catch (cause) { setMemoryError(cause instanceof Error ? cause.message : "Unable to load memory"); }
    finally { setMemoryBusy(false); }
  };

  const saveMemory = async (clear = false) => {
    setMemoryBusy(true);
    setMemoryError("");
    try {
      if (clear) {
        await api("/memory", userId, { method: "DELETE" }); setMemoryDraft(""); setMemoryNotes("");
      } else {
        const result = await api<CoreMemory>("/memory", userId,
          { method: "PUT", body: JSON.stringify({ user: memoryDraft, notes: memoryNotes }) });
        setMemoryDraft(result.user); setMemoryNotes(result.notes);
      }
      if (!clear) setMemoryOpen(false);
    } catch (cause) { setMemoryError(cause instanceof Error ? cause.message : "Unable to save memory"); }
    finally { setMemoryBusy(false); }
  };

  return { users, userId, setUserId, sessions, sessionId, selectSession, messages, loading,
    inputRequest, answerInput: (answer: InputAnswer, display: string) => send(display, answer),
    submitting, error, send, cancel, memoryOpen, setMemoryOpen, memoryDraft, setMemoryDraft,
    memoryBusy, memoryError, openMemory, saveMemory, memoryNotes, setMemoryNotes, memoryBudget,
    historyOffset, historyBusy, loadEarlier, loadMoreSessions, exportSession, deleteSession, storage };
}
