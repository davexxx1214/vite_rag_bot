import { useEffect, useRef, useState, type RefObject } from "react";
import { Box } from "@mui/material";
import {
  type AnimatedCaretMode,
  type CaretPoint,
  lerp,
  measureCaretInTextarea,
  resolveAnimatedCaretMode,
  syncMirrorStyles,
} from "./animatedCaret";

const TAIL_SEGMENTS = 6;
const MOVE_THRESHOLD = 0.75;
const LERP = 0.38;

type TrailDot = { x: number; y: number; h: number; life: number };

export function useMainChatCaretMode(disabled?: boolean): AnimatedCaretMode {
  return useState<AnimatedCaretMode>(() =>
    resolveAnimatedCaretMode({
      disabled,
      forceNative: new URLSearchParams(window.location.search).get("caret") === "off",
      forceAnimated: new URLSearchParams(window.location.search).get("caret") === "on",
    }),
  )[0];
}

export function MainChatInputCaret({
  hostRef,
  textareaRef,
  enabled,
  disabled,
}: {
  hostRef: RefObject<HTMLElement | null>;
  textareaRef: RefObject<HTMLTextAreaElement | null>;
  enabled: boolean;
  disabled?: boolean;
}) {
  const [mode] = useState<AnimatedCaretMode>(() =>
    resolveAnimatedCaretMode({
      disabled,
      forceNative: new URLSearchParams(window.location.search).get("caret") === "off",
      forceAnimated: new URLSearchParams(window.location.search).get("caret") === "on",
    }),
  );

  const layerRef = useRef<HTMLDivElement | null>(null);
  const caretRef = useRef<HTMLDivElement | null>(null);
  const rafRef = useRef<number | null>(null);
  const animRef = useRef<number | null>(null);
  const dirtyRef = useRef(false);
  const focusedRef = useRef(false);
  const composingRef = useRef(false);
  const targetRef = useRef<CaretPoint | null>(null);
  const displayRef = useRef<CaretPoint | null>(null);
  const trailsRef = useRef<TrailDot[]>([]);
  const visibleRef = useRef(false);
  const mirrorRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (mode !== "animated" || !enabled) return;
    const textarea = textareaRef.current;
    const host = hostRef.current;
    if (!textarea || !host) return;

    const mirror = document.createElement("div");
    mirror.setAttribute("aria-hidden", "true");
    mirrorRef.current = mirror;
    host.appendChild(mirror);

    const syncGeometry = () => {
      const layer = layerRef.current;
      if (!layer) return;
      const hostRect = host.getBoundingClientRect();
      const taRect = textarea.getBoundingClientRect();
      const top = taRect.top - hostRect.top;
      const left = taRect.left - hostRect.left;
      layer.style.top = `${top}px`;
      layer.style.left = `${left}px`;
      layer.style.width = `${taRect.width}px`;
      layer.style.height = `${taRect.height}px`;

      mirror.style.top = `${top}px`;
      mirror.style.left = `${left}px`;
      syncMirrorStyles(textarea, mirror);
      mirror.style.width = `${textarea.clientWidth}px`;
    };

    const setCaretVisible = (show: boolean) => {
      if (visibleRef.current === show) return;
      visibleRef.current = show;
      const el = caretRef.current;
      if (el) el.style.visibility = show ? "visible" : "hidden";
    };

    const scheduleMeasure = () => {
      dirtyRef.current = true;
      if (rafRef.current !== null) return;
      rafRef.current = requestAnimationFrame(() => {
        rafRef.current = null;
        if (!dirtyRef.current) return;
        dirtyRef.current = false;

        if (!focusedRef.current || composingRef.current || disabled || textarea.disabled) {
          targetRef.current = null;
          setCaretVisible(false);
          return;
        }

        syncGeometry();
        const next = measureCaretInTextarea(textarea, mirror);
        if (!next) {
          targetRef.current = null;
          setCaretVisible(false);
          return;
        }

        const prev = targetRef.current;
        targetRef.current = next;
        if (!displayRef.current) displayRef.current = { ...next };
        setCaretVisible(true);

        if (
          prev &&
          (Math.abs(prev.x - next.x) > MOVE_THRESHOLD || Math.abs(prev.y - next.y) > MOVE_THRESHOLD)
        ) {
          const trails = trailsRef.current;
          trails.push({ x: displayRef.current!.x, y: displayRef.current!.y, h: displayRef.current!.h, life: 1 });
          while (trails.length > TAIL_SEGMENTS) trails.shift();
        }
      });
    };

    const onFocus = () => {
      focusedRef.current = true;
      syncGeometry();
      scheduleMeasure();
      document.addEventListener("selectionchange", onSelectionChange);
    };
    const onBlur = () => {
      focusedRef.current = false;
      targetRef.current = null;
      setCaretVisible(false);
      trailsRef.current = [];
      document.removeEventListener("selectionchange", onSelectionChange);
    };
    const onSelectionChange = () => {
      if (document.activeElement === textarea) scheduleMeasure();
    };
    const onCompositionStart = () => {
      composingRef.current = true;
      setCaretVisible(false);
    };
    const onCompositionEnd = () => {
      composingRef.current = false;
      scheduleMeasure();
    };

    const ro = new ResizeObserver(() => {
      syncGeometry();
      scheduleMeasure();
    });
    ro.observe(textarea);
    ro.observe(host);

    const events = ["input", "keydown", "keyup", "click", "scroll"] as const;
    for (const type of events) textarea.addEventListener(type, scheduleMeasure);
    textarea.addEventListener("focus", onFocus);
    textarea.addEventListener("blur", onBlur);
    textarea.addEventListener("compositionstart", onCompositionStart);
    textarea.addEventListener("compositionend", onCompositionEnd);

    if (document.activeElement === textarea) onFocus();

    const tick = () => {
      animRef.current = requestAnimationFrame(tick);
      if (!focusedRef.current) return;

      const caret = caretRef.current;
      const target = targetRef.current;
      const display = displayRef.current;
      if (!caret || !target || !display || !visibleRef.current) return;

      display.x = lerp(display.x, target.x, LERP);
      display.y = lerp(display.y, target.y, LERP);
      display.h = lerp(display.h, target.h, LERP);
      caret.style.transform = `translate3d(${display.x}px, ${display.y}px, 0)`;
      caret.style.height = `${display.h}px`;

      const moving =
        Math.abs(display.x - target.x) > 0.6 ||
        Math.abs(display.y - target.y) > 0.6;
      caret.classList.toggle("idle", !moving);

      const trails = trailsRef.current;
      for (let i = trails.length - 1; i >= 0; i--) {
        const t = trails[i];
        t.life -= 0.14;
        if (t.life <= 0) trails.splice(i, 1);
      }
      const hostEl = caret.parentElement;
      if (!hostEl) return;
      const trailEls = hostEl.querySelectorAll<HTMLElement>("[data-caret-trail]");
      trailEls.forEach((el, i) => {
        const t = trails[i];
        if (!t) {
          el.style.opacity = "0";
          return;
        }
        el.style.transform = `translate3d(${t.x}px, ${t.y}px, 0)`;
        el.style.height = `${t.h}px`;
        el.style.opacity = String(t.life * 0.55);
      });
    };
    animRef.current = requestAnimationFrame(tick);

    return () => {
      if (rafRef.current !== null) cancelAnimationFrame(rafRef.current);
      if (animRef.current !== null) cancelAnimationFrame(animRef.current);
      ro.disconnect();
      for (const type of events) textarea.removeEventListener(type, scheduleMeasure);
      textarea.removeEventListener("focus", onFocus);
      textarea.removeEventListener("blur", onBlur);
      textarea.removeEventListener("compositionstart", onCompositionStart);
      textarea.removeEventListener("compositionend", onCompositionEnd);
      document.removeEventListener("selectionchange", onSelectionChange);
      mirror.remove();
      mirrorRef.current = null;
    };
  }, [mode, enabled, disabled, textareaRef, hostRef]);

  if (mode !== "animated" || !enabled) return null;

  return (
    <Box
      ref={layerRef}
      aria-hidden
      sx={{
        position: "absolute",
        pointerEvents: "none",
        overflow: "hidden",
        zIndex: 1,
        "& [data-caret-trail], & [data-caret-core]": {
          position: "absolute",
          left: 0,
          top: 0,
          width: "2px",
          borderRadius: "1px",
          willChange: "transform, opacity",
          background: "linear-gradient(180deg, #0c5033 0%, #126c46 100%)",
        },
        "& [data-caret-core]": {
          visibility: "hidden",
          boxShadow: "0 0 6px rgba(18, 108, 70, 0.45)",
        },
        "& [data-caret-core].idle": {
          animation: "mainChatCaretBlink 1.05s step-end infinite",
        },
        "@keyframes mainChatCaretBlink": {
          "0%, 49%": { opacity: 1 },
          "50%, 100%": { opacity: 0.2 },
        },
      }}
    >
      {Array.from({ length: TAIL_SEGMENTS }, (_, i) => (
        <div key={i} data-caret-trail style={{ opacity: 0 }} />
      ))}
      <div ref={caretRef} data-caret-core className="idle" />
    </Box>
  );
}
