# syntax=docker/dockerfile:1
# 构建上下文为仓库根目录： docker build -f docker/frontend.Dockerfile .

# ---- 构建阶段：Vite 打包静态资源 ----
FROM node:20-alpine AS build
WORKDIR /app
COPY frontend/package*.json ./
RUN npm ci --registry=https://registry.npmmirror.com
COPY frontend/ ./
RUN npm run build

# ---- 运行阶段：Nginx 托管静态资源 + 反向代理 /api ----
FROM nginx:1.27-alpine
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
