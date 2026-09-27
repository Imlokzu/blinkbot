# Lightweight unofficial YouTube clients and free transcribe (August 2026)

Answer to the request "find a mega-lightweight client for YouTube, something like NewPipe,
only lightweight and in React, to turn on videos for the bot, and a free
transcribe API so the bot 'listens' to this video". Here — what exists, what is chosen and how
to use the bot's already prepared endpoints from the React dashboard.

---

## 1. Transcribe (bot 'listens' to video) — CHOSEN ✅

**[youtube-transcript-api](https://pypi.org/project/youtube-transcript-api/)**
(Python, MIT, without key, without quotas):

- works with manual and **automatic** subtitles;
- language priority is set by a list (`["uk","en",…]`);
- in 2026 remains recommended #1 in all reviews of "free youtube
  transcript api" — see [comparison of approaches](https://outlierkit.com/resources/youtube-transcript-api/).

Already working in the bot:

```
GET /api/music/transcript?id=<id|URL>&lang=uk        # segments [{start,text}]
GET /api/music/transcript?id=…&text=1                # concatenated text (up to 4000 chars)
tool listen_to_video {url}                            # bot reads + plays sound on the screen
```

Fallback: if YouTube closed timedtext for the IP — the backend takes captions via
Invidious (`/api/v1/captions/...`, WebVTT is parsed by our own code in `music.py`).

## 2. YouTube client for React/JS — CHOSEN ✅ (two layers)

### 2.1. Search and metadata: yt-dlp on the backend (what is already in the bot)

**[yt-dlp](https://github.com/yt-dlp/yt-dlp)** — unofficial client #1:
search (`ytsearch5:query`), metadata, direct audio. Not "lightweight" in the sense of a
bundle, but it is on the BACKEND (Python), so the frontend gets a ready-made
micro-API:

```
GET /api/music/search?q=crab+rave&limit=5   → [{id,title,uploader,duration}]
GET /api/music/stream?provider=youtube&id=… → audio with Range (seeking)
```

This is the "NewPipe-like" client for the screen/dashboard — without any JS-SDK.

### 2.2. Pure JS/React: youtubei.js — "NewPipe in TypeScript"

**[YouTube.js (npm: youtubei.js)](https://github.com/LuanRT/YouTube.js/)**
LuanRT — full-featured wrapper around the private YouTube InnerTube API:
works in Node.js, Deno and [modern browsers](https://ytjs.dev/guide/getting-started),
without native dependencies. This is the closest thing to a "lightweight NewPipe in React":

```js
import { Innertube } from "youtubei.js";
const yt = await Innertube.create();
const results = await yt.search("crab rave");
const info = await yt.getInfo(results.videos[0].id);
const audioUrl = info.streaming_data?.adaptive_formats
  .find(f => f.has_audio && !f.has_video)?.url;
```

Nuances 2026 (important!):
- the browser mode requires a proxy through OUR OWN backend for the media links themselves
  (CORS + PO-token-gate googlevideo from client IPs);
- the API is unofficial — breaks when InnerTube changes, fixed by updating the package.

**When to choose youtubei.js:** if you want search/comments/queues right in the
React dashboard without Python. This is not needed for the bot's screen — everything there is already
via `/api/music/*`.

### 2.3. Invidious / Piped — REST without keys (fallback channel)

- **Invidious API**: `/api/v1/search`, `/api/v1/videos/{id}`, stream via
  `latest_version?id=…&itag=140&local=true` (the instance proxies through itself —
  bypasses IP-gate; this specific path is the main one in `music.py`).
- **Piped API**: `pipedapi.*/streams/{id}` returns `audioStreams[]`.
- The downside of both in 2026: **public instances flap** (502↔206 within
  a minute) and increasingly close search with captchas. That's why the bot has
  several + retries + auto-discovery of live ones from `api.invidious.io`.
  For production stability — your own instance in the local network.

## 3. Comparison (briefly)

| Option | Where it runs | Keys | Stability 2026 | When to use |
|---|---|---|---|---|
| yt-dlp (via our `/api/music/*`) | Python backend | no | high (updates itself) | bot screen, simple chat |
| youtubei.js | Node/browser | no | medium (InnerTube changes) | React dashboards with search/queues |
| Invidious/Piped API | third-party instances | no | flaps | fallback, own instance — fine |
| official YouTube Data API | anywhere | yes (quotas) | high | search-metadata only, NO media |

Recommendation for the project: **already implemented combination of yt-dlp (search) +
Invidious (stream) + youtube-transcript-api (transcribe)** on the backend, and
React clients (dashboard and future apps) consume it via
`/api/music/*`. Additional youtubei.js — only when something is needed that
is not in these endpoints (comments, subscriptions, client-side queues).

## 4. Example: React client on top of our endpoints

```jsx
function useSearch(q) {
  const [tracks, setTracks] = useState([]);
  useEffect(() => {
    if (!q) return;
    fetch(`/api/music/search?q=${encodeURIComponent(q)}`)
      .then(r => r.json()).then(d => setTracks(d.tracks))
      .catch(() => setTracks([]));
  }, [q]);
  return tracks;
}

// play with seeking: <audio src="/api/music/stream?provider=youtube&id=ID" />
// position/seeking — standard audio.currentTime + Range handled by backend
```

This is the "lightweight React client": zero YouTube SDK in the bundle, all the work with
unofficial APIs — on the backend, where it can be retried and cached.

## 5. Sources

- [LuanRT/YouTube.js](https://github.com/LuanRT/YouTube.js/) and
  [ytjs.dev — Getting Started](https://ytjs.dev/guide/getting-started)
- [youtube-transcript-api on PyPI](https://pypi.org/project/youtube-transcript-api/)
- [overview of free transcribe options 2026](https://outlierkit.com/resources/youtube-transcript-api/)
- [keyless extraction guide](https://use-apify.com/blog/how-to-extract-youtube-transcripts-2026)
- our code: `Virtual Bot/music.py`, `Virtual Bot/tools/music_tools.py`,
  `Virtual Bot/static/screen/screen.js` (Now Playing)
