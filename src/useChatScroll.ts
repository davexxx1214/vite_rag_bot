import { useCallback, useLayoutEffect, useRef, useState } from "react";

type Message = { id: string; content: string };

export function useChatScroll(open: boolean, contextKey: string, messages: readonly Message[], inputRequest: unknown) {
  const messagesRef = useRef<HTMLDivElement>(null);
  const messagesContentRef = useRef<HTMLDivElement>(null);
  const following = useRef(true);
  const frame = useRef<number | null>(null);
  const lastTop = useRef(0);
  const snapshot = useRef({ firstId: "", height: 0, top: 0 });
  const latest = useRef<Message | undefined>(undefined);
  const [showLatest, setShowLatest] = useState(false);
  const [hasNewContent, setHasNewContent] = useState(false);

  const cancelFrame = useCallback(() => {
    if (frame.current !== null) cancelAnimationFrame(frame.current);
    frame.current = null;
  }, []);

  const scrollToLatest = useCallback(() => {
    cancelFrame();
    following.current = true;
    setShowLatest(false);
    setHasNewContent(false);
    const container = messagesRef.current;
    if (!container) return;
    container.scrollTop = container.scrollHeight;
    lastTop.current = container.scrollTop;
    snapshot.current.top = container.scrollTop;
  }, [cancelFrame]);

  const scheduleFollow = useCallback(() => {
    if (!following.current || frame.current !== null) return;
    frame.current = requestAnimationFrame(() => {
      frame.current = null;
      if (following.current) scrollToLatest();
    });
  }, [scrollToLatest]);

  useLayoutEffect(() => {
    following.current = true;
    latest.current = undefined;
    lastTop.current = 0;
    snapshot.current = { firstId: "", height: 0, top: 0 };
    setShowLatest(false);
    setHasNewContent(false);
    return cancelFrame;
  }, [open, contextKey, cancelFrame]);

  useLayoutEffect(() => {
    if (!open) return;
    const container = messagesRef.current;
    const content = messagesContentRef.current;
    if (!container || !content) return;
    const FADE_SIZE = 24;
    let appliedFadeTop = -1;
    let appliedFadeBottom = -1;
    const syncScrollFades = () => {
      const { scrollTop, scrollHeight, clientHeight } = container;
      const canScroll = scrollHeight > clientHeight + 2;
      const fadeTop = canScroll && scrollTop > 2 ? FADE_SIZE : 0;
      const fadeBottom = canScroll && scrollTop + clientHeight < scrollHeight - 2 ? FADE_SIZE : 0;
      if (fadeTop !== appliedFadeTop) {
        appliedFadeTop = fadeTop;
        container.style.setProperty("--chat-fade-top", `${fadeTop}px`);
      }
      if (fadeBottom !== appliedFadeBottom) {
        appliedFadeBottom = fadeBottom;
        container.style.setProperty("--chat-fade-bottom", `${fadeBottom}px`);
      }
    };
    syncScrollFades();
    let touchY = 0;
    const pause = () => {
      if (container.scrollHeight <= container.clientHeight) return;
      cancelFrame();
      following.current = false;
      setShowLatest(true);
      syncScrollFades();
    };
    const wheel = (event: WheelEvent) => {
      if (event.deltaY < 0) pause();
    };
    const touchStart = (event: TouchEvent) => { touchY = event.touches[0]?.clientY ?? 0; };
    const touchMove = (event: TouchEvent) => {
      const nextY = event.touches[0]?.clientY ?? touchY;
      if (nextY > touchY) pause();
      touchY = nextY;
    };
    const keyDown = (event: KeyboardEvent) => {
      if (event.defaultPrevented || (event.target instanceof HTMLElement
        && event.target.closest("input, textarea, select, [contenteditable='true']"))) return;
      if (["ArrowUp", "PageUp", "Home"].includes(event.key) || (event.key === " " && event.shiftKey)) pause();
    };
    const scroll = () => {
      const top = container.scrollTop;
      const atBottom = container.scrollHeight - container.clientHeight - top <= 2;
      if (top < lastTop.current - 0.5 && (!following.current || !atBottom)) pause();
      else if (top > lastTop.current + 0.5 && atBottom) {
        following.current = true;
        setShowLatest(false);
        setHasNewContent(false);
      }
      lastTop.current = top;
      snapshot.current.top = top;
      syncScrollFades();
    };
    const observer = new ResizeObserver(() => {
      snapshot.current.height = container.scrollHeight;
      scheduleFollow();
      syncScrollFades();
    });
    observer.observe(content);
    observer.observe(container);
    container.addEventListener("wheel", wheel, { passive: true });
    container.addEventListener("touchstart", touchStart, { passive: true });
    container.addEventListener("touchmove", touchMove, { passive: true });
    container.addEventListener("keydown", keyDown);
    container.addEventListener("scroll", scroll);
    return () => {
      observer.disconnect();
      cancelFrame();
      container.removeEventListener("wheel", wheel);
      container.removeEventListener("touchstart", touchStart);
      container.removeEventListener("touchmove", touchMove);
      container.removeEventListener("keydown", keyDown);
      container.removeEventListener("scroll", scroll);
    };
  }, [open, scheduleFollow, cancelFrame]);

  useLayoutEffect(() => {
    const container = messagesRef.current;
    if (!open || !container) return;
    const previous = snapshot.current;
    const firstId = messages[0]?.id ?? "";
    const prepended = previous.firstId && firstId !== previous.firstId
      && messages.some(message => message.id === previous.firstId);
    if (prepended) {
      cancelFrame();
      following.current = false;
      container.scrollTop = previous.top + container.scrollHeight - previous.height;
      lastTop.current = container.scrollTop;
      setShowLatest(true);
    }
    const newest = messages.at(-1);
    if (!following.current && latest.current && newest
      && (newest.id !== latest.current.id || newest.content !== latest.current.content)) setHasNewContent(true);
    latest.current = newest;
    snapshot.current = { firstId, height: container.scrollHeight, top: container.scrollTop };
    scheduleFollow();
  }, [open, contextKey, messages, inputRequest, scheduleFollow, cancelFrame]);

  return { messagesRef, messagesContentRef, showLatest, hasNewContent, scrollToLatest };
}
