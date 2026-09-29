---
name: excalidraw-diagrams
description: "Draw diagrams the user can see and edit: flowcharts, architectures, sequences, mind maps, sketches, whiteboard explanations. Use whenever someone asks to draw, sketch, diagram, visualise or explain something as a picture or scheme (схема, діаграма, намалюй, покажи на схемі) — write a .mmd or .excalidraw file, never ASCII art."
metadata:
  {
    "openclaw":
      {
        "emoji": "✏️",
      },
  }
---

# Excalidraw diagrams

The chat has a **workbench** beside it. Every file you write with
`workspace_write` opens there by itself, and two kinds of file are drawn as
hand-drawn Excalidraw sketches the user can move, edit and save:

| File | Use it for |
|---|---|
| `session/<name>.mmd` — Mermaid | **the default**: flowcharts, pipelines, architectures, sequences, class diagrams |
| `session/<name>.excalidraw` — a scene | free layouts Mermaid cannot do: mind maps, sketches, comparisons, timelines, a picture with positions that matter |

Write the file, then answer in one or two sentences — what the diagram shows
and that it is open beside the chat. **Do not paste the diagram code into the
reply** and do not describe how to open it: it is already on screen.

## Design before you write

A diagram should show something prose cannot: an order, a branch, what talks
to what. Before writing:

1. Name the one idea the picture carries. One diagram, one idea — split
   anything bigger.
2. Pick the direction: left→right for flows and pipelines, top→down for
   hierarchies and decisions.
3. **At most ~12 nodes.** Labels of 1–4 words; put the detail on a second
   line (`<br>` in Mermaid, `\n` in a scene), not in a sentence.
4. Use the real names — the actual endpoint, file, port or step — never
   "Component A" or "Service 1".
5. Labels in the user's language.

## Mermaid (`.mmd`)

```
flowchart LR
  user["Користувач<br>на телефоні"] -->|питання| bot["Клод Бот"]
  bot --> brain{"Потрібен<br>інструмент?"}
  brain -->|так| tool["Пошук"]
  brain -->|ні| reply["Відповідь"]
  tool --> reply
```

- Supported natively and drawn as editable shapes: `flowchart`,
  `sequenceDiagram`, `classDiagram`. Anything else (pie, gantt, mindmap…) is
  pasted in as a flat image — use a scene for those instead.
- Ids short and ASCII (`bot`, `db1`); the label goes in quotes, and quotes
  are required when a label has spaces, brackets or punctuation.
- Shapes: `[box]`, `(rounded)`, `{decision}`, `[(database)]`, `((circle))`.
  Dashed link `-.->`, labelled link `-->|text|`.
- `subgraph name ... end` groups nodes. Styling (`classDef`, `style`) is
  ignored by the converter — do not spend lines on it.

## Excalidraw scene (`.excalidraw`)

Write the **short skeleton form**; the workbench expands it into a full scene
(no `seed`, `version` or `nonce` — leave them out):

```json
{
  "type": "excalidraw",
  "elements": [
    { "type": "text", "x": 0, "y": -70, "text": "Як бот відповідає", "fontSize": 28 },
    { "type": "rectangle", "id": "ask", "x": 0, "y": 0, "width": 180, "height": 80,
      "backgroundColor": "#ffd8a8", "fillStyle": "solid", "label": { "text": "Питання" } },
    { "type": "ellipse", "id": "brain", "x": 280, "y": 0, "width": 180, "height": 80,
      "backgroundColor": "#e5dbff", "fillStyle": "solid", "label": { "text": "Мозок\nOpenClaw" } },
    { "type": "diamond", "id": "tool", "x": 560, "y": -10, "width": 160, "height": 100,
      "backgroundColor": "#ffec99", "fillStyle": "solid", "label": { "text": "Тул?" } },
    { "type": "arrow", "x": 180, "y": 40, "start": { "id": "ask" }, "end": { "id": "brain" } },
    { "type": "arrow", "x": 460, "y": 40, "start": { "id": "brain" }, "end": { "id": "tool" },
      "label": { "text": "так" } }
  ]
}
```

- Shapes: `rectangle`, `ellipse`, `diamond` — each needs `id`, `x`, `y`,
  `width`, `height`; text inside goes in `label.text`.
- `text` stands alone: headings, notes, captions. Not every word needs a box
  — a title or a note reads better as free text.
- `arrow` / `line`: `x`, `y` at the start; bind the ends with
  `start: {id}` / `end: {id}` and the arrow follows the shapes when the user
  moves them. `label.text` puts words on it; `strokeStyle: "dashed"` for
  optional or async links.
- Layout on a grid: columns ~280 px apart, rows ~160 px, boxes about
  180×80. Coordinates can be negative. Leave room — cramped reads worse
  than wide.

### Colour means something

| Meaning | backgroundColor |
|---|---|
| neutral step | `#f1f3f5` |
| start / input | `#ffd8a8` |
| done / output | `#b2f2bb` |
| decision | `#ffec99` |
| the AI, a model | `#e5dbff` |
| problem / error | `#ffc9c9` |
| storage, files | `#d0ebff` |

Use two or three of these per diagram, always with `fillStyle: "solid"`.
Leave strokes at the default dark colour.

## Changing a diagram

`workspace_read` the file, change it, write the **whole** file back — the
open tab reloads by itself.

The user may have edited and saved it in the workbench; such a file holds
full elements (with `version`, `seed`…). Then:

- keep those elements as they are; to rename one, change its `text` **and**
  `originalText` together (the label of a shape is a separate `text` element
  whose `containerId` is the shape's id);
- add new things in skeleton form next to them — they are expanded on open,
  and arrows between *new* shapes stay bound;
- an arrow from a new shape to an old one will not bind — point it there by
  coordinates, or for a big rework write a new file (`…-v2.excalidraw`)
  rather than breaking theirs.
