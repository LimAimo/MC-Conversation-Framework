# MC Conversation Framework (MCCF)

**[English README](README_EN.md)** | 简体中文（当前）

---

## 这是什么？能给我带来什么好处？

你有没有在国际服里遇到过这种情况：外国玩家在世界频道打了一长串话，你完全看不懂；
或者你想跟旁边的老外交易/组队，却因为语言不通只能互相打问号？

**MCCF 就是解决这个问题的：装上它，服务器里说中文的和说英文的（以及日语、韩语、
德语、法语、西班牙语、俄语等）玩家可以直接用各自的母语聊天，其他人看到的会自动是
翻译好的内容。** 不需要开着翻译软件来回复制粘贴，聊天这件事本身就是"无缝"的。

它还做了几件让这套翻译更"沉浸"、更贴近真实说话的事：

- **翻译只给听得到的人看**：不是全服广播式的翻译，而是按游戏内实际的距离/是否
  被墙挡住来决定谁能"听到"这句话——离得近（看得见说话者）的消息出现在聊天栏，
  离得远看不到人只能听到的就显示在物品栏上方，跟真实说话的感觉一致，不会出现
  "全服都看到你在哪个角落嘀咕"的尴尬。
- **翻译服务你自己选，自己的 Key 自己填**：支持接入 OpenAI、Claude、Gemini、DeepL、
  Kimi、DeepSeek，也支持完全本地免费跑的 Ollama——服主可以在游戏内一个设置界面里
  切换和管理，不需要碰配置文件、不需要重启服务器。
- **不想让服务器统一管翻译？** 也可以只装客户端、自己配置翻译服务，不依赖服主
  是否安装了这个模组（详见下方"纯客户端模式"）。
- **错过的对话可以回看**：字幕会自动消失，但游戏里有一个"聊天历史记录"界面，
  能翻看这局游戏里发生过的所有对话，还会自动按"这是一段对话"分组显示，方便回顾。

## 怎么使用？

