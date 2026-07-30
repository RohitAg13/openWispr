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

## Domain

The canonical host is **`openwispr.dev`**. It is set on the Railway service under
**Settings → Networking → Custom Domain**, which issues the certificate and prints
the DNS record to create at the registrar (a `CNAME` for `www`, or Railway's
ALIAS/ANAME target for the apex — an apex `CNAME` is not valid DNS and registrars
that appear to accept one are doing something proprietary).

The generated `*.up.railway.app` subdomain keeps working after the custom domain is
attached; Railway does not retire it. So three hosts can serve identical content —
apex, `www`, and the Railway subdomain — which is a split ranking, not redundancy.
Both pages therefore carry a `<link rel="canonical">` pointing at the apex, and
Plausible's `data-domain` is set to `openwispr.dev`.

To make the consolidation real rather than advisory, set **`CANONICAL_HOST`** on the
Railway service:

```
CANONICAL_HOST=openwispr.dev
```

`server.js` then 301s every other host to it. It is unset by default on purpose —
enabling it before the apex resolves would redirect the working `www` host to a dead
one and take the site down. Check first:

```bash
dig +short openwispr.dev      # must return an answer before enabling
```

301s are cached aggressively by browsers, so a wrong value here is expensive to undo.

## Editing

`index.html` is fully self-contained: all styles are inline and the four
interactive widgets (capability explorer, voice-cleanup toggle, select-&-polish
tabs, FAQ accordion) are driven by a small vanilla-JS block at the bottom of the
file. Fonts load from Google Fonts. There is no build step.

Download/Star links point at the GitHub repo and its Releases page — update them
in `index.html` if the canonical repo URL changes.
