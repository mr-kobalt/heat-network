# Многоступенчатая сборка визуализатора (ADR-0013).
# Контекст сборки — корень репозитория: `docs/` подключается реестром
# документации фронтенда (ADR-0072).
FROM docker.io/library/node:22-alpine AS build
WORKDIR /workspace/frontend
RUN corepack enable

COPY frontend/package.json frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile

COPY frontend/ ./
COPY docs/ /workspace/docs/
ARG VITE_API_BASE_URL=
ARG VITE_BASEMAP_STYLE_URL=
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
ENV VITE_BASEMAP_STYLE_URL=$VITE_BASEMAP_STYLE_URL
RUN pnpm build

FROM docker.io/library/nginx:1.27-alpine
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/frontend/dist /usr/share/nginx/html
EXPOSE 80
