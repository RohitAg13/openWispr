# OpenWispr landing page

The marketing site for OpenWispr — a single, self-contained static page
(`index.html`) served by a tiny zero-dependency Node server (`server.js`).

```
website/
├── index.html     # the entire landing page (inline styles, vanilla-JS interactivity)
├── server.js      # zero-dependency static file server (binds to $PORT)
├── package.json   # `npm start` → node server.js
└── railway.json   # Railpack build + start command
```

> Deploy config also lives at the **repo root**: a `Dockerfile` + `.dockerignore`
> for the zero-config path (see *Deploy to Railway* below).

## Run locally

```bash
cd website
npm start          # → http://localhost:3000
```

No dependencies to install — `server.js` uses only Node's standard library
(Node 18+).

## Deploy to Railway

Two ways. Option A needs **no settings at all**.

### Option A — root Dockerfile (zero-config, recommended)

A `Dockerfile` at the **repo root** builds and starts this subfolder directly.
Railway auto-detects it — no Root Directory, no builder choice, no env vars:

1. **New Project → Deploy from GitHub repo** and pick this repository.
2. Done. Railway builds the Dockerfile and runs `node website/server.js`, which
   binds to the `PORT` Railway injects.

(A root `.dockerignore` keeps the build context to just `website/`. Trade-off: a
push to any path can trigger a rebuild — set **Settings → Build → Watch Paths**
to `website/**` to limit that.)

### Option B — Root Directory + Railpack

Point the service at this subfolder and let Railway's default builder (Railpack)
detect `package.json`. The Root Directory field is under the service
**Settings → Source** section (not under *Build*):

1. Service **Settings → Source → Root Directory** = `website`.
2. Railway uses `website/railway.json` (Railpack → `npm start`).

> Note: Railway's **Nixpacks** builder is legacy; this project targets **Railpack**
> (Option B) or a plain **Dockerfile** (Option A).

Add a custom domain under **Settings → Networking** when ready.

## Editing

`index.html` is fully self-contained: all styles are inline and the four
interactive widgets (capability explorer, voice-cleanup toggle, select-&-polish
tabs, FAQ accordion) are driven by a small vanilla-JS block at the bottom of the
file. Fonts load from Google Fonts. There is no build step.

Download/Star links point at the GitHub repo and its Releases page — update them
in `index.html` if the canonical repo URL changes.
