# Zero-config Railway deploy for the OpenWispr landing page.
# Railway detects this Dockerfile at the repo root and builds it directly —
# no "Root Directory" setting, no Nixpacks/Railpack provider detection.
# The server reads $PORT (Railway injects it) and binds 0.0.0.0.
FROM node:20-alpine

WORKDIR /app
COPY website/ ./website/

# documentation only; Railway routes to whatever $PORT the server binds
EXPOSE 3000

CMD ["node", "website/server.js"]
