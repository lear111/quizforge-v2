FROM node:22-bookworm-slim AS frontend
WORKDIR /frontend
COPY package.json package-lock.json ./
RUN npm ci --ignore-scripts --no-audit --no-fund
COPY . ./
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=frontend /frontend/.generated-sdk /usr/share/nginx/sdk
COPY --from=frontend /frontend/LICENSE.upstream /usr/share/nginx/html/LICENSE.upstream
COPY --from=frontend /frontend/public /usr/share/nginx/html
COPY --from=frontend /frontend/practice /usr/share/nginx/html/practice
COPY --from=frontend /frontend/shared /usr/share/nginx/html/shared
