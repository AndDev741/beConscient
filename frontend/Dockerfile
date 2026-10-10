FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY tsconfig.json vite.config.ts index.html ./
COPY public ./public
COPY src ./src
RUN npm run build

FROM nginx:1.29-alpine
# Where /api/ and /ingest/ go. Override at run time, e.g. -e BACKEND_UPSTREAM=backend:8080
ENV BACKEND_UPSTREAM=beconscient-backend:80 \
    NGINX_ENVSUBST_FILTER=^BACKEND_UPSTREAM$
RUN rm -f /etc/nginx/conf.d/default.conf
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY nginx/security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
