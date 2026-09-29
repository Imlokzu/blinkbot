# Diagrams

Each diagram comes in three files:

| File | What it is for |
|---|---|
| `*.mmd` | The source, in Mermaid. GitHub renders it; edit this one first. |
| `*.excalidraw` | The same diagram as a hand-drawn Excalidraw scene, for editing by hand at excalidraw.com or in the dashboard. |
| `*.svg` | A static picture with the fonts embedded, for READMEs and the Obsidian vault. |

To redraw after changing the `.mmd`: paste it into Excalidraw's
"Mermaid to Excalidraw" tool (excalidraw.com → More tools), save the scene
over the `.excalidraw`, and export the SVG with the background on. The chat
workbench does the same conversion for files the bot writes.

- `architecture` — processes, ports and who talks to whom (matches the
  ports table in the owner's "Architecture" note).
