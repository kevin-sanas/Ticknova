FROM node:24.22.0-alpine@sha256:44fce696f38ba8c987c4db89954051000bbd05ffd8d8659ba15ed4a8602e937f AS build
WORKDIR /app
COPY frontend/package*.json ./
RUN npm ci --ignore-scripts
COPY frontend/ ./
RUN npm run build

FROM nginx:1.30.0-alpine@sha256:0272e4604ed93c1792f03695a033a6e8546840f86e0de20a884bb17d2c924883
COPY --from=build /app/dist /usr/share/nginx/html
ENV TICKNOVA_ENVIRONMENT=local
COPY deploy/nginx.conf /etc/nginx/templates/default.conf.template
EXPOSE 80
