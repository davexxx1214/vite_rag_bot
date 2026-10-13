const MAX_ANIMATED_TEXT = 512;
const MAX_ANIMATED_NODES = 24;
const segmenter = typeof Intl.Segmenter === "function"
  ? new Intl.Segmenter(undefined, { granularity: "grapheme" }) : null;

// Keep existing elements/text nodes where possible; streaming must not replay old text.
function reconcile(parent: Node, desired: Node) {
  let current = parent.firstChild;
  for (const incoming of Array.from(desired.childNodes)) {
    const next = current?.nextSibling ?? null;
    if (!current) {
      parent.appendChild(incoming.cloneNode(true));
      continue;
    }
    if (current.nodeType !== incoming.nodeType || current.nodeName !== incoming.nodeName) {
      parent.replaceChild(incoming.cloneNode(true), current);
    } else if (current instanceof Element && incoming instanceof Element) {
      for (const attribute of Array.from(current.attributes)) {
        if (!incoming.hasAttribute(attribute.name)) current.removeAttribute(attribute.name);
      }
      for (const attribute of Array.from(incoming.attributes)) {
        if (current.getAttribute(attribute.name) !== attribute.value) {
          current.setAttribute(attribute.name, attribute.value);
        }
      }
      reconcile(current, incoming);
    } else if (current.nodeValue !== incoming.nodeValue) {
      current.nodeValue = incoming.nodeValue;
    }
    current = next;
  }
  while (current) {
    const next = current.nextSibling;
    parent.removeChild(current);
    current = next;
  }
}

function unwrap(span: Element) {
  const parent = span.parentNode;
  if (!parent) return;
  span.replaceWith(...Array.from(span.childNodes));
  parent.normalize();
}

// html must already have passed through DOMPurify. Animation never schedules content.
export function updateMarkdown(root: HTMLElement, html: string, reveal: boolean) {
  // marked appends layout newlines after blocks. They are not an answer suffix:
  // "你好\n" -> "你好世界\n" must still count as appended visible text.
  const previous = (root.textContent ?? "").trimEnd();
  root.querySelectorAll("[data-chat-reveal]").forEach(unwrap);
  const template = document.createElement("template");
  template.innerHTML = html;
  const text = (template.content.textContent ?? "").trimEnd();
  const added = text.length - previous.length;
  // Formatting may remove Markdown delimiters. Show that revision immediately.
  const animate = reveal && added > 0 && added <= MAX_ANIMATED_TEXT
    && text.startsWith(previous) && segmenter !== null
    && !window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  reconcile(root, template.content);
  root.querySelectorAll("a").forEach(anchor => {
    anchor.target = "_blank";
    anchor.rel = "noopener noreferrer";
  });
  if (!animate) return;

  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  const nodes: Text[] = [];
  while (walker.nextNode()) nodes.push(walker.currentNode as Text);
  let offset = 0;
  let count = 0;
  for (const node of nodes) {
    const end = offset + node.length;
    let start = Math.max(0, previous.length - offset);
    offset = end;
    if (start >= node.length || count >= MAX_ANIMATED_NODES || !node.data.trim()) continue;
    // A delta can extend an emoji/combining character. Never split that glyph.
    const boundary = segmenter!.segment(node.data).containing(start);
    if (boundary && boundary.index < start) start = boundary.index + boundary.segment.length;
    if (start >= node.length) continue;
    const tail = start ? node.splitText(start) : node;
    const span = document.createElement("span");
    span.dataset.chatReveal = "";
    span.className = "chat-text-reveal";
    tail.replaceWith(span);
    span.appendChild(tail);
    span.addEventListener("animationend", () => unwrap(span), { once: true });
    count++;
  }
}
