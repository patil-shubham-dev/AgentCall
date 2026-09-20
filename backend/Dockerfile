# syntax=docker/dockerfile:1
# Multi-stage build for VoiceBridge backend

# Stage 1: Install dependencies
FROM node:26.8.2-alpine AS deps
WORKDIR /app
RUN apk add --no-cache libc6-compat
COPY package.json package-lock.json ./
RUN npm ci --omit=dev && \
    cp -R node_modules /prod_modules && \
    npm ci

# Stage 2: Build
FROM node:26.8.2-alpine AS builder
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY package.json package-lock.json ./
COPY tsconfig.json ./
COPY src/ ./src/
COPY scripts/ ./scripts/
RUN npm run build

# Stage 3: Production image
FROM node:26.8.2-alpine AS runner
WORKDIR /app

# Non-root user
RUN addgroup --system --gid 1001 appgroup && \
    adduser --system --uid 1001 --ingroup appgroup appuser

# Render mounts Secret Files at /etc/secrets (group 1000) at runtime. Grant
# the app user read access WITHOUT baking any secret into the image (a COPY
# would leave key material extractable in layer history forever). Uses the
# existing gid-1000 group when the base image provides one, else creates it.
RUN if getent group 1000 >/dev/null; then \
      addgroup appuser "$(getent group 1000 | cut -d: -f1)"; \
    else \
      addgroup -g 1000 secrets && addgroup appuser secrets; \
    fi

# Copy production dependencies
COPY --from=deps /prod_modules ./node_modules

# Copy compiled output
COPY --from=builder /app/dist ./dist
COPY --from=builder /app/scripts ./scripts
COPY --from=builder /app/src/voicebridge/repositories/schema.sql ./src/voicebridge/repositories/schema.sql
COPY package.json ./

# Read-only filesystem
RUN chown -R appuser:appgroup /app && \
    chmod -R 755 /app && \
    chmod 755 /app/node_modules/.bin/*

USER appuser

ENV NODE_ENV=production
EXPOSE 4000

HEALTHCHECK --interval=10s --timeout=5s --start-period=10s --retries=3 \
    CMD node -e "require('http').get('http://localhost:4000/api/v1/health', r => { process.exit(r.statusCode === 200 ? 0 : 1) }).on('error', () => process.exit(1))"

CMD ["./scripts/start.sh"]
