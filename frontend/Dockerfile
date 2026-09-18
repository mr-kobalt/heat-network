# Многоступенчатая сборка визуализатора (ADR-0013).
FROM docker.io/library/node:22-alpine AS build
WORKDIR /app
RUN corepack enable

COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile

COPY . .
ARG VITE_API_BASE_URL=/
ARG VITE_BASEMAP_STYLE_URL=
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
ENV VITE_BASEMAP_STYLE_URL=$VITE_BASEMAP_STYLE_URL
RUN pnpm build

FROM docker.io/library/nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
