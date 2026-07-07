FROM node:20-alpine

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --only=production

COPY tsconfig.json ./
COPY src/ src/

RUN npm run build

USER node

EXPOSE 4000 4001

CMD ["node", "dist/index.js"]
