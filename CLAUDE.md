# GreenV — Claude Code

@AGENTS.md

The shared agreement above is the whole agreement; this file only adds what is specific to Claude
Code.

- **Verify in the browser pane rather than asking the user to look.** Start `apps/web` with the
  preview tool, never with a shell command, and screenshot the result.
- **`measurement/` carries its own Claude Code notes** in `measurement/CLAUDE.md`, including a
  design-review skill that grades Verge Studio against Verge Studio's own reference captures. That
  skill does not apply to `apps/web` or `apps/mobile`; they have no visual reference yet.
- **Start a session in the directory you are working in.** Starting in `apps/web/` or `services/`
  keeps the measurement subtree's 190-line agreement out of context, which is most of it.
