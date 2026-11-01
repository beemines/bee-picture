# 参与贡献

欢迎通过 Issue 讨论缺陷和改进，再提交聚焦于一个问题的 Pull Request。

1. Fork 仓库，创建功能分支。
2. 根据 README 配置本地环境，所有凭据保留在本机。
3. 后端执行 `mvn -DskipTests package`，前端执行 `npm ci && npm run build-only`；涉及类型的修改还应执行 `npm run type-check`。
4. 如修改接口或配置，同步更新文档和脱敏示例；涉及行为的修改补充有意义的测试。
5. 执行 `python scripts/check_release.py`，检查没有新增私密值和失效的 README 图片链接。
6. PR 描述写清问题、变更、验证及未完成的外部服务验证。

`docs/demo` 中的账号、数据和 SVG 插画均为演示内容；请勿用真实用户数据替换。提交代码表示你有权按本仓库 MIT 许可贡献该内容。第三方素材必须注明来源与许可证。
