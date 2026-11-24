# 第三方组件说明

本项目使用下列主要开源组件。各依赖的完整许可证及声明保留在 target/lib 中对应组件 JAR 的 META-INF 目录或前端分发文件中。

| 组件 | 用途 | 许可证 |
|---|---|---|
| Vue 3.5.13 | 浏览器界面与交互 | MIT |
| Spring Boot / Spring Framework / Spring Security Crypto | HTTP 服务、事务、数据库访问、密码摘要 | Apache License 2.0 |
| H2 | 本地持久化演示数据库 | MPL 2.0 或 EPL 1.0 |
| MySQL Connector/J | 可选 MySQL 连接驱动 | GPL v2，附 Universal FOSS Exception |
| Jackson | JSON 序列化 | Apache License 2.0 |
| Apache Tomcat | 嵌入式 HTTP 容器 | Apache License 2.0 |

前端 Vue 分发文件保留了原始版权头。其他传递依赖以各自随附声明为准。源码交付不包含 Java 运行时或 Maven，使用者按其发行条款安装开发环境。
# 分布式 KV 扩展

Apache Ratis 3.3.1，Apache License 2.0，https://ratis.apache.org/ 。本项目使用其选主、日志复制、Raft 客户端和状态机接口。KV 数据结构、原子条件更新、请求结果去重、快照内容、业务接口和实验脚本位于本项目源码中。运行依赖的许可文件保留在相应 JAR 中。

