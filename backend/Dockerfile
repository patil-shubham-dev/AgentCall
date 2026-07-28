# syntax=docker/dockerfile:1
# Multi-stage build for VoiceBridge backend

# Stage 1: Install dependencies
FROM node:26-alpine AS deps
WORKDIR /app
RUN apk add --no-cache libc6-compat
COPY package.json package-lock.json ./
RUN npm ci --omit=dev && \
    cp -R node_modules /prod_modules && \
    npm ci

# Stage 2: Build
FROM node:26-alpine AS builder
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY package.json package-lock.json ./
COPY tsconfig.json ./
COPY src/ ./src/
COPY scripts/ ./scripts/
RUN npm run build

# Stage 3: Production image
FROM node:26-alpine AS runner
WORKDIR /app

# Non-root user
RUN addgroup --system --gid 1001 appgroup && \
    adduser --system --uid 1001 --ingroup appgroup appuser

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
