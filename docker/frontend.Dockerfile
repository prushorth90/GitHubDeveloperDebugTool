FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
COPY frontend/package.json ./frontend/package.json
COPY infrastructure/package.json ./infrastructure/package.json
RUN npm ci --workspace frontend --no-audit --no-fund
COPY frontend ./frontend
RUN npm run build --workspace frontend

FROM nginx:stable-alpine
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/frontend/dist /usr/share/nginx/html
EXPOSE 80
