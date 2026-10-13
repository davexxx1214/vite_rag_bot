# Answer me with HTML browser integration

Unmodified upstream source, version 0.5.2, commit
`4da5ae89ac435683110aa1b8707d9d4170184c55`:
https://github.com/QingYunA/answer-me-with-html

Licensed under MIT; see LICENSE. Dependencies are pinned in the workspace lockfile.

`scripts/build-visualization.mjs` bundles the page renderer for the browser. Its
build adapters inline assets, disable filesystem images and user themes, and
render pasted code without reading source files. The CLI, updates, housekeeping,
video generation and agent skill instructions are not executed by the bot.

The inline renderer loads only when an assistant message contains a complete
supported visual fence. The original Markdown remains the conversation record.
Each block sanitizes HTML/SVG and uses a shadow root to isolate styles and SVG IDs.
Inline blocks do not execute upstream or answer-supplied scripts. Invalid blocks
fall back to their original code while surrounding text continues streaming.
