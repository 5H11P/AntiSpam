# AntiSpam

AntiSpam 是一个适用于 **Spigot 1.8.8** 的聊天管理插件，用于限制发言频率、拦截短时间内重复或高度相似的消息，以及阻止包含服务器 IP 或域名的广告。项目以 Java 8 为编译目标，当前版本为 `1.0.0`。

## 功能

- **聊天冷却**：普通玩家默认每 3 秒可发送一条消息；可按权限组设置更短的冷却时间，或通过权限完全跳过冷却。
- **相似消息检测**：去除颜色代码和标点、统一大小写与空白后，使用编辑距离计算相似度。默认在 3 秒的检测窗口内允许同一组相似消息出现 3 次，第 4 次起拦截。
- **广告拦截**：识别 IPv4、带端口的 IPv4 地址，以及指定后缀的域名；支持地址白名单，并处理部分空格、括号点号等写法。
- **独立开关与豁免权限**：三类检查均可单独启停，也可以分别为玩家授予豁免权限。
- **热重载配置**：通过命令重新读取配置，并清空当前保存的玩家聊天状态。

## 构建与安装

准备 JDK 8 或更新版本，以及 Maven。项目依赖由服务器提供的 Spigot 1.8.8 API，Maven 首次构建时需要访问 `pom.xml` 中配置的 Spigot 仓库。

```bash
mvn clean package
```

构建产物为 `target/AntiSpam-1.0.0.jar`。将其放入服务端的 `plugins/` 目录并重启服务器。首次启动后，插件会生成 `plugins/AntiSpam/config.yml`；修改配置后可执行 `/antispam reload`。

## 命令与权限

| 命令 | 作用 | 所需权限 |
| --- | --- | --- |
| `/antispam reload`（或 `/as reload`） | 重载配置并清空聊天状态 | `antispam.reload` |

| 权限 | 作用 | `plugin.yml` 默认值 |
| --- | --- | --- |
| `antispam.bypass.all` | 跳过所有检查 | `op` |
| `antispam.bypass.cooldown` | 跳过聊天冷却 | `op` |
| `antispam.bypass.spam` | 跳过相似消息检测 | `op` |
| `antispam.bypass.advertise` | 跳过 IP 和域名检测 | `op` |
| `antispam.cooldown.vip` | 使用默认 1 秒冷却 | `false` |
| `antispam.cooldown.admin` | 使用默认 0 秒冷却 | `op` |
| `antispam.reload` | 允许执行重载命令 | `op` |

权限节点可以在 `config.yml` 中修改，但如果使用自定义节点，也应在所用的权限插件中为相应玩家或权限组授予该节点。

## 配置说明

完整示例及默认值见 [`src/main/resources/config.yml`](src/main/resources/config.yml)。以下列出主要配置项：

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `cooldown.enabled` | `true` | 启用聊天冷却 |
| `cooldown.default-seconds` | `3` | 普通玩家冷却时间，单位为秒 |
| `cooldown.permission-groups` | `vip: 1`、`admin: 0` | 根据权限设置冷却；同时拥有多个组权限时取最短时间 |
| `anti-spam.enabled` | `true` | 启用相似消息检测 |
| `anti-spam.max-repeat-times` | `3` | 相似消息的允许次数，超过后拦截 |
| `anti-spam.reset-spam-time` | `3` | 重复计数的重置间隔，单位为秒 |
| `anti-spam.similarity-threshold` | `0.85` | 相似度阈值，范围为 `0.0`～`1.0` |
| `anti-spam.min-message-length` | `3` | 低于该长度的规范化消息不参与相似度检测 |
| `anti-spam.history-size` | `5` | 每名玩家保留的近期消息条数；当前相似度判断并不读取这份历史记录 |
| `anti-advertise.enabled` | `true` | 启用广告拦截 |
| `anti-advertise.check-ip` | `true` | 检测 IPv4 地址 |
| `anti-advertise.check-address-with-port` | `true` | 检测带端口的 IPv4 地址 |
| `anti-advertise.check-domain` | `true` | 检测域名 |
| `anti-advertise.blocked-tlds` | 见配置文件 | 需要检测的域名后缀，例如 `com`、`net`、`cn` |
| `anti-advertise.whitelist` | 示例地址 | 允许出现的域名或 IP；部署时请改成自己服务器的地址 |

白名单中的域名按完整地址匹配：允许 `example.com` 不会自动允许 `play.example.com`，需要分别添加。白名单中的 IPv4 地址也可放行该地址附带端口的写法。提示消息位于 `messages` 节点，支持 `&` 颜色代码和 `%prefix%` 占位符；冷却提示还支持 `%seconds%`。

## 检测行为

每条聊天消息按 **广告 → 相似消息 → 冷却** 的顺序检查，命中后取消发送并向玩家显示相应提示。相似消息检测发生在冷却检查之前，因此即使消息最终因冷却被拦截，它也可能计入重复次数。冷却时间以最后一条通过所有检查的消息为起点。玩家退出服务器或执行配置重载时，其缓存的聊天状态会被清除。

项目入口位于 [`AntiSpamPlugin.java`](src/main/java/cn/antispam/AntiSpamPlugin.java)，聊天检测逻辑位于 [`ChatListener.java`](src/main/java/cn/antispam/listener/ChatListener.java)。
