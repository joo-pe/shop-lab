FROM nginx:stable-alpine
COPY web/nginx.conf /etc/nginx/nginx.conf
COPY web/index.html web/styles.css web/app.js /usr/share/nginx/html/
EXPOSE 8080
