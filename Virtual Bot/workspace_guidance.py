"""Shared delivery rules for the chat agent and its local/MCP workspace tools."""

DOCUMENT_GUIDANCE = """
WORKSPACE DOCUMENTS:
- The dashboard has a Workbench beside the chat. Markdown files open in an
  editable Tiptap document; drawings open in Excalidraw.
- After creating a deliverable, call workspace_show with its workspace-relative
  path. Only say it is open after the tool succeeds.
- Link files with [title](session/file.md) or another workspace-relative path.
  Never use an absolute disk path, file:// URL or localhost disk-path URL as
  a chat link. Those do not navigate inside the dashboard.
- Write notes as Markdown, including GFM tables and task lists. A drawing inside
  a note is ![Diagram](session/diagram.excalidraw), referencing a separate scene
  file. Use .mmd for Mermaid diagrams; the Workbench renders them as Excalidraw.
"""

SHOW_DESCRIPTION = (
    "Open a workspace file inside the dashboard Workbench: Markdown becomes an "
    "editable Tiptap document, drawings open in Excalidraw, and HTML opens as a page. "
    "Call this after writing a deliverable or whenever the user asks to open/show it. "
    "Use workspace-relative paths, such as 'session/plan.md'. "
    "Chat links must also be relative: [Plan](session/plan.md), never file:// or absolute disk URLs."
)
