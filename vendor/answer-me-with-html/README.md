# Answer me with HTML browser integration

Unmodified upstream source, version 0.5.2, commit
`4da5ae89ac435683110aa1b8707d9d4170184c55`:
https://github.com/QingYunA/answer-me-with-html

Licensed under MIT; see LICENSE. Dependencies are pinned in the workspace lockfile.

`scripts/build-visualization.mjs` bundles the page renderer for the browser. Its
build adapters inline assets, disable filesystem images and user themes, and
render pasted code without reading source files. The CLI, updates, housekeeping,
video generation and agent skill instructions are not executed by the bot.

The renderer and preview component load only when a reader opens a visual answer.
The original Markdown remains the conversation record. The preview sanitizes the
document before adding the trusted upstream runtime, and displays it in an opaque
sandbox with a restrictive CSP.
