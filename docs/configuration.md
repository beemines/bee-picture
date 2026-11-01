# 配置指南

## 后端环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SERVER_ADDRESS` | `127.0.0.1` | 后端监听地址 |
| `SERVER_PORT` | `8123` | API 端口，统一前缀 `/api` |
| `DB_URL` | 本机 `bee_picture` 数据库 | 完整 MySQL JDBC URL |
| `DB_USERNAME` | `root` | 本地账号，部署时改为最小权限业务账号 |
| `DB_PASSWORD` | 空 | 自己的数据库密码 |
| `REDIS_HOST` / `REDIS_PORT` | `127.0.0.1` / `6379` | Redis 地址 |
| `REDIS_DATABASE` / `REDIS_PASSWORD` | `0` / 空 | Redis 逻辑库与密码 |
| `COS_HOST` | 空，必填 | 图片访问域名，含 `https://`，不带末尾斜线 |
| `COS_BUCKET` | 空，必填 | 自己的 COS 存储桶标识 |
| `COS_REGION` | `ap-guangzhou` | 必须与自己的存储桶一致 |
| `COS_SECRET_ID` / `COS_SECRET_KEY` | 空，必填 | 后端专用 COS 凭据 |
| `ALIYUN_AI_API_KEY` | 空 | 仅 AI 扩图需要 |
| `CORS_ALLOWED_ORIGINS` | 本机前端地址 | 逗号分隔的明确来源，不使用携带凭据的通配符 |
| `API_DOCS_ENABLED` | `true` | Knife4j 开关，公网部署应另行限制文档访问 |

Spring Boot 直接读取环境变量。仓库不把后端 `.env` 当作自动加载机制。可选本地 YAML 覆盖文件必须使用 `--spring.profiles.active=local`；其名称已列入 `.gitignore`。

## 前端环境变量

前端变量会进入浏览器代码，只能填写公开地址，**不能放任何云密钥**。

| 变量 | 默认行为 | 用途 |
| --- | --- | --- |
| `VITE_API_BASE_URL` | 同源 | Axios 请求地址，不附加 `/api`，接口函数自身包含前缀 |
| `VITE_API_PROXY_TARGET` | 本机后端 `8123` | 仅 Vite 开发代理生效，文档演示改为 `8130` |
| `VITE_WS_BASE_URL` | 当前页面 origin | WebSocket 自动选择 ws / wss，并访问 `/api/ws/picture/edit` |
| `VITE_IMAGE_ORIGIN` | 不启用图片代理 | 自己的图片源 origin；开发时将同源图片转换为 `/image-proxy` 请求 |

前端配置变更后重启 Vite；生产地址在构建时注入。生产环境的图片处理需要对象源允许正确的 CORS，开发代理并不会随 `dist` 自动部署。

## COS 与 AI

使用自己的 COS 存储桶、访问域名和最小权限凭据。后端启动时检查必要参数；空配置会给出配置提示，不会回退到维护者的账号。

图片公开读取策略、跨域、图片处理能力及存储费用由使用者自行配置。AI 扩图的 Key 只存在于后端环境，需自行确认对应模型服务权限与预算。不要把公共素材图库的访问策略直接复制到包含私密内容的空间。

## 部署注意事项

- 静态站点对 Vue history 路由设置 `try_files` 回退到 `index.html`。
- 同域反向代理 `/api/` 到后端，保留路径；`/api/ws/` 支持 HTTP Upgrade 与 Connection 头。
- 默认监听本机，容器内如需使用 `0.0.0.0` 应只向受控反向代理暴露端口。
- 真实浏览器跨域部署需同时核对 Cookie、SameSite、HTTPS 与 `CORS_ALLOWED_ORIGINS`。
- 不公开 MySQL / Redis，不将开发用 `.env.local`、数据库导出或日志目录映射为静态资源。
- 初始化 SQL 仅用于空库，后续升级应使用经审阅的迁移脚本和备份流程。

## 常见问题

**图片列表有数据但图片不显示**：检查图片 URL、对象访问策略、跨域、存储桶区域、云账号状态和对象是否仍存在。后端健康不代表对象存储可用。

**Windows 运行命令过长**：使用 README 的 `mvn package` 后 `java -jar` 方式。

**文档演示中的按钮提示只读**：这是预期行为。演示数据服务只负责展示真实界面，不实现真实登录、上传、删除或 AI 任务。
