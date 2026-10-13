export type CaretPoint = { x: number; y: number; h: number };

export type AnimatedCaretMode = "animated" | "native";

const COARSE = "(pointer: coarse)";
const REDUCED = "(prefers-reduced-motion: reduce)";

export function resolveAnimatedCaretMode(options: {
  disabled?: boolean;
  forceNative?: boolean;
  forceAnimated?: boolean;
}): AnimatedCaretMode {
  if (options.forceAnimated) return "animated";
  if (options.forceNative || options.disabled) return "native";
  if (typeof window === "undefined") return "native";
  if (window.matchMedia(COARSE).matches) return "native";
  if (window.matchMedia(REDUCED).matches) return "native";
  return "animated";
}

const MIRROR_STYLE_KEYS: readonly string[] = [
  "boxSizing",
  "width",
  "borderTopWidth",
  "borderRightWidth",
  "borderBottomWidth",
  "borderLeftWidth",
  "paddingTop",
  "paddingRight",
  "paddingBottom",
  "paddingLeft",
  "fontStyle",
  "fontVariant",
  "fontWeight",
  "fontStretch",
  "fontSize",
  "fontSizeAdjust",
  "fontFamily",
  "lineHeight",
  "textAlign",
  "textTransform",
  "textIndent",
  "textDecoration",
  "letterSpacing",
  "wordSpacing",
  "tabSize",
  "MozTabSize",
  "whiteSpace",
  "wordWrap",
  "overflowWrap",
  "wordBreak",
];

export function syncMirrorStyles(textarea: HTMLTextAreaElement, mirror: HTMLDivElement) {
  const computed = getComputedStyle(textarea);
  for (const key of MIRROR_STYLE_KEYS) {
    // @ts-expect-error mirrored style keys
    mirror.style[key] = computed[key];
  }
  mirror.style.position = "absolute";
  mirror.style.top = "0";
  mirror.style.left = "0";
  mirror.style.visibility = "hidden";
  mirror.style.pointerEvents = "none";
  mirror.style.overflow = "hidden";
  mirror.style.height = "auto";
  mirror.style.minHeight = "0";
  mirror.style.maxHeight = "none";
  mirror.style.borderStyle = "solid";
  mirror.style.whiteSpace = "pre-wrap";
}

let markerSpan: HTMLSpanElement | null = null;

function getMarker(): HTMLSpanElement {
  if (!markerSpan) {
    markerSpan = document.createElement("span");
    markerSpan.textContent = "\u200b";
  }
  return markerSpan;
}

/** One layout read: caret offset inside the textarea content box (scroll-adjusted). */
export function measureCaretInTextarea(
  textarea: HTMLTextAreaElement,
  mirror: HTMLDivElement,
): CaretPoint | null {
  const start = textarea.selectionStart;
  const end = textarea.selectionEnd;
  if (start !== end) return null;

  const value = textarea.value;
  const before = value.slice(0, start);

  mirror.replaceChildren(document.createTextNode(before), getMarker());
  const marker = mirror.lastElementChild as HTMLSpanElement;
  const markerRect = marker.getBoundingClientRect();
  const mirrorRect = mirror.getBoundingClientRect();

  const x = markerRect.left - mirrorRect.left - textarea.scrollLeft;
  const y = markerRect.top - mirrorRect.top - textarea.scrollTop;
  const h = markerRect.height || parseFloat(getComputedStyle(textarea).lineHeight) || 16;

  return { x, y, h };
}

export function lerp(a: number, b: number, t: number) {
  return a + (b - a) * t;
}
