FROM node:24-alpine AS build

WORKDIR /workspace

COPY package.json package-lock.json ./
RUN npm ci

COPY angular.json tsconfig.json tsconfig.app.json ./
COPY public ./public
COPY src ./src

RUN npm run build -- --configuration production

FROM nginxinc/nginx-unprivileged:alpine

ENV NGINX_ENVSUBST_FILTER=^(API_UPSTREAM|GOOGLE_CALLBACK_ORIGIN)$

RUN rm -f /etc/nginx/conf.d/default.conf

COPY deploy/nginx.conf.template /etc/nginx/templates/default.conf.template
COPY deploy/snippets/novel-security-headers.conf /etc/nginx/snippets/novel-security-headers.conf
COPY --chmod=0555 deploy/validate-runtime-env.sh /docker-entrypoint.d/15-validate-novel-env.sh
COPY --from=build --chown=101:101 /workspace/dist/web/browser /usr/share/nginx/html

EXPOSE 8080
