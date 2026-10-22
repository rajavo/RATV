FROM node:22-alpine

# ffmpeg remuxes the channels (no re-encoding); tzdata gives the guide its local times.
RUN apk add --no-cache ffmpeg tzdata

WORKDIR /app
COPY package.json server.js ./
COPY lib ./lib
COPY public ./public

ENV NODE_ENV=production \
    PORT=8080 \
    DATA_DIR=/app/data \
    TZ=Asia/Riyadh

EXPOSE 8080
VOLUME ["/app/data"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/healthz >/dev/null || exit 1

CMD ["node", "server.js"]