1. **下载**：去 [Modrinth](https://modrinth.com/mod/mc-conversation-framework)
   或 [GitHub Releases](../../releases) 下载最新的 `.jar` 文件。（两个渠道
   都提供编译好的 jar，Modrinth 是主渠道；GitHub Releases 还额外提供
   sources jar 供开发者调试用。）
2. **安装**：把下载的 `.jar` 放进 Minecraft 的 `mods` 文件夹。
   - 如果你是**服主**：服务器和你自己的客户端都需要装，才能获得完整的"空间化翻译"
     体验（谁能听到谁听不到、近处走聊天栏 / 远处走物品栏字幕等）。
   - 如果你只是**普通玩家**、连的服务器没装这个模组：你自己单独装客户端也能用——
     会自动切换成"纯客户端模式"，本地把聊天栏文字翻译给你自己看，见下方说明。
3. **同时装好 [Fabric API](https://modrinth.com/mod/fabric-api)**（必需）和
   [Fabric Loader](https://fabricmc.net/use/)。可选装
   [ModMenu](https://modrinth.com/mod/modmenu) 获得图形化设置入口。
4. **进游戏后配置翻译服务**：按 `Esc` 打开暂停菜单，点"聊天历史记录"旁边的设置
   入口（或用 ModMenu 打开"MCCF"的设置），选择一个翻译服务（比如 DeepL 或 OpenAI）
   填入你自己的 API Key 即可开始使用。
5. 不知道该填什么、遇到问题，或者想了解更详细的功能范围/开发细节，
   请继续阅读下面的技术文档，或前往
   [GitHub Issues](../../issues) 反馈。

---

## 技术简介

面向 Minecraft 多人服务器的沉浸式跨语言交流基础设施。核心链路：
文字聊天拦截 → 空间化听觉判定（距离 + 射线遮挡）→ 动态对话上下文（Conversation，
一个对话组从开始到结束作为完整翻译上下文）→ 可插拔翻译 Provider（OpenAI/Claude/
Gemini/DeepL/Kimi/DeepSeek/Ollama）→ 空间化分发（近处走聊天栏 / 远处走物品栏上方
字幕）→ 游戏内配置界面（ModMenu 集成 + 按键呼出）。

目标版本：**Minecraft 1.21.1 · Yarn Mappings 1.21.1+build.3 · Fabric Loader 0.15.11 ·
Fabric API 0.116.15+1.21.1 · Fabric Loom 1.7.3 · Gradle 8.8 · Java 21**

> **关于 mappings 的说明**：本项目使用 **Yarn**（不是 Mojang 官方映射）。
> 如果你看到网上教程用 `net.minecraft.world.entity.Entity` 这类命名，
> 那是 NeoForge / Mojang mapping 风格，和本项目用的 Yarn 命名
> （`net.minecraft.entity.Entity`）是两套完全不同的体系，不要混用。

---

## 一、在你自己电脑上编译

### 1. 前置要求
- **JDK 21**（推荐 Temurin/Adoptium 或 Microsoft Build of OpenJDK）
- **Gradle 8.8**（本项目用的 Fabric Loom 1.7.3 实测要求 Gradle ≥ 8.6，
  用 8.8 是经过验证的稳定版本；wrapper 已锁定 8.8，正常情况下不需要
  你本机单独装 Gradle，见下）
- 稳定的网络连接（首次构建需要从 Fabric/Maven/Mojang 仓库下载依赖，几百 MB）

### 2. 关于 Gradle Wrapper
本项目**没有附带** `gradlew` / `gradlew.bat` / `gradle-wrapper.jar` ——
这几个文件里的 `gradle-wrapper.jar` 是二进制文件，我在无网络的沙盒环境里
无法可靠生成一份真实可用的（伪造内容可能导致你本地校验失败或下载损坏的
Gradle）。`gradle/wrapper/gradle-wrapper.properties` 已经配置好指向
Gradle 8.8，你只需要在本地生成对应的 wrapper 脚本：

```bash
# 前提：本机已安装任意版本的 Gradle（哪怕版本不同也没关系，wrapper 任务
# 只是根据已有的 gradle-wrapper.properties 生成 gradlew/gradlew.bat）
gradle wrapper

# 之后就可以用 ./gradlew 了，它会自动下载并使用 8.8
./gradlew build
```

如果你本机完全没装过 Gradle，也可以直接下载 Gradle 8.8 一次性用：
https://gradle.org/releases/ → 找 8.8 → 解压后用它的 `bin/gradle` 执行
上面的 `gradle wrapper` 命令。

生成 wrapper 后，以后就可以用 `./gradlew build`（Windows 用 `gradlew.bat build`）了。

### 3. 构建
```bash
cd MC-Conversation-Framework-1.21.1
gradle build          # 或 ./gradlew build（如果已生成 wrapper）
```
构建产物在 `build/libs/MCConversationFramework-<版本号>.jar`（版本号见
`gradle.properties` 里的 `mod_version`），把它丢进服务器和客户端的 `mods/` 文件夹即可
（服务器和客户端都要装，因为这是一个同时含服务端逻辑和客户端渲染的模组）。

同时确保服务器和客户端都装了对应版本的 **Fabric API**（`0.116.15+1.21.1` 或更高的
1.21.1 兼容版本），可以在 Modrinth 上下载。装了
[ModMenu](https://modrinth.com/mod/modmenu) 11.0.4（1.21.1 对应版本）的话还能获得
游戏内配置界面的图标入口，非必需。

### 4. IDE 推荐
用 IntelliJ IDEA 打开项目根目录，Gradle 插件会自动识别。首次导入较慢（下载
Minecraft 反混淆源码 + 依赖），耐心等待即可。跑 `genSources` 后能看到带注释的
Minecraft 源码，便于后续开发调试。

---

## 二、当前功能范围

- ✅ 拦截原版聊天，替换为空间化点对点分发
- ✅ 距离 + 射线遮挡判定"谁能听到/看到"（`HearingResolver`）
- ✅ 动态 Conversation 合并/拆分/超时释放（`ConversationManager`）
- ✅ 可插拔翻译 Provider 架构，内置 **7 个真实 Provider** + Mock：
  OpenAI、Claude (Anthropic)、Gemini、DeepL、Kimi (Moonshot AI)、
  DeepSeek、Ollama（本地）
- ✅ **游戏内配置界面**：ModMenu 集成 + 独立按键呼出，可视化切换 Provider、
  填写 API Key / 模型名 / API Endpoint，仅 op 可编辑，普通玩家只读查看
- ✅ 世界词典（专有名词占位替换，保证跨 Provider 翻译一致性）
- ✅ 客户端自动上报 Minecraft 语言设置作为目标语言
- ✅ 双模式空间化分发：VISIBLE（看得见说话者，消息走原版聊天栏）/ AUDIBLE（看不到说话者，消息走物品栏上方 HUD 字幕，多人堆叠布局）
- ✅ `/mccf` 管理命令（status / provider / dictionary / reload）
- ✅ API Endpoint 可自定义（接自建反代 / 兼容网关）+ 一键"恢复默认"
- ✅ 配置界面一键拉取 Provider 可用模型列表
- ✅ 配置界面一键导出日志（`logs/latest.log` 中 MCCF 相关行 + 完整日志）
- ✅ 配置界面多语言（`Text.translatable()`，跟随客户端语言，已提供简体中文翻译）
- ✅ **纯客户端模式**：服务器没装 MCCF 时自动降级为"仅本地翻译聊天栏文字"
  （无空间判定、无字幕），也可手动强制切换，详见"三之二、纯客户端模式"
- ⏳ 语音识别（STT）—— 本版本以文字聊天为输入，语音识别是下一步扩展方向
  （建议对接 Simple Voice Chat 的音频 API 作为输入源，替换 SpatialChatHandler
  的触发源即可，其余流程不需要改动）

---

## 三、配置界面怎么用

**两个入口**：
1. 装了 [ModMenu](https://modrinth.com/mod/modmenu) 的话，Mods 列表里
   MCCF 旁边会出现齿轮图标，点击打开。
2. 不装 ModMenu 也能用——游戏内默认绑定了一个按键（默认未指定具体键位，
   在 `设置 → 控制 → 按键绑定 → MC Conversation Framework` 分类下可以
   自己绑定一个键，绑定后按键直接呼出配置界面）。

**权限规则**：只有服务器 op 能修改 Provider / API Key / 模型 / Endpoint，
提交后由服务端二次校验权限（不是仅凭客户端界面判断），非 op 玩家打开
界面能看到当前生效的是哪个 Provider，但所有输入框禁用、看不到真实
API Key。

**每个 Provider 需要填什么**：
- OpenAI / Claude / Gemini / Kimi / DeepSeek：填 API Key + 模型名（有默认值，
  通常不用改）+ 可选的自定义 API Endpoint（默认走各家官方地址）
- DeepL：只需要 API Key（免费版 Key 以 `:fx` 结尾，界面会自动识别走免费端点）
- Ollama：不需要 API Key（本地部署默认无鉴权），需要填 Endpoint（默认
  `http://localhost:11434`）和模型名（默认 `llama3.2`，换成你本地已拉取的模型名）
- Mock：调试用，无需任何配置

**一键获取模型**：点"获取模型"按钮会调用对应 Provider 的模型列表接口
（OpenAI/Kimi/DeepSeek/Claude 用 `GET /v1/models`，Gemini 用
`GET /v1beta/models`，Ollama 用 `GET /api/tags`），结果打印到聊天栏方便复制。
DeepL（固定引擎无模型概念）和 Mock 不支持，按钮自动置灰。可以用输入框里
**尚未保存**的 Key 直接测试，不需要先保存一次。

**导出日志**：点"导出日志"按钮，从 `logs/latest.log` 提取 MCCF 相关行 /
复制完整日志，两者都输出到 `<游戏目录>/mccf-exports/`，带时间戳文件名，纯本地操作。

保存后服务端会立即重建对应的 Provider 实例并生效，无需重启服务器。

---

## 三之二、纯客户端模式（服务器没装 MCCF 时）

如果你连的服务器没有安装 MCCF（比如公共服/别人的服），模组没法拦截聊天做
空间判定、也没法做距离/遮挡这类需要服务端参与的判定——所有人还是会照常
收到原版广播的聊天。这种情况下 MCCF 会自动降级为**纯客户端模式**：不做
空间判定、不渲染物品栏字幕，只是把收到的每一条聊天消息在本地翻译成
你自己客户端设置的语言，追加显示在聊天栏里（原文照常显示，译文是追加的
一行，前面带 `⇄` 标记），比如：
```
<Steve> こんにちは
⇄ Hello
```

**怎么进入这个模式**：
- **自动检测**（默认）：进入服务器时会检测对方是否声明了 MCCF 的网络通道，
  没检测到就自动切换，不需要你做任何操作。
- **手动强制**：主配置界面（按键呼出或 ModMenu）里点"纯客户端本地翻译设置"，
  进"运行模式"选择"强制纯客户端模式"——即使服务器装了 MCCF，也只用本地
  翻译；反过来选"强制服务器模式"可以在自动检测误判时手动纠正。这个选择
  保存在本地（`config/mccf/client-mode.json`），跟随你的客户端，不是针对
  某个服务器的设置。

**配置管理**：纯客户端模式用的翻译 Provider 配置是一份完全独立于服务端的
本地文件（`config/mccf/client-only-config.json`），任何人都能自由编辑，
不需要 op 权限——毕竟这份配置只影响你自己本地看到的翻译结果，不会分发给
任何其他玩家。填法和主配置界面一样（Provider + API Key + 模型名 +
Endpoint）。如果当前连的服务器恰好装了 MCCF，还可以点"从服务器同步"一键
把服务器正在用的 Provider/模型名/Endpoint 拷贝过来，省得重新选一遍——但
**不会**拷贝 API Key（即使你是 op、快照里带真实 Key 也不拷贝），密钥必须
你自己填。

**已知限制**：
- 没有对"是否已经是目标语言"做检测，每条消息都会真的发一次翻译请求
  （Mock Provider 除外，它本身会在同语言时直接透传，不消耗额度）。
- 不会给自己发的消息做二次翻译（服务器回显自己说的话时会跳过），但其他
  语言相同的消息目前没有去重/节流，人多的服务器可能会比较费 API 额度。
- 由于没有服务端参与，这只是"翻译 + 展示美化"，**不是**真正的信息隔离——
  所有人依然会收到原版广播的完整聊天，纯客户端模式改变的只是你自己客户端
  怎么显示它。

---

## 四、如何接入其他翻译 Provider（内置 7 个之外的）

打开 `net.mccf.mod.translation.provider.TranslationProvider` 接口，参照
`MockTranslationProvider` 写一个新的实现类，例如：

```java
public class MyTranslationProvider implements TranslationProvider {
    @Override public String getId() { return "myprovider"; }
    @Override public String getDisplayName() { return "My Provider"; }

    @Override
    public CompletableFuture<TranslationResult> translate(TranslationRequest request) {
        // 1. 用 java.net.http.HttpClient 异步调用目标 API
        //    （可参照 HttpProviderSupport 里的通用请求/超时/错误处理逻辑）
        // 2. 把 request.contextMessages() 作为对话历史一起传给模型，
        //    帮助它理解代词指代、术语一致性等
        // 3. 返回 CompletableFuture<TranslationResult>
    }
}
```

然后在 `ProviderFactory` 里注册对应的创建逻辑，并在 `ProviderDefaults` 里
补上默认的 endpoint / model 值（供"恢复默认"按钮使用）。

API Key 等敏感信息建议放在 `config/mccf/config.json` 之外单独的、不提交到版本库
的文件里（比如环境变量或 `secrets.json` 并加入 `.gitignore`）。

---

## 五、目录结构

```
src/main/java/net/mccf/mod/
├── MCCF.java                        模组入口（服务端）：注册网络包、事件监听、命令
├── config/
│   ├── MCCFConfig.java              服务端配置（距离阈值、超时时间、当前 Provider 等）
│   ├── ProviderConfig.java          单个 Provider 的配置项（apiKey / model / endpoint）
│   └── ProviderDefaults.java        每个 Provider 的默认 endpoint / model，"恢复默认"的数据源
├── context/
│   ├── Conversation.java            单个对话组：参与者 + 近期消息
│   └── ConversationManager.java     合并/拆分/超时释放
├── dictionary/WorldDictionary.java  世界词典 + 占位符替换逻辑
├── translation/
│   ├── TranslationService.java      词典 -> Provider -> 结果还原 的调度层
│   └── provider/
│       ├── TranslationProvider.java          可插拔接口
│       ├── ProviderFactory.java              根据配置创建对应 Provider 实例
│       ├── HttpProviderSupport.java          各 Provider 共用的 HTTP 请求/超时/错误处理
│       ├── MockTranslationProvider.java      调试用示例实现
│       ├── OpenAiTranslationProvider.java
│       ├── ClaudeTranslationProvider.java
│       ├── GeminiTranslationProvider.java
│       ├── DeepLTranslationProvider.java
│       ├── KimiTranslationProvider.java
│       ├── DeepSeekTranslationProvider.java
│       └── OllamaTranslationProvider.java
├── spatial/
│   ├── HearingResolver.java          距离 + 射线遮挡判定
│   ├── SpatialChatHandler.java       核心调度：拦截聊天 -> 判定 -> 翻译 -> 分发
│   └── PlayerLanguageRegistry.java   玩家语言运行时注册表
├── network/                          C2S/S2C 网络包定义（见下方说明）
└── command/
    ├── MCCFCommand.java              /mccf 管理命令（status / provider / dictionary / reload）
    └── ConfigSyncHandler.java        配置界面的服务端数据同步：MCCFConfig <-> JSON 快照，op 权限校验

src/client/java/net/mccf/mod/client/
├── MCCFClient.java                   客户端入口：注册渲染器、按键绑定、网络接收器
├── config/
│   ├── MCCFConfigScreen.java         配置界面主 Screen
│   ├── ModelSelectionScreen.java     "获取模型"结果的子列表 Screen
│   ├── MCCFModMenuIntegration.java   ModMenu 集成入口（仅 ModMenu 安装时加载）
│   ├── ClientConfigState.java        客户端本地配置状态单例（含未提交的编辑，来自服务端快照）
│   ├── ClientProviderConfig.java     客户端内存中单个 Provider 配置副本（服务端/本地两处复用）
│   ├── ClientOnlyTranslationConfig.java  纯客户端模式：玩家自己的本地翻译配置，持久化到
│   │                                      config/mccf/client-only-config.json，任何人可编辑
│   └── ClientOnlyConfigScreen.java   纯客户端模式的"本地翻译设置"子界面（从主界面按钮打开）
├── mode/ClientOnlyModeManager.java   判断当前是否该走纯客户端模式：自动检测 + 手动强制覆盖，
│                                      持久化到 config/mccf/client-mode.json
├── chat/ClientOnlyChatTranslator.java  纯客户端模式下的本地聊天翻译：监听收到的聊天消息，
│                                        异步翻译后追加显示，不做空间判定
├── subtitle/
│   ├── ActiveSubtitle.java           客户端内存态字幕数据（仅 AUDIBLE 模式）
│   ├── SubtitleManager.java          接收、超时管理、多人去重排序（仅 AUDIBLE）
│   └── HotbarSubtitleRenderer.java   AUDIBLE 模式：物品栏上方堆叠显示
└── util/LogExporter.java             日志导出逻辑（提取 MCCF 相关行 + 完整日志复制）
```

**关于 `network/` 包**：目前实际被 `MCCF.java` / `MCCFClient.java` 注册使用的网络包是
`SubtitlePayload`、`ConversationRosterPayload`、`LanguageReportPayload`、
`RequestConfigPayload`、`ConfigSnapshotPayload`、`UpdateConfigPayload`、
`RequestModelsPayload`、`ModelsResultPayload`、`ModePreferencePayload` 共 9 个。

（早期版本里还有 `RequestModelListPayload` / `ModelListResponsePayload` 两个类，
是"获取模型"功能早期迭代时留下的死代码，1.0.0 已删除清理。）

---

## 六、配置文件（首次运行后自动生成）

`config/mccf/config.json`：
```json
{
  "subtitleVisibleRange": 32.0,
  "hearingRange": 48.0,
  "conversationRange": 48.0,
  "conversationIdleTimeoutSeconds": 120,
  "enableOcclusionCheck": true,
  "activeProvider": "mock",
  "showOriginalText": true,
  "showOriginalTextInChat": false
}
```

`config/mccf/dictionary.json`：世界词典，格式为
`{ "词条": { "语言代码": "该语言下的译文", ... }, ... }`，也可以用
`/mccf dictionary add <term> <lang> <translation>` 命令在游戏内添加。

---

## 七、已知限制 / 后续可扩展方向

- 语音识别未实现（见"二、当前功能范围"）。
- `HearingResolver` 目前只做"说话者→听众"单向射线检测一次，性能上对大量玩家
  同时说话的场景（比如几十人的活动服）没有做批量优化，如果后续遇到性能问题，
  可以考虑给听众按区块分桶、或限制每 tick 处理的聊天消息数。
- 字幕的显示时长按文本长度粗略估算，可以后续做成可配置项。
- Conversation 的合并策略目前是"简单并集"，没有处理"两个大对话组因为一个
  中间人同时能听到而被强行合并"这种边界情况的精细化处理（比如设置合并的
  最小重叠人数阈值），如果实测中出现不合理的合并，可以在
  `ConversationManager.recordUtterance` 里加更细的判定逻辑。
- 世界广播、NPC 对话、剧情事件等在设计文档中提到的"未来扩展"尚未实现，
  但整体架构（Provider 可插拔 + Conversation 上下文隔离）是为它们预留的。
- ~~`network/` 包里有两个未注册使用的死代码类~~（1.0.0 已删除清理，见"五、目录结构"末尾说明）。
- **纯客户端模式（`ClientOnlyChatTranslator`）用到的 `ClientReceiveMessageEvents.CHAT`
  事件签名**：此前 README 里标注为"凭印象写、未本地编译验证"的风险点，在
  1.21.1 上经本地编译验证**签名正确**（5 参数：`message, signedMessage, sender, params, receptionTimestamp`），
  该风险点已消除。`CyclingButtonWidget.setValue(...)`（在"从服务器同步"按钮里
  用来刷新下拉框显示）此前同样标注为"凭印象写"，也已在 1.21.1 上编译通过，
  运行时行为正常。

---

## 八、更新日志

> 决策分析（根因、方案论证、取舍）已移至 [docs/design-notes.md](docs/design-notes.md)（[英文版](docs/design-notes_en.md)），本章节只保留纯版本更新。

### 2026-08-08　1.1.8 修复：服务端装 MCCF 但客户端没装时消息丢失 + 强制客户端模式下收不到消息

- **修复服务端装 MCCF、客户端没装时玩家完全收不到消息**：服务端拦截原版广播后用 SubtitlePayload 分发，但没装 MCCF 的客户端不认这个通道，导致说话者自己看不到自己发的消息、听众也收不到消息。修复：服务端用 `ServerPlayNetworking.canSend` 检测玩家是否支持 SubtitlePayload 通道，不支持的玩家改用原版 `sendMessage` 发 `<名字> 原文` 格式文本（无翻译，但至少能看到消息内容）。
- **修复强制客户端模式下 host 看不到非 client-only 玩家的消息**：host 选强制客户端模式后被标记为 client-only，其他非 client-only 玩家发消息时服务端拦截原版广播，`dispatchTo` 对 client-only 听众 `continue` 跳过，导致 host 既收不到 CHAT 也收不到 SubtitlePayload。修复：说话者非 client-only 时，对 client-only 听众发未翻译的 SubtitlePayload（不调翻译服务，避免浪费），客户端走 SubtitlePayload 接收器的 client-only 分支手动显示原文 + 本地翻译。
- **修复 client-only 听众收到 SubtitlePayload 时只显示译文不显示原文**：原版广播被服务端拦截后 client-only 听众收不到 CHAT 事件，SubtitlePayload 接收器的 client-only 分支只调 `translateAndAppend` 追加翻译，原文缺失。修复：在该分支加 `addVisibleToChatHud` 手动显示原文。
- **ConversationRosterPayload 发送加 canSend 检查**：没装 MCCF 的参与者不支持该通道，跳过避免发包失败。
- 涉及文件：`SpatialChatHandler.java`、`MCCFClient.java`。

版本号 `1.1.7` → `1.1.8`。决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-08-08　1.1.7 优化：配置界面标签页改版 + Mock 保存确认修复 + Provider 列表选中框跟随

- **配置界面标签页改版**：标签页从带边框的 ButtonWidget + » « 前缀改为自绘的扁平标签（还原创建新世界界面的"游戏/世界/更多"风格）——纯文字标签，选中态底部 2px 白色高亮线标识，非选中灰色、hover 浅灰，底部暗色分隔线隔开标签栏和内容区。
- **修复 Mock 保存确认弹窗需点两次才生效**：选 Mock → 保存 → 确认弹窗 → 点"是"后第一次不应用，需要再次保存并确认才生效。根因是 ConfirmScreen 回调里 performSave 在 setScreen 之前执行，setScreen 触发 init 重建发 requestSnapshot，回包的 applySnapshot 重置了 pendingActiveProvider。改为先 setScreen 后 performSave（和 LocalConfigPanel 一致），通过 triggerServerPerformSave 在新 panel 上执行保存。
- **修复服务端切换 Provider 后客户端选中框不跟随**：服务端把 DeepSeek 改成 Kimi 后，客户端收到广播快照，但选中框还停在 DeepSeek，只有 Kimi 有 ✓ 对勾；跨页时也不自动滚动到新 Provider。根因是 1.1.4 删除了 `selectedProvider = state.activeProvider`（防止 init 重建后 requestSnapshot 回包重置玩家查看的 Provider），但一刀切导致别人改配置后选中框也不跟随。用 previousActiveProvider 字段区分：玩家没在看别的 Provider（selectedProvider == 旧 activeProvider）时跟随到新 activeProvider 并滚动；玩家在看别的 Provider 时保留不打断。
- **跳转最新按钮箭头改向**：聊天历史界面右下角"最新"按钮的箭头从 ↓ 改为 ↑，和默认排序（TIME_DESC 最新在最上面，点按钮滚到顶部）的方向一致。
- 涉及文件：`MCCFConfigScreen.java`、`ServerConfigPanel.java`、`ClientConfigState.java`、9 个语言文件。

版本号 `1.1.6` → `1.1.7`。决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-08-05　1.1.6 新增：双击 jar 弹窗 + 聊天历史记录大优化

- **双击 jar 弹窗**：玩家下载 jar 后双击不再看到 JVM 报错，而是弹一个友好的安装提示窗口，告诉玩家这是 Minecraft 模组、需要放到 mods 文件夹、需要先装 Fabric Loader 和 Fabric API。支持 9 种语言（跟随系统语言），含"打开教程"按钮一键打开 GitHub README。
- **历史记录持久化**：聊天历史记录现在存到 `config/mccf/chat-history.json`，重进游戏/重连服务器后保留。之前断开连接就清空，现在只有玩家手动点"清空"才会清除。
- **分组标题加时间范围**：每个对话分组标题后面追加时间范围（如"XX 的对话 14:23-14:31"），一眼知道这段对话发生在什么时间段。
- **分组标题样式优化**：去掉绿色背景条，改为左侧白色竖线 + 白色文字，更干净不突兀。
- **消息双行显示**：每条消息改为类似聊天栏的双行呈现——第一行 `<说话者> 原文`（说话者名字按来源着色，右侧时间戳），第二行 `⇄ 译文 [源语言→目标语言]`（浅灰色缩进）。导出格式同步改为双行。
- **右键复制消息**：在聊天历史界面右键点击任意消息行，复制译文（或原文，如果无译文）到剪贴板，方便粘贴到聊天栏使用。
- **导出历史到文件**：点"导出"按钮把当前筛选后的历史记录导出为 txt 文件到 `mccf-exports/`，导出后自动打开文件夹。
- **跳转最新按钮**：底部"↓ 最新"按钮一键滚动到最新消息（默认排序下滚到顶部，其他排序滚到底部）。
- **清空历史按钮**：底部"清空"按钮（带确认弹窗）清除所有历史记录和持久化文件。
- **重置筛选按钮**：筛选面板内"重置"按钮一键清空所有筛选条件（来源/参与者/关键词/排序/显示译文），恢复到默认状态。
- **筛选面板空白优化**：收紧面板高度从 104px 到 60px，消除展开时下半部分的大片空白。
- **筛选立即生效**：所有筛选条件（来源切换、参与者选择、关键词输入、排序、显示译文）现在都是即时生效，不需要按回车或收起面板。
- **排序选项改名**：TIME_DESC 排序选项从"最新在前"改为"默认"，语义更清晰。
- **消息行视觉优化**：无译文的消息行（自己说话或同语言）文字垂直居中显示，避免 20px 行高下单行文字顶在上面留大片空白；有译文的行保持双行布局。
- **系统提示缩小**："开始了一段新对话"等系统提示行缩小到 0.85 倍字号，作为分隔性辅助信息不再和消息正文一样大喧宾夺主。
- **对话大标题缩小**：分组大标题字号从 1.3 倍降到 1.1 倍，在 20px 行高里不再头重脚轻。
- **长消息 tooltip**：被截断的长消息行 hover 时显示完整内容 tooltip（双行），自动按屏幕宽度换行，不再超出屏幕边界。
- **清理幽灵翻译键**：删除了引用不存在的"主配置界面按钮"入口的 `mccf.config.chat_history` 翻译键（9 种语言）。
- 涉及文件：`JarDoubleClick.java`、`build.gradle`、`ChatHistoryManager.java`、`ChatHistoryScreen.java`、`MCCFClient.java`、9 个语言文件。

版本号 `1.1.5` → `1.1.6`。决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-08-05　1.1.5 新增：权限状态机 + Mock 警告 + 导出日志打开文件夹

- **权限状态机**：服务端配置标签页根据连接状态动态判断可见性：
  - **隐藏**（不显示标签）：未进入世界（带 500ms 防抖）/ 单人世界无人通过 LAN 加入 / 服务端未装 MCCF
  - **只读**（灰色 + 黄色横幅"你不是此服务器的管理员"）：服务器非 op
  - **可编辑**：op + 服务端装了 MCCF / 单人 LAN 有人加入
  - "字幕/聊天栏显示原文"两个开关不受权限限制（客户端个人偏好）
  - 实时刷新：状态变化时自动重建界面（如玩家被 op / 服务端装了 MCCF / 单人世界有人通过 LAN 加入）
- **Mock 警告**：
  - 选中 Mock 查看（从非 Mock 切换到 Mock）时弹 ConfirmScreen 提醒"Mock 不会真正翻译"
  - 保存 Mock 时弹 ConfirmScreen 确认（已保存 Mock 不再弹）
  - 右侧配置区顶部横幅：选中查看 Mock 时黄色警告条，保存后红色常驻条"Mock 仅用于调试..."
- **导出日志打开文件夹**：点击"导出日志"后自动用系统文件管理器打开 `mccf-exports/` 文件夹，玩家不用手动找文件
- **Mock 配置界面精简**：Mock 选中时隐藏 API Key/endpoint/model/disableThinking/恢复默认/获取模型/清除 Key，只保留"显示原文"两个开关 + 保存按钮。Mock 无需这些配置项，显示出来反而误导。ServerConfigPanel 和 LocalConfigPanel 两个面板都应用。
- **非 op 配置界面精简**：非 op 玩家打开服务端配置时，右侧隐藏 API Key/endpoint/model/disableThinking/恢复默认/获取模型/清除 Key/保存按钮，保留"显示原文"两个开关。Provider 列表禁止手动滚动 + 禁止点击切换（但 init 时自动定位到 activeProvider 让玩家看到当前生效的 Provider）。右侧控件区显示提示文字"当前生效的提供商：X（只读查看）"，位置在横幅下方避免重叠。
- **Provider 列表自动滚动到选中项**：打开界面时自动滚动到 selectedProvider（通常等于 activeProvider），不再每次打开都要手动滚动找当前生效的 Provider。
- 涉及文件：`MCCFConfigScreen.java`（权限状态机 + 防抖 + 实时刷新）、`ServerConfigPanel.java`（权限横幅 + Mock 警告 + applyEditability 改造 + Mock/非 op 控件隐藏 + 非 op 提示文字 + 列表锁定）、`LocalConfigPanel.java`（Mock 控件隐藏）、`ProviderConfigPanel.java`（PanelVisibility 枚举 + isMockSelected helper + init 自动滚动）、`ProviderListWidget.java`（scrollToProvider + setScrollLocked + setSelectionLocked）、`LogExporter.java`（打开文件夹）、9 种语言文件（4 个新翻译键 × 9）

版本号 `1.1.4` → `1.1.5`。决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-08-05　1.1.4 修复：onSnapshotUpdated 重置 selectedProvider + 自己说话字幕不显译文 + 历史记录显示译文开关 + 大标题样式

- **核心 bug 修复 - 切换 Provider 后获取模型列表点取消仍切回 activeProvider**：1.1.3 的 init() 重建保留 selectedProvider 修复只解决了一半——新 panel 构造函数调用 `requestSnapshot()` 发配置请求，服务端回包触发 `onSnapshotUpdated()`，里面 `selectedProvider = state.activeProvider` 把保留的选中状态又重置了。修复：删除 `onSnapshotUpdated` 里的 selectedProvider 重置行。首次打开通过 `initialSelectedProvider()` 初始化，init 重建通过 `preservedSelectedProvider` 保留，保存后 selectedProvider 已等于 activeProvider——三种场景都不需要 onSnapshotUpdated 重置。
- **UX 修复 - 自己说话字幕不显示译文行**：物品栏字幕（AUDIBLE 模式）下，自己说话时 `originalText == translatedText`（服务端不翻译自己说的话），显示 "⇄ 译文" 会暗示"自己说的话需要翻译"——用户反馈这很奇怪。修复：`HotbarSubtitleRenderer` 渲染时判断 `speakerId == client.player.uuid`，isSelf 时只显示 "名字: 原文" 一行，不显示 ⇄ 译文行。
- **功能新增 - 聊天历史"显示译文"开关**：筛选面板新增"显示译文"toggle（默认开启），关闭后历史记录只显示原文不显示译文，方便专注阅读原文不被译文干扰。9 种语言新增 `mccf.history.filter.show_translated` 翻译键。FILTER_PANEL_HEIGHT 从 84 改到 104 容纳新控件。
- **样式改进 - 大标题放大 + 不可选中**：`GroupTitleWidget`（"xxx 的对话"）用 `context.getMatrices().scale(1.3f)` 放大 1.3 倍渲染 + 加深背景色（0x3355AA55 → 0x5555AA55），与普通消息行视觉分开。`GroupTitleWidget` 和 `SystemEventWidget`（"开启了一段新对话"）的 `mouseClicked` 返回 false，不可被点击选中——它们是纯展示元素，选中没有意义。

版本号 `1.1.3` → `1.1.4`。决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-08-05　1.1.3 修复：切换 Provider 丢失更改 + 强制关闭思考警告屏幕点"是"不生效 + 命令本土化

- **核心 bug 修复 - 切换 Provider 后操作丢失**：玩家从 DeepSeek 切换到 OpenAI 查看 → 点"获取模型列表"进入 ModelSelectionScreen → 关闭后回到主界面，Provider 列表选中的切回 DeepSeek，丢失了切换到 OpenAI 的所有更改。根因：`MCCFConfigScreen.init()` 在 `ModelSelectionScreen`/`ConfirmScreen` 关闭后被触发重建，`new ServerConfigPanel(...)` 创建全新实例，新实例的 `selectedProvider` 通过 `initialSelectedProvider()` 读 `state.activeProvider`，丢失了玩家在旧 panel 里临时切换查看的 `selectedProvider`。
- **同根因 bug 修复 - 强制关闭思考点"是"不生效**：玩家选某 Provider → 点开 disableThinking 开关 → 警告屏幕点"是" → `state.getOrCreate(selectedProvider).disableThinking = true` 正确写入 state → 但 `setScreen` 触发 init 重建 → 新 panel 的 `selectedProvider = state.activeProvider`（可能不是玩家刚改的那个）→ 新 panel 显示的是 activeProvider 的 disableThinking（false）→ 玩家看到"没生效"。和上面是同一根因。
- **修复方案**：`MCCFConfigScreen` 加 `lastSelectedServerProvider` / `lastSelectedLocalProvider` 字段，`init()` 重建前从旧 panel 读出 `selectedProvider` 保留，新 panel 构造后通过 `setPreservedSelectedProvider()` 传回去。`ProviderConfigPanel.init()` 优先使用 preserved 值，没有才 fallback 到 `initialSelectedProvider()`。同时 `setModelFromSelection` 改用方法参数 providerId 写入 state，不依赖 panel 实例的 selectedProvider 字段。
- **命令本土化**：`/mccf status` 和 `/mccf stats reset` 的输出从英文硬编码改为 `Text.translatable()`，9 种语言文件新增 `command.mccf.status` 和 `command.mccf.stats.reset` 翻译键。
- **仓库整理**：`.gitignore` 添加 `.codebuddy/memory/` 排除规则；`git rm --cached` 移除已误提交的 `.codebuddy/memory/{2026-08-05.md,MEMORY.md}`（AI 工作记忆，不属于项目源码）。软删除完成，硬删除（filter-repo 重写历史彻底清理）待后续统一执行。

版本号 `1.1.2` → `1.1.3`。决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-08-05　1.1.2 修复：Zhipu 未注册 + 网络包长度校验 + 翻译失败感知 + 多项 QA 问题修复

- **致命 bug 修复**：`MCCF.registerAllProviders()` 漏注册 `"zhipu"`，导致选了智谱 AI 后服务端找不到该 Provider 静默 fallback 到 Mock——配置界面看起来配好了但实际完全没翻译。该 bug 从 0.15.0 加入智谱起跨越多个版本无人发现，根因是 ProviderDefaults/ProviderFactory/ClientConfigState 都包含了 zhipu（玩家界面能正常看到/选择/填 Key），唯独运行时注册表漏了。
- **安全加固**：`RequestModelsPayload` 加 `MAX_JSON_LENGTH = 65536` 校验；`LanguageReportPayload` 加 `MAX_LANGUAGE_LENGTH = 64` 校验；`ConversationRosterPayload` 解码端 `readVarInt` 后增加 `[0, MAX_PARTICIPANTS]` 范围检查。旧代码无长度校验，恶意客户端可发送超大 JSON / 超大 idCount 触发服务端 OOM——`new ArrayList<>(Integer.MAX_VALUE)` 预分配约 16GB 内存直接打爆服务端。
- **UX 修复 - 翻译失败玩家感知**：`TranslationService` 新增 `failureCount` / `successCount` 原子计数器；`/mccf status` 命令展示翻译统计（`translations: N ok / M failed (X.X% success)`）；新增 `/mccf stats reset` 子命令重置统计；`ClientOnlyChatTranslator` 翻译失败时给玩家聊天栏发一次去重的灰色提示（同一 reason 60 秒内只提示一次，避免刷屏）。
- **UX 修复 - Provider 缓存即时刷新**：`ClientOnlyChatTranslator` 的 Provider 缓存永不失效导致玩家改 API Key 后翻译仍用旧 Key。新增 `invalidateProviderCache()` 方法，由 `ClientOnlyTranslationConfig.save()` 在文件成功写入后回调触发，保证"保存即刷新"。
- **UX 修复 - 历史记录说话者名**：纯客户端模式下历史记录 speakerName 永远显示 "?"。`ClientOnlyChatTranslator` 新增 `parseSpeakerName()` 从聊天文本 `<玩家名>` 前缀解析；新增 `translateAndAppend(text, uuid, speakerNameHint)` 重载，让 SubtitlePayload 退回路径能传入 payload 自带的权威 speakerName。
- **健壮性修复 - UUID 解析**：`ClientOnlyChatTranslator` 的 `UUID.fromString(senderUuid)` 在非标准 UUID 格式时抛 IllegalArgumentException 被异步调度器吞掉。新增 `parseSenderUuid()` 加 try-catch 退化成零 UUID，保证翻译流程继续。
- **健壮性修复 - LogExporter 日志爆炸**：磁盘满时每行写入失败都记一次 error 日志，百万行日志会产生百万条错误。新增 `AtomicBoolean writeFailed` 标志，首次写入失败后立即停止后续写入，避免日志风暴；结果消息标注"PARTIAL, write aborted"。
- **限流升级**：`RateLimiter` 从固定窗口升级为滑动窗口（ArrayDeque 时间戳队列）。旧版固定窗口在边界附近可能放过接近 2 倍上限的请求（5 条/秒限制下突刺可达 10 条/秒），可能触发 OpenAI / DeepL 等付费 API 限流导致 Key 被临时封禁。滑动窗口精确限制"任意连续 1 秒内不超过 5 条"，无突刺风险。`RateLimiterTest` 同步更新并新增"滑动窗口边界无 2 倍突刺"测试用例。
- **UX 优化 - 字幕溢出提示**：`HotbarSubtitleRenderer` 字幕上限 5 条超出时旧版静默丢弃，玩家无感知。新版在字幕堆叠底部追加一行灰色文字 `… 还有 N 条字幕未显示`，玩家可感知有内容被跳过。
- **UX 优化 - 首次提示持久化 + ModMenu 入口**：`MCCFClient.tipped` 从内存 static boolean 改为持久化到 `client-mode.json` 的 `tippedFirstJoin` 字段，老玩家重启客户端不再被同一条提示刷屏（升级到 1.1.2 后会再提示一次让用户感知到改进，之后不再重复）。9 种语言的首次提示文案同时加入 ModMenu 入口说明，玩家不需绑定按键也能找到设置入口。

版本号 `1.1.1` → `1.1.2`。决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-08-04　1.1.1 修复：强制关闭思考开关点"是"不生效 + 显示原文改为客户端个人偏好

- 修复"强制关闭思考"开关点确认弹窗"是"后仍显示"关"的 bug（`ServerConfigPanel`/`LocalConfigPanel` 的 `onDisableThinkingToggled` 回调顺序错误）。
- "字幕显示原文"和"聊天栏显示原文"从服务端 op 配置改为客户端个人偏好，每个玩家独立决定，不受服务器/op 限制。
- 修复 AUDIBLE 字幕实际不显示原文的 bug（`HotbarSubtitleRenderer` 只渲染译文，未读 `originalText`）。
- 两个开关保留在"服务端配置"标签页原位置，改为可编辑（不再灰色不可选），值存入 `client-only-config.json`。
- `MCCFConfig`/`ConfigSyncHandler`/`ClientConfigState` 移除这两个字段，服务端 `SpatialChatHandler` 始终在 payload 中携带原文。

版本号 `1.1.0` → `1.1.1`。决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-08-03　1.1.0 调整：发布工作流改为纯手动触发 + GitHub Release 恢复附带 jar 和 sources jar

- `.github/workflows/release.yml`：改为纯 `workflow_dispatch` 手动触发。手动触发后自动编译 + 测试 + 创建 GitHub Release + 自动打 `v*.*.*` tag + 上传 jar 和 sources jar。
- 删除 `release.py` 脚本。
- GitHub Release 恢复附带 jar 和 sources jar（0.16.4 起曾改为只含源码）。
- 新增 `docs/design-notes.md`（中英文）：从 README 更新日志剥离的决策分析。
- README 下载说明 + 更新日志顺序修正。

版本号 `1.0.0` → `1.1.0`。决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-08-02　1.0.0 首个正式版：修复 reload 遗漏 + 清理死代码 + 版本号转正

- 修复 `MCCF.reload()` 漏掉 `showOriginalTextInChat` 字段。
- 删除 2 个死代码网络包类：`RequestModelListPayload.java`、`ModelListResponsePayload.java`。
- README 配置示例补上 `showOriginalTextInChat` 字段。
- README 已知限制章节同步更新。

版本号 `0.16.4` → `1.0.0`。决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-08-02　0.16.4 调整：GitHub Release 改为源码 Release + 新增一键发版脚本

- `.github/workflows/release.yml`：`build` job 仍跑编译验证但不传 artifact，`release` job 去掉 `download-artifact` 和 `files` 参数，GitHub Release 只含源码。
- 新增 `release.py` 一键发版脚本：从 `gradle.properties` 读取 `mod_version`，自动打 `v*.*.*` tag 并 push 触发 GitHub Actions 创建源码 Release，支持 `--check` dry-run。
- README.md / README_EN.md 下载说明同步更新：主渠道改为 Modrinth，GitHub Release 只提供源码和更新日志。
- README.md "怎么使用"章节"字幕悬浮位置等"过期描述改为"近处走聊天栏 / 远处走物品栏字幕等"。

版本号 `0.16.3` → `0.16.4`。决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-08-02　0.16.3 修复：配置界面输入框 placeholder 文字超出输入框宽度

- 简化全部 9 种语言的 `api_key.placeholder` 和 `endpoint.placeholder`，去掉冗长的"留空则保持当前值不变"解释，只保留核心提示。

版本号 `0.16.2` → `0.16.3`。决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-08-02　0.16.2 修复：配置界面"显示原文"开关布局 + 灰色不可选 + 强制关闭思考按钮重复显示

- `ServerConfigPanel.buildRightPanel`："字幕显示原文"和"聊天栏显示原文"两个开关从并排一行拆成各自一整行，spacing 公式相应调整。
- `ServerConfigPanel.applyEditability`：两个开关的 `active` 无条件置 `false`，仅作只读展示。
- `ServerConfigPanel` 和 `LocalConfigPanel` 的 `refreshFieldsFromState`：`disableThinkingButton.visible = supportsThinking && tabVisible`，修复非活动标签页按钮重复显示。

版本号 `0.16.1` → `0.16.2`。决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-08-02　0.16.1 文档调整：去除"固定在 1.21.1"的版本理由论证

- `README.md`：删除"技术简介"中 `> **为什么固定在 1.21.1？**` 整段引用块。
- `README_EN.md`："This is Minecraft 1.21.1 only."条目附带的论证段落删除。
- `gradle.properties`：Fabric Properties 注释精简为"1.21.1 仅作为当前发布基线，不作为长期锁定"。

版本号 `0.16.0` → `0.16.1`。决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-08-02　0.16.0 移除世界空间字幕（VISIBLE 走聊天栏转正）+ AI 上下文改为完整对话组

- 删除 `WorldSubtitleRenderer.java`，移除 `MCCFClient` 中 `WorldRenderEvents.AFTER_ENTITIES` 的渲染器注册。
- `ActiveSubtitle.Mode` 枚举（VISIBLE/AUDIBLE）和 `mode` 字段移除，`SubtitleManager.onReceive` 不再解析 mode，`HotbarSubtitleRenderer` 不再按 mode 过滤。
- `Conversation.recordMessage`：移除 `MAX_CONTEXT_MESSAGES = 20` 硬截断。
- `ChatCompletionsSupport.buildSystemPrompt`：移除 `context.size() - 5` 的最近 5 条截断。
- 涉及文件：删除 `WorldSubtitleRenderer.java`；修改 `MCCFClient.java`、`ActiveSubtitle.java`、`SubtitleManager.java`、`HotbarSubtitleRenderer.java`、`HearingResolver.java`、`MCCFConfig.java`、`Conversation.java`、`ChatCompletionsSupport.java`、`TranslationProvider.java`。
- README 顶部介绍、技术简介、"当前功能范围"、"目录结构"、"已知限制"、"9.2.5 版本踩坑"同步更新。

版本号 `0.15.0` → `0.16.0`。决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-07-30　0.15.0 新增：五家 Provider 独立"强制关闭思考"开关 + 新增智谱 AI Provider

- `ProviderConfig` / `ClientProviderConfig` 新增 `disableThinking` 字段。
- `ChatCompletionsSupport.buildRequestBody` 新增带 `disableThinking` 参数的重载，DeepSeek/Kimi/智谱复用；Claude、Gemini 各自的 `buildRequestBody` 加对应参数注入。
- `ServerConfigPanel` / `LocalConfigPanel` 新增 `THINKING_CAPABLE_PROVIDERS` 判断，DeepSeek/Kimi/Claude/Gemini/智谱五家显示"强制关闭思考"开关。
- 打开开关时用 `ConfirmScreen` 弹出警告，关闭直接生效。
- `ClientOnlyTranslationConfig#copyPublicFieldsFrom` 新增 `disableThinking` 字段拷贝。
- 新增 `ZhipuTranslationProvider`，默认 endpoint `https://open.bigmodel.cn/api/paas/v4`，默认模型 `glm-5.2`，注册进 `ProviderFactory`、`ProviderDefaults`、`ClientConfigState.PROVIDER_IDS`。
- 新增 5 个翻译键 × 9 种语言。

版本号 `0.14.0` → `0.15.0`。决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-07-30　0.14.0 新增：聊天历史记录支持筛选与排序

- `ChatHistoryManager` 新增 `FilterOptions`（record）：来源、参与者、关键词三个筛选维度。
- 新增 `SortMode`（枚举）：`TIME_DESC`、`TIME_ASC`、`PARTICIPANT_COUNT_DESC`、`MESSAGE_COUNT_DESC`。
- `groupedSnapshot()` 新增带参数重载，原有无参版本保留。
- 新增 `knownSpeakerNames()` 收集说话者显示名供参与者筛选下拉框使用。
- `ChatHistoryScreen` 新增筛选/排序面板：来源开关、参与者下拉、关键词输入框、排序方式下拉。
- 修复 `rebuildList()` 残留失效列表 bug：重建前先 `remove(listWidget)`。
- 新增 9 个翻译键 × 9 种语言。

版本号 `0.13.1` → `0.14.0`。决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-07-30　0.13.1 移除 CurseForge 相关说明 + 修正一处过期硬编码版本号

- `README.md`、`README_EN.md`：下载安装说明里 CurseForge 提及去掉，只保留 GitHub Release 和 Modrinth。
- `README.md` 第 4 节硬编码版本号改为引用 `gradle.properties` 的 `mod_version`。

版本号 `0.13.0` → `0.13.1`。决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-07-30　0.13.0 悬浮说明从顶部标题挪到左侧列表本身 + 修复编译错误

- `ProviderListWidget#renderWidget`：悬浮 tooltip 从顶部标题移到左侧列表，复用 `rowHovered` 变量记录 `hoveredProviderId`，在 `disableScissor()` 之后统一画一次。
- `ServerConfigPanel` / `LocalConfigPanel` 的 `renderExtra` 移除顶部标题悬浮判定逻辑。
- `MCCFClient.java` 补上 `import java.util.List;`（0.12.0 遗漏）。

版本号 `0.12.0` → `0.13.0`。决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-07-30　0.12.0 提示区改为悬浮 tooltip + 聊天历史复用服务端 Conversation 分组 + 聊天栏可选显示原文 + 元信息补全

- `BOTTOM_HINT_AREA_HEIGHT` 从 100px 缩小到 50px，Provider 说明改为悬浮 tooltip。
- `SubtitlePayload` 新增 `conversationId`、`sourceLang`、`targetLang` 三个字段，改为手写 `PacketCodec.of(encoder, decoder)` 实现。
- 新增 `ConversationRosterPayload` 网络包：同步 Conversation 参与者名单。
- `Conversation.addParticipant` 改为返回 `boolean`，`ConversationManager.recordUtterance` 返回 `UtteranceResult`。
- 客户端新增 `ConversationRosterManager`、`ChatHistorySystemEvent`、`ChatTimelineItem`。
- `ChatHistoryManager` 新增 `groupedSnapshot()` 按 conversationId 分组。
- `ChatHistoryScreen` 重写：大标题列出参与者、组内消息和系统提示按时间混排、每条消息显示原文和译文、追加 `[源语言→目标语言]` 标签。
- 新增 `MCCFConfig.showOriginalTextInChat` 字段（默认关闭），`SpatialChatHandler#dispatchTo` 按 `displayMode` 选用对应开关。
- 客户端 VISIBLE 分支：`originalText` 非空时展示两行（原文 + 灰色译文）。
- `ServerConfigPanel` 新增两个并排开关。
- `fabric.mod.json`：`authors` 改为 `"LimAimo"`，`contact` 字段指向 GitHub 仓库。

版本号 `0.11.0` → `0.12.0`。决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-07-30　0.11.0 提示文字迁移到左下角空白区 + 修复两处误导性状态显示（未连接检测行、无限期加载中）

- 新增 `ProviderConfigPanel#renderLeftBottomHints`，提示文字从屏幕底部居中改为左侧列表正下方左对齐。
- `MCCFConfigScreen.BOTTOM_HINT_AREA_HEIGHT` 从 54px 扩大到 100px。
- `LocalConfigPanel.renderExtra`：未在任何世界中时不显示服务器检测结果行。
- `ServerConfigPanel` 新增 `snapshotRequestedAtMillis` 和 `SNAPSHOT_TIMEOUT_MS`（5000ms）三态判断，超时显示红色提示 + "重试"按钮。
- 新增"重试"按钮（`retryButton`）。
- 新增翻译键 `mccf.config.not_installed` 和 `mccf.config.retry` × 9 种语言。
- 移除 `ServerConfigPanel` / `LocalConfigPanel` 的 `renderExtra` 开头死代码 `if (!tabVisible) return;`。

版本号 `0.10.0` → `0.11.0`。决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-07-30　0.10.0 修复：Provider 列表 DeepSeek 消失（滚动缺失）+ 移除"设为默认"按钮，改为保存即生效

- `ProviderListWidget` 新增 `scrollOffset` 状态和 `mouseScrolled` 处理，支持鼠标滚轮滚动，渲染时用 scissor 裁剪，右侧画简易滚动条。
- `ServerConfigPanel` 和 `LocalConfigPanel` 移除 `activateButton` / `onActivate()`，`onSave()` / `performSave()` 直接把 `selectedProvider` 写入待启用目标。
- 两个面板控件行数各少一行，`buildRightPanel` 间距公式分母调整。
- `initialSelectedProvider()` 改为直接读 `state.activeProvider` / `config.activeProvider`。
- 移除翻译键 `mccf.config.activate` / `.activate.current` / `.activate_pending`（9 种语言）。

版本号 `0.9.0` → `0.10.0`。决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-07-30　0.9.0 优化：配置界面提示文字位置 + 修复潜在的文字/控件重叠

- 新增 `MCCFConfigScreen.BOTTOM_HINT_AREA_HEIGHT`（54px），`contentBottom` 计算改为 `this.height - MARGIN - BOTTOM_HINT_AREA_HEIGHT`。
- 底部提示文字统一改为 18px 固定行距。
- "强制服务器模式但未检测到服务器"从常驻警告文字改成 `ConfirmScreen` 拦截式确认弹窗。
- 新增 `mccf.localconfig.warn_force_server_title` 翻译键 × 9 种语言，移除 `warn_force_server_proceed` / `warn_force_server_cancel`。

版本号 `0.8.0` → `0.9.0`。决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-07-30　0.8.0 新增：英文 README + GitHub Actions 自动发布工作流

- 中文 README 顶部新增"这是什么？能给我带来什么好处？"和"怎么使用？"两节。
- 新增 `README_EN.md`。
- 新增 `.github/workflows/release.yml`：push 到 main 只编译测试，push `v*.*.*` tag 创建 GitHub Release 附带 jar，支持 `workflow_dispatch`。
- 新增 `.github/scripts/extract_changelog.py`：从 README 更新日志提取 Release Notes。

版本号 `0.7.0` → `0.8.0`。决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-07-30　0.7.0 修复：聊天历史界面行高 bug（一句话占满屏）+ 新增对话分组

- `HistoryListWidget` / `ModelListWidget` 构造函数保留 top/bottom 语义，内部换算成 `super(client, width, bottom-top, top, itemHeight)`，行高 12px。
- `ChatHistoryScreen` 新增"对话分组"显示：按时间间隔聚类（超 30 秒视为新对话），每组顶部显示参与者标题。
- 涉及文件：`ChatHistoryScreen.java`、`ModelSelectionScreen.java`、`ProviderListWidget.java`。

版本号 `0.6.2` → `0.7.0`。决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-07-30　0.6.2 新增：RateLimiter 单元测试 + 限流逻辑抽取

- 新增 `src/main/java/net/mccf/mod/util/RateLimiter.java`。
- 新增 `src/test/java/net/mccf/mod/util/RateLimiterTest.java`（5 个测试场景）。
- `ClientOnlyChatTranslator.java` 移除内部限流逻辑，改用 `RateLimiter` 实例。
- `build.gradle` 加 JUnit 5 依赖 + `test { useJUnitPlatform() }`。

版本号 `0.6.1` → `0.6.2`。决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-07-30　0.6.1 新增：暂停菜单入口 + 修复聊天历史记录找不到入口

- 用 `ScreenEvents.afterInit` 事件在 `GameMenuScreen` 追加"聊天历史记录"按钮。
- 新增翻译键 `mccf.history.button` × 9 种语言。

版本号 `0.6.0` → `0.6.1`。决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-07-30　0.6.0 新增：纯客户端本地设置面板的"获取模型列表"功能

- `LocalConfigPanel` 新增"获取模型"按钮，客户端直接调用 `listModels()` 发 HTTP 请求。
- `ModelSelectionScreen` 解耦 `ClientConfigState`，通过 `currentModel` 参数 + `selectionCallback` 回调传值。
- `ServerConfigPanel` 适配：传入 `state.getOrCreate(providerId).model` 作为 `currentModel`。

版本号 `0.5.1` → `0.6.0`。决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-07-29　0.5.1 修复：补齐配置界面布局改动后遗漏的翻译键

- 补齐 13 个翻译键 × 9 种语言：`mccf.config.tab.server` / `.local`、`mccf.config.activate` 系列、`mccf.history.title` / `.close` / `.empty`、`mccf.history.source.*`、`key.mccf.open_history`。

版本号 `0.5.0` → `0.5.1`。决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-07-29　0.5.0 修复：说话者收不到自己消息的回显 + Mock Provider 醒目警告 + 新增聊天历史记录

- 新增 `SpatialChatHandler#dispatchSelfEcho`，给说话者本人发原文回显。
- 客户端 `MCCFClient` 新增 `isSelf` 判定分支。
- 两个配置界面在选中 Mock Provider 时追加红色警告（`mccf.config.mock_warning`）。
- 新增 `ChatHistoryManager`（容量 500 条环形缓冲区）与 `ChatHistoryEntry`。
- 新增 `ChatHistoryScreen`：只读可滚动历史记录列表。
- 主配置界面新增"聊天历史记录"按钮 + 独立按键绑定 `key.mccf.open_history`。
- 新增文件：`ChatHistoryEntry.java`、`ChatHistoryManager.java`、`ChatHistoryScreen.java`。
- 涉及修改：`SpatialChatHandler.java`、`MCCFClient.java`、`ClientOnlyChatTranslator.java`、`MCCFConfigScreen.java`、`ClientOnlyConfigScreen.java`，9 种语言文件。

版本号 `0.4.0` → `0.5.0`。决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-07-29　0.4.0 修复：VISIBLE 模式字幕不显示，临时改回聊天框

- VISIBLE 模式消息改走原版聊天框，格式 `<Steve> 译文`。
- AUDIBLE 模式不变，仍走 `HotbarSubtitleRenderer`。
- `WorldSubtitleRenderer.java` 保留不删（0.16.0 才删除）。
- 涉及文件：`MCCFClient.java`、`WorldSubtitleRenderer.java`、`gradle.properties`。

版本号 `0.3.2` → `0.4.0`。决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-07-29　0.3.2 新增 7 种语言本地化

- 新增 7 种语言文件：`zh_tw.json`、`ja_jp.json`、`ko_kr.json`、`es_es.json`、`fr_fr.json`、`de_de.json`、`ru_ru.json`。

版本号 `0.3.1` → `0.3.2`。决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-07-29　0.3.1 编译错误修复：TextFieldWidget 密码遮盖 API + 注释 Unicode 转义

- `MCCFConfigScreen.java`、`ClientOnlyConfigScreen.java`：`setRenderPasswordReveal(boolean)` 改为 `setRenderTextProvider(BiFunction)`，字符替换成 `•`。
- `HttpProviderSupport.java`：注释里 `\uXXXX` 改为 `U+XXXX`。

版本号 `0.3.0` → `0.3.1`。决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-07-29　0.3.0 重大更新：字幕位置改造 + 强制客户端模式修复 + 性能与稳定性优化

- `WorldSubtitleRenderer.java`：字幕位置从"头顶上方"改为"模型旁边靠近相机一侧"，支持自动换行、距离衰减、半透明黑色背景。
- 新增 `ModePreferencePayload`（C2S 网络包）+ `ClientOnlyModeRegistry.java`，修复强制客户端模式 bug。
- `TranslationService`：失败结果不写缓存，缓存改为 LRU（5000 条）+ TTL（1 小时）。
- `ClientOnlyChatTranslator`：抽取 `translateAndAppend` 方法，新增 Provider 实例缓存，新增固定窗口限流（每秒 5 条）。
- `ModelSelectionScreen` 调用修复，`ClientOnlyConfigScreen` 新增强制服务器模式警告，新增 Provider 提示文字。
- 客户端首次进入游戏世界时显示提示（`mccf.tip.first_join`）。
- `WorldDictionary` 缓存 `Pattern` 对象，`ProviderFactory` 未知 ID fallback 加日志，`HttpProviderSupport` 用 Gson 替代手写 JSON 转义，`LogExporter` 改为流式读取。

版本号 `0.2.1` → `0.3.0`。决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-07-28　目标版本从 1.21.8 切换到 1.21.1

- `gradle.properties`：`minecraft_version` 改为 `1.21.1`，`yarn_mappings` 改为 `1.21.1+build.3`，`loader_version` 改为 `0.15.11`，`fabric_version` 改为 `0.116.15+1.21.1`，`modmenu_version` 改为 `11.0.4`。
- `build.gradle`：Fabric Loom 从 `1.14.10` 降到 `1.7.3`。
- `gradle/wrapper/gradle-wrapper.properties`：Gradle 从 `9.2.0` 降到 `8.8`。
- `fabric.mod.json`：`depends.minecraft` 改为 `~1.21.1`，`depends.fabricloader` 改为 `>=0.15.11`。
- `MCCFClient.java`：类注释"目标版本固定在 1.21.8"改为"1.21.1"。

决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-07-28　记入注释风格规范 + 新增"已确认规则"章节

- 规则同步记入项目记忆文件 `project_memory.md`。
- README 新增"九、已确认规则"章节，包含 9.1（版本号与 README 更新规则）和 9.2（注释风格规范，含 6 条子规则 + 9.2.5 版本踩坑汇总）。

版本号 `0.2.1` → `0.3.0`。决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-07-28　注释修正 + 版本号策略记入项目规则

- `HotbarSubtitleRenderer.java` 类注释里 `HudElementRegistry.addLast` 旧描述更新为 `HudRenderCallback.EVENT.register`。
- 全项目扫描 `TODO/FIXME/XXX/HACK` 标记：无残留。
- 记入项目规则：版本号策略 patch=bug/注释修复，minor=功能/版本切换/API 适配，major=重大重写。

版本号 `0.2.0` → `0.2.1`。决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-07-28　本地编译报告修复的 3 处 API 差异

- `RequestConfigPayload.java`：`PacketCodecs.BOOLEAN` → `PacketCodecs.BOOL`。
- `MCCFClient.java` + `HotbarSubtitleRenderer.java`：`HudElementRegistry.addLast(...)` → `HudRenderCallback.EVENT.register(...)`。
- `WorldSubtitleRenderer.java`：`getTickProgress(boolean)` → `getTickDelta(boolean)`。
- `ModelSelectionScreen.java`：`AlwaysSelectedEntryListWidget` 构造器从 6 参数改为 5 参数。

决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-07-28　JAR 命名 + 版本号策略

- `gradle.properties`：`archives_base_name` 从 `mccf` 改为 `MCConversationFramework`。
- `mod_version` 从 `0.1.0` 升到 `0.2.0`。

决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-07-17　包名修复 + 构建配置修复

- `HearingResolver.java`：`ServerPlayerEntity` 包路径修正，`RaycastContext` 包路径修正。
- `build.gradle`：补上 `${project.modmenu_version}`。
- Loom 插件版本锁定为 `1.14.10`。
- `mappings` 改回 Yarn。
- 移除 `fabricApi { configureDataGeneration() }`。

决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 同日　版本从 1.21.11 降到 1.21.8

- `gradle.properties`：全部改为 1.21.8 对应版本。
- `MCCFClient.java`：`KeyBinding` 构造函数改回字符串分类 + `wasPressed()`。

决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 同日　Gradle 版本改回 9.2.0

- `gradle/wrapper/gradle-wrapper.properties`：Gradle 改回 `9.2.0`。

决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 本地编译报告修复的 3 处 bug

- `gradle.properties`：`modmenu_version` 改为 `15.0.2`。
- `fabric.mod.json`：`depends` 里 `minecraft` / `fabricloader` 改为 `~1.21.8` / `>=0.16.14`。
- `RequestConfigPayload.java`：确认 1.21.8 用 `PacketCodecs.BOOLEAN`。

决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 新功能：API Endpoint 可配置 + 一键获取模型 + 日志导出 + 多语言

- 除 Mock 外全部 Provider 支持自定义 API Endpoint，`ProviderConfig.host` 重命名为 `endpoint`。
- 配置界面新增"获取模型"按钮。
- 配置界面新增"导出日志"按钮。
- 配置界面所有文字改用 `Text.translatable()`，提供 `zh_cn.json` 翻译。

决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 崩溃修复：未连接服务器时调整窗口导致崩溃

- `MCCFConfigScreen.init()` 等发包地方改用 `ClientPlayNetworking.canSend(payloadId)` 判断。

决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 字幕不显示问题修复

- `WorldSubtitleRenderer.java`：文字颜色 `0xFFFFFF` 改为 `0xFFFFFFFF`。

决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-07-22　README 整理

- 章节编号重新排列成 一~八 顺序。
- 目录结构对照实际源码重写。
- Fabric API 版本描述统一为 `0.130.0+1.21.8`，ModMenu 版本号标注为 15.0.2。
- 接入 Provider 示例改为对照 `ProviderFactory` + `ProviderDefaults`。

决策分析见 [docs/design-notes.md](docs/design-notes.md)。

### 2026-07-22　新功能：纯客户端模式

- 新增 `client/mode/ClientOnlyModeManager.java`：模式判定（自动检测 + 手动覆盖），持久化到 `config/mccf/client-mode.json`。
- 新增 `client/config/ClientOnlyTranslationConfig.java`：本地翻译配置，持久化到 `config/mccf/client-only-config.json`。
- 新增 `client/config/ClientOnlyConfigScreen.java`：配置子界面。
- 新增 `client/chat/ClientOnlyChatTranslator.java`：监听 `ClientReceiveMessageEvents.CHAT` 异步翻译追加显示。
- `MCCFClient.java` 改动：`onInitializeClient()` 加载模式管理器 + 注册聊天翻译器。

决策分析见 [docs/design-notes.md](docs/design-notes.md)。

---

## 九、已确认规则

> 本章节记录与团队/用户对齐过的**稳定规则**，供实现对照，避免后续改动时逻辑漂移。
> 和"八、更新日志"不同：更新日志是按时间的事件流（谁在什么时候改了什么），
> 本章节是稳定的约束（代码必须长期遵循的规范）。新规则记入这里之后，
> 只有在显式声明废弃时才能移除，不能因为某次改动"看起来不需要了"就悄悄省略。

### 9.1 版本号与 README 更新规则
- **触发条件**：每次完成一个功能或更改后，视情况更新项目版本号，并在 README 更新日志写明本次更改。
- **版本号位置**：`gradle.properties` 的 `mod_version` 字段。
- **版本号策略**（语义化版本）：
  - `patch`（x.y.**Z**）：小 bug 修复、注释修正、文档调整等不影响功能/构建的改动。
  - `minor`（x.**Y**.0）：新功能、版本切换（如 MC 版本适配）、API 适配修复、构建配置重大调整、工程规范重大变更。
  - `major`（**X**.0.0）：重大重写、架构变更。
- **README 更新位置**："八、更新日志"章节，按日期倒序新增条目。
- **"视情况"判断标准**：
  - 纯探索性查询、仅读不改 → 不更新版本号，可记可不记 README。
  - 改了源代码/构建配置/资源文件 → **必须**更新版本号 + 写 README 更新日志。
  - 只改了 README/注释 → 可只升 patch 并在 README 记一笔，也可不升版本号只记日志。
- **记入时间**：2026-07-28。

### 9.2 注释风格规范
本节的规则适用于**所有新写的代码注释和 Javadoc**，改老代码时如果碰到不符合规范的注释
也应该顺手修正。参考 Claude 的注释风格，核心原则：**注释的价值在于解释"为什么"，
而不是重复"是什么"**——代码本身已经说明了"是什么"。

#### 9.2.1 注释写"为什么"，不写"是什么"
- 用**叙述性长句**解释决策动机，而不是干巴巴描述代码行为。
- 反例：`// 重置血量`
- 正例：`// 玩家重生后必须显式 setHealth(20.0) 重置血量，因为死亡时残留的"当前血量"
  会被 RespawnScreen 复用到新角色身上；不能用 heal() 或治疗指令，那会触发 Regen
  效果的事件链，和"重生瞬间满血"的语义不符；这一步必须在 teleport(toSpawn)
  之前调用，否则复活点坐标更新后 PlayerEvent.Respawn 已经触发，血量会被
  respawn 逻辑二次覆盖。`

#### 9.2.2 把决策历史写进注释
- 遇到曾经改过的逻辑，注释里保留**决策脉络**：谁反馈的、旧版本错在哪、为什么改成现在这样。
- 团队决定**保留为特性**的部分（不是 bug），要显式声明
  "此行为经讨论后保留，不再处理"，防止后续接手的人当成 bug 再"修"一遍。
- 目的：让后人能还原决策过程，而不只是看到当前状态。

#### 9.2.3 类级别声明职责边界
- 每个**类的 Javadoc 开头**必须明确写："这个类只管 X，不管 Y（那是 Z 类的职责）"。
- 目的：防止越界改动。如果某个类的方法开始调用它"不该管"的东西，评审时一眼能看出来。
- 模板：
  ```java
  /**
   * <职责的一句话描述>
   *
   * 职责边界：
   * - 只管：X、Y
   * - 不管：A（那是 B 类的职责）、C（那是 D 类的职责）
   */
  ```

#### 9.2.4 对比论证：非显然的 API 选择必须说明
- 遇到非显然的 API 选择（比如 `setHealth` vs `damage()`、`teleport` vs 手动构造
  `TeleportTarget`），在注释里说明**为什么选 A 不选 B**，包括 A 的代价。
- 模板：`// 这里用 setHealth 而不是 damage()：damage 会触发 LivingEntityDamage 事件，
  被护甲/药水/模组拦截后扣血量不可控；setHealth 是直接写入，代价是绕过了所有
  "伤害处理"逻辑（包括死亡判断），所以调用前必须手动检查 currentHealth > 0。`
- 目的：避免后人"优化"代码时把 A 换成 B，触发当初已经踩过的坑。

#### 9.2.5 版本兼容性踩坑留在注释里
- 任何因为 MC / Fabric / Yarn 版本差异踩过的坑，都要在**对应代码位置**留注释。
- 格式：`// 1.21.1 的 DynamicRegistryManager 用 get() 而不是 getOrThrow()
  （后者是 1.21.5+ 才改的名字）。升级到 1.21.5+ 时这里要同步改名。`
- 已记入的版本踩坑（供查阅，代码位置也有对应注释）：
  - `PacketCodecs.BOOL`（1.21.1）↔ `PacketCodecs.BOOLEAN`（1.21.8+）——`RequestConfigPayload.java`
  - `RenderTickCounter.getTickDelta(boolean)`（1.21.1）↔ `getTickProgress(boolean)`（1.21.8+）——原 `WorldSubtitleRenderer.java`（0.16.0 已删除该文件，但这个版本差异知识点保留：未来若重新引入世界空间渲染会再次踩到）
  - `HudRenderCallback.EVENT.register`（1.21.1）↔ `HudElementRegistry.addLast`（1.21.6+）——`MCCFClient.java` / `HotbarSubtitleRenderer.java`
  - `AlwaysSelectedEntryListWidget` 构造器：1.21.1 是 5 参数 `(client, width, height, y, itemHeight)`（第 5 参数即 itemHeight），1.21.8+ 扩为 6 参数 `(client, width, height, y, bottom, itemHeight)`（重新加回 bottom）。**踩坑**：曾误判 1.21.1 第 5 参数为 bottom，把列表高度值传成 itemHeight 导致行高 bug（一句话占满屏），0.7.0 修正——`ModelSelectionScreen.java` / `ChatHistoryScreen.java`
  - `TextFieldWidget.setRenderTextProvider(BiFunction)`（1.21.1）替代已移除的 `setRenderPasswordReveal(boolean)`（1.20.x 及之前）——`MCCFConfigScreen.java` / `ClientOnlyConfigScreen.java`。1.21.1 没有"一行开启密码框"的开关，需自己传一个把字符替换成圆点的 `BiFunction`。
  - Java 源码注释中**不要写 `\uXXXX`**：Java 编译器在词法分析阶段（早于注释识别）就会处理 Unicode 转义，注释里的 `\uXXXX` 若 `X` 不是合法十六进制会报"非法的 Unicode 逃逸"。写 Unicode 码点请用 `U+XXXX` 形式——`HttpProviderSupport.java`。

#### 9.2.6 诚实承认不确定性
- 遇到没查清楚的底层行为，**直接写明**"没查清楚""索性完全绕开"，不要假装完全理解了。
- 模板：`// 清零 timeUntilRegen 仍不足以保证扣血生效，具体是哪个环节吞掉的没有查清楚
  （疑似 LivingEntity.damage 里有额外的 invulnerability 检查），索性完全绕开：
  用 setHealth 直接写入，不依赖 damage 管线。`
- 目的：让后人知道"这里还有未解之谜，不要盲目重构"，而不是误以为代码已经完全验证过。