# DataGate 前端多阶段构建（参考 DocLoom；node 20 + 淘宝镜像 + nginx）
FROM node:20 AS build
WORKDIR /app
# 淘宝 npm 镜像，国内装依赖快
RUN npm config set registry https://registry.npmmirror.com
COPY package.json ./
RUN npm install --no-audit --no-fund
COPY . .
# 生产构建 -> dist/
RUN npm run build:prod

# 运行阶段：nginx 托管静态资源 + 反代 /prod-api 到后端
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
