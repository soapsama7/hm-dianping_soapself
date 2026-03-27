FROM nginx:1.25-alpine

COPY nginx.conf /etc/nginx/nginx.conf

# 把 hmdp 静态站点放到 nginx 默认根目录
COPY html/hmdp/ /usr/share/nginx/html/
COPY html/50x.html /usr/share/nginx/html/50x.html

EXPOSE 8080
