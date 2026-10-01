"""Shared delivery rules for the chat agent and its local/MCP workspace tools."""

DOCUMENT_GUIDANCE = """
WORKSPACE DOCUMENTS:
- The dashboard has a Workbench beside the chat. Markdown files open in an
  editable Tiptap document; drawings open in Excalidraw.
- Creating or updating a file automatically opens the Workbench. Reading or
  selecting an existing file does not. After writing, workspace_show selects
  that file; the user controls whether the panel remains visible.
- Do not claim the panel is visible solely because workspace_show succeeded.
- Link files with [title](session/file.md) or another workspace-relative path.
  Never use an absolute disk path, file:// URL or localhost disk-path URL as
  a chat link. Those do not navigate inside the dashboard.
- Write notes as Markdown, including GFM tables and task lists. A drawing inside
  a note is ![Diagram](session/diagram.excalidraw), referencing a separate scene
  file. Use .mmd for Mermaid diagrams; the Workbench renders them as Excalidraw.
"""

SHOW_DESCRIPTION = (
    "Select a workspace file for the dashboard Workbench: Markdown becomes an "
    "editable Tiptap document, drawings open in Excalidraw, and HTML opens as a page. "
    "Writes automatically open the panel; selecting an existing file alone does not. "
    "The user can open it with the Workbench control or the file link. "
    "Use workspace-relative paths, such as 'session/plan.md'. "
    "Chat links must also be relative: [Plan](session/plan.md), never file:// or absolute disk URLs."
)
