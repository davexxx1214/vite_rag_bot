export type InputOption = { id: string; label: string; value: string; description: string };
export type InputRequest = {
  requestId: string;
  status: "pending" | "answered" | "cancelled" | "expired";
  reason: string;
  question: string;
  options: InputOption[];
  allowFreeText: boolean;
  createdAt: string;
  expiresAt: string;
};
export type InputAnswer = { optionId?: string; text?: string; cancelled?: boolean };

export function canAnswer(request: InputRequest, now = Date.now()): boolean {
  return request.status === "pending" && Number.isFinite(Date.parse(request.expiresAt)) && now < Date.parse(request.expiresAt);
}

export function makeInputAnswer(request: InputRequest, selected: string, text: string): InputAnswer | null {
  if (!canAnswer(request)) return null;
  if (selected === "custom") {
    const value = text.trim();
    return request.allowFreeText && value.length > 0 && value.length <= 500 ? { text: value } : null;
  }
  return request.options.some(option => option.id === selected) ? { optionId: selected } : null;
}
