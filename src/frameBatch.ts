export function createFrameBatch(
  publish: () => void,
  requestFrame: (callback: () => void) => number,
  cancelFrame: (frame: number) => void,
) {
  let frame: number | null = null;
  const cancel = () => {
    if (frame === null) return;
    cancelFrame(frame);
    frame = null;
  };
  const flush = () => {
    if (frame === null) return;
    cancel();
    publish();
  };
  const schedule = () => {
    if (frame !== null) return;
    frame = requestFrame(() => {
      frame = null;
      publish();
    });
  };
  return { schedule, flush, cancel };
}
