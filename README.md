# Entity-Hack - 旧版 Minecraft（Indev/Infdev）实体操控器

**一个通过 Attach API 注入的、针对 2010 上半年旧版 Minecraft（Indev 20100223 ~ Infdev 20100415）的外置式实体添加 / 移除 / 修改工具。**

不需要安装 RetroWrapper，不修改 Minecraft 的任何文件，也不用 ASM/retransform——启动游戏并进入世界后再注入，用反射 + 动态代理在游戏内部直接生成实体、摘除实体、改实体字段。

> [!IMPORTANT]
> **📢 重要提示：**
> 1. 本工具是开源第三方工具，**只配合用户自行准备的正版 Minecraft 使用**，不提供、不包含任何游戏资源。
> 2. 由于程序通过 Java Attach API 注入其他进程，部分杀毒软件可能**误报**。本项目完全开源，请放心运行，或将其加入白名单。
> 3. 注入需要游戏**已进入世界**（世界里已经有玩家实体），未进入世界时工具会轮询等待，超时 90 秒。

---

## ⭐ 核心功能特性

- **实体添加**：按类型 + 坐标 + 数量生成实体，走该版本游戏自己的生成口（`spawnEntityInWorld`），生成后由游戏线程正常初始化。可生成清单是**按"渲染器不会当场 NPE"过滤**的：那个年代有些类（典型是 `EntityItem`）确实有 `(World)` 空构造、形状上完全"可生成"，但它渲染要用的字段没人赋值，一进登记表就被 `RenderItem` 解引用成空指针——**崩的是游戏主线程**。被踢掉的类型和原因会写在窗口"类型"页与日志里，不会默默少几个选项。
- **类型清单照实说"为什么生不成"**：类池每轮刷新重捞一次（绑定时世界还没加载完、快照是空的这种情况不会把清单冻死）；扫得到但反射生不成的实体类会分成两类写进"类型"页与日志——**这个 jar 里连 `<init>` 都没有的死壳类**（0227 的 5 只怪 + 2 只被动怪就是）与**构造器签名不是单个 `(World)` 的类**（连真实签名一起列出，本工具不会替参数编值）。
- **实体移除**：支持"立即摘除"（从登记表里真删）与"置死"（写死亡标志 + 交给每 tick 泵自删）两种语义，可单个删、批量删（自动跳过玩家）。
- **实体修改**：坐标（pos / prevPos / **碰撞盒** / `EntityMap` 格子一起搬）、动量三元组，以及**任意字段的通用反射编辑器**（血量、纹理路径、名称、计时器……不挑版本、不猜字段名）。老版的 `moveEntity` 是"从碰撞盒反推坐标"的，只写坐标会在下一 tick 被盒子拽回原位——所以这里必须把盒子当账本一起搬。
- **类型注册表操控**：Infdev 0413+ 的 `EntityList`（那个年代的"实体 id 空间"）可以查看、新增、劫持、解除 `id -> class` 映射。
- **外置式注入**：游戏启动后再注入，随时开随时关；卸载时把包装过的登记表还原成原始 `ArrayList`，对游戏本体零改动、无残留。
- **跨版本兼容**：基于"形状判据 + 运行期效果验证"定位 World / 实体基类 / 生成口 / 摘除口 / 死亡标志 / `IWorldAccess` / `EntityList` / `EntityMap`，**完全不依赖混淆字母**——混淆名每版都在漂移，判据不漂。
- **零第三方依赖**：只用 JDK 的 attach API + JDK 自带反射/动态代理，没有 ASM、没有 ByteBuddy、不改方法体。
- **借游戏线程执行**：把 World/EntityMap 上那个"元素是实体"的 `List` 字段换成透明代理，所有写操作排在游戏线程上执行，避开与 tick 循环的数据竞争。
- **换关卡自动跟随**：回菜单再进世界、Indev 换关卡之后游戏是 `new` 了一个新 World，工具会自己发现并搬过去（实体列表、生成/移除/改坐标、游戏线程通道一起跟），不需要重新注入。
- **离线结构探针**：`run.bat probe <jar|版本>` 不启动游戏、不真的 attach，就能验证定界逻辑认不认识某个实物 jar。
- **完整诊断日志**：Agent 全程写日志（位于用户主目录 `entity-hack-agent.log`），注入失败可快速定位；遇到问题可连同日志一起提交到 [Issues](https://github.com/CreatorCSIE/Entity-Hack/issues)。

---

## 🎮 支持版本

实测范围 = `%APPDATA%\.betacraft\versions\` 里的 7 个实物 jar（下方"实测结果"一节是完整数据）。构造器层面的逐版本清单（0227/0313/0316–0330/0413–0414 共 9 个 jar 的 `javap` 扫描）在 [docs/premain-ctor-patch.md](docs/premain-ctor-patch.md)。

| 版本 | 添加 | 移除 | 修改 | 类型注册表（id 空间） | 说明 |
| ---- | ---- | ---- | ---- | ---- | ---- |
| Indev 20100223 | ✅（**已真机注入**，见下方"真机注入记录"） | ✅ 立即摘除口 | ✅ | ❌ | 通路最完整；掉落物/箭这类"空构造 + 渲染器要的字段没人填"的类型被挡在清单外（否则游戏主线程 NPE），id 走 `LevelLoader` 字符串工厂、本工具未实现该通路 |
| Indev 20100227 | ⚠️ | ⚠️ | ✅ | ❌ | **已真机注入**（死壳期）：World 上没有任何实体方法，只能直写/直删 `EntityMap` 格子，且借不到游戏线程（就地执行，有竞态风险）。可生成清单只剩一个 `net.minecraft.a.c.a.e`（= `EntityMob` 基类）是**这个 jar 的客观事实**，见"已知限制"里那条字节码结论 |
| Infdev 20100313 | ⚠️ | ⚠️ | ✅ | ❌ | 同 0227（`EntityMap` 混淆名换成 `a.a.g`） |
| Infdev 20100316 | ✅ | ✅ 置死口 | ✅ | ❌ | 有每 tick 泵、有 `IWorldAccess`；实体不落盘。`javap` 实测 0316–0330 这一档可直连 11 个类、死壳**只剩 Giant 一只**（`a.c.a.a`），与 0227/0413 的"七只全空"不是同一回事 |
| Infdev 20100413 / 0414 | ✅ | ✅ 置死口 | ✅ | ✅ | 通路最全；但这一档与 0227 同族"构造器剥除"——可直连的具体实体类只剩 5 个（0415 有 13 个），另外 7 个叶类**连 `<init>` 都没有**（见"已知限制"与 [docs/premain-ctor-patch.md](docs/premain-ctor-patch.md)） |
| Infdev 20100415 | ✅ | ✅ 置死口 | ✅ | ✅ | 叶类构造器恢复，全部直通 |
| Classic（2009 系）/ Beta / 1.0+ | ❓ | ❓ | ❓ | ❓ | **未验证**：本工具的实测边界止于 inf-20100415；这些年代的结构形态不同，不做纸面承诺 |
| 经 LaunchWrapper 启动的旧版 profiles | ✅（预期） | ✅（预期） | ✅（预期） | 同版本 | 捞根时收集了 AWT 组件树上的全部 Applet 候选 + 线程 target 兜底；但 2010 上半年的 jar 通常不经 LaunchWrapper |

---

## 📂 目录结构 (Distribution Layout)

```text
Entity-Hack/
├── entity-hack.jar         # 最终产物（compile.bat 生成）
├── src/                    # 主源码
│   └── org/github/creatorcsie/entityhack/
│       ├── agent/          # Java Agent（注入后运行在 Minecraft 进程内）
│       │   ├── AgentMain.java      # agentmain 入口：捞根 -> 绑定 -> 挂通道 -> 弹窗
│       │   ├── GameFinder.java     # 捞候选根（Applet 组件树 / Thread.target）
│       │   ├── GameShape.java      # 纯结构判据（离线探针与运行期共用同一套）
│       │   ├── GameBinder.java     # 定界 + "探针实验"语义分类
│       │   ├── Handles.java        # 绑定结果句柄（World/实体/登记表/三元组/各操作口）
│       │   ├── TickPump.java       # 借游戏线程：包装登记表 List（主）/ IWorldAccess（补）
│       │   ├── WorldWatch.java     # 换关卡监视：发现 World 实例被换掉就搬句柄 + 重挂通道
│       │   ├── EntityOps.java      # 添加 / 移除 / 修改 / 注册表读写
│       │   └── EntityWindow.java   # 实体操控 GUI（三个页签）
│       └── loader/         # 注入器（GUI + 命令行 attach + 离线探针）
│           ├── Main.java
│           ├── InjectorGui.java
│           └── JarProbe.java       # 实物 jar 离线结构探针
├── test/mock/              # 三套"仿造的老 Minecraft 世界" + 端到端断言
│   ├── mock/MockMain.java          # 206 项断言驱动
│   ├── v0413/net/minecraft/...     # Infdev 0413/0415 形态
│   ├── v0223/net/minecraft/...     # Indev 0223 形态
│   └── v0227/net/minecraft/...     # Indev 0227/0313 死壳形态
├── docs/                   # 取证笔记（源码仓库才有，不进 release zip）
│   └── premain-ctor-patch.md       # 死壳实体类清单 + 「补构造器」唯一合法时机（premain / 离线改 jar）
├── lib/tools.jar           # JDK attach API（compile.bat 自动从 JDK 生成）
├── natives/attach.dll      # attach 原生库（compile.bat 自动从 JDK 生成）
├── build/                  # 编译产物（.class，不入库）
├── build-test/             # Mock 测试编译产物（不入库）
├── compile.bat             # 构建脚本（需 JDK 8）
└── run.bat                 # 运行脚本（只需 JRE 8）
```

---

## 🔧 从 Release 下载（推荐，无需 JDK）

普通用户不需要源码，也不用安装 JDK——直接去 [Releases](https://github.com/CreatorCSIE/Entity-Hack/releases) 下载**完整构建包**（zip），解压即可使用：

```text
完整构建包内容：
├── entity-hack.jar        # 主程序（同时也是 Java Agent）
├── lib\tools.jar          # attach API（已内置，无需 JDK）
├── natives\attach.dll     # attach 原生库（已内置）
└── run.bat                # 一键运行入口
```

1. 下载并解压最新版 zip。
2. 启动 Indev/Infdev 版 Minecraft（20100223 ~ 20100415，建议用 BetaCraft 启动器）并**进入世界**。
3. 双击 `run.bat` 打开注入器，选中 Minecraft 的 JVM 点击**注入**；或直接 `run.bat --attach <PID>`。

运行时只需 **JRE 8**（没有任何 Java 程序的话，安装 [Java 8 JRE](https://www.oracle.com/java/technologies/javase/javase8-archive-downloads.html) 即可）。注入器与游戏必须使用**同一台机器上的 JVM**，且注入方 JVM 位数要与游戏一致（32/64 别混）。

---

## 🧰 构建 (Build)

*以下仅面向**开发者 / 想要自己编译**的用户。普通用户请走上方 Releases 下载，无需 JDK。*

需要 [JDK 8](https://www.oracle.com/java/technologies/javase/javase8-archive-downloads.html)（`compile.bat` 按 `ENTITY_HACK_JDK` → `JAVA_HOME` → `%ProgramFiles%\Java\jdk1.8*` 顺序自动探测，不必改脚本）：

```bat
compile.bat          REM 编译并打包 entity-hack.jar（同时从 JDK 生成 lib\tools.jar 与 natives\attach.dll）
compile.bat test     REM 额外编译 Mock 测试到 build-test\
```

*提示：`lib\tools.jar` 与 `natives\attach.dll` 是从 JDK 自动复制出来的运行时依赖，已加入 `.gitignore`，clone 后跑一次 `compile.bat` 即自动生成。字节码固定为 `-source 1.7 -target 1.7`，以保证能被那个年代的 JVM 加载。*

---

## 💻 运行 (Run)

运行时只需 **JRE 8**（JDK 也可）：

```bat
run.bat                          REM 打开注入器 GUI（列出本机 JVM，选择后点注入）
run.bat --attach <PID>           REM 直接注入指定进程
run.bat probe <jar|版本名>        REM 离线结构探针，例：run.bat probe inf-20100415
run.bat probe all                REM 对 7 个实物 jar 逐个探针并与分析文档矩阵对照
run.bat mock                     REM 跑 Mock 端到端验证（需先 compile.bat test）
```

**使用流程**：启动 Minecraft 并进入世界 → `run.bat` 打开注入器 → 选择 Minecraft 的 JVM 点击注入 → 游戏进程内弹出实体操控窗口 →

- **「实体」页签**：在场实体表（id / 实现类 / 坐标 / dead 位）；选类型 + 坐标 + 数量点"生成"；选中后"移除选中 / 移除全部（保留玩家） / 切换死亡位 / 把 x/y/z 写入选中实体 / 写入动量"；下方"字段编辑"表可逐个字段直接改。
- **「类型注册表」页签**：`EntityList` 的 `id -> class` 映射表，可注册/劫持/解除（仅 0413+；0223~0316 该页会提示没有注册表）。
- **「状态」页签**：本次定界得到的全部句柄（World / 实体基类 / 三元组字段 / 生成口 / 摘除口 / 死亡标志 / 通道类型 / 回调次数），排错就靠它。

---

## 🧠 工作原理

1. **注入**：注入器用 `com.sun.tools.attach.VirtualMachine` 附加到 Minecraft 的 JVM，`loadAgent(entity-hack.jar)`；jar 的 `Agent-Class` 指向 `agentmain(String, Instrumentation)`。
2. **捞根**：这个年代的 Minecraft 没有 `Minecraft.theMinecraft` 静态单例，实例只被 Applet / 游戏线程持有。于是从 `Frame.getFrames()` 的 AWT 组件树收集全部 Applet 候选，加上"线程 `target` 是游戏类实例"这一路兜底，逐个尝试绑定。
3. **定界（纯形状）**：
   - **实体基类** = 一个类里连排 ≥9 个同型 `float/double`（= prevPos + pos + motion 三元组全声明在它身上）+ 大量数值字段 + 被一堆 mob 继承 + **不是**"实例登记表"类。最后一条是关键：实物 jar 里 `Block` 也满足"6~8 连 double"、子类数量还比实体多（31~32 个），但它有 59~62 个 `static` 的自身类型字段（`Block.stone` 那种枚举表），实体基类恒为 0 个。
   - **World** = 有容器字段（`List` / `List[]` / 内含 `List[]` 的 `EntityMap`）且与实体类型发生引用（字段/方法参数/返回值），并且不在实体继承链上。
   - **AABB / IWorldAccess / EntityList / EntityMap** 各有独立形状判据（详见 `GameShape`）。
4. **抓实例**：实体对象上那个"世界形状"的字段就是 World 入口——反过来，从玩家实例就能抓到 World，不需要改写任何方法体。
5. **数值三元组分辨**：可读源里 `prevPos → posX → motionX` 恒是连排声明的（Indev 0223 是 `Entity.java:24/27/30`，Infdev 0413 是 `:26/31/36`），三组齐全时**直接按声明顺序拿**。不要拿数值大小猜这一组：刚构造出来、还没走过一 tick 的实体 `prevPos` 正好是 0，比 `motion` 还小，猜大小会把 `prevPos` 当成 motion，于是写位置时漏掉 `prevPos`——渲染插值会直接从旧位置拉回来。只剩两组时才退回幅度判据（动量量级远小于坐标）。
6. **借游戏线程**：把登记表 `List` 字段换成 `Proxy` 实现的 `List`——所有调用原样转发给底层 `ArrayList`，转发**之后**顺手排空我们的待办队列，于是每个写操作都真的跑在游戏线程上。补充通道是用 `Proxy` 实现 `IWorldAccess` 塞进部件表（实测它并非每 tick 都回调，只能当补充）。
7. **语义分类（探针实验）**：造一个一次性实体，把 World 上"单实体参数的 void 方法"逐个调一遍，观察登记表包含关系变化与实体上布尔字段的翻转——
   - 不在表 → 在表 = **生成口**；在表 → 不在表 = **立即摘除口**；某个布尔位 `false → true` = **死亡标志** + 对应的**置死口**。
   - 效果是唯一可信的证据：混淆名每版都在漂，"看起来像 spawnEntityInWorld"的名字什么都证明不了。
8. **增 / 删 / 改**：添加 = `new 子类(World)` + 写坐标（含碰撞盒）+ 调生成口；移除 = 置死口（交给每 tick 泵）或立即摘除口，兜底直删登记表；修改位置 = `Handles.applyPosition` 一口气做完"写 pos/prevPos → 平移碰撞盒 → 搬 `EntityMap` 格子"，因为老版的账本是碰撞盒和格子，坐标只是读数。
9. **可生成清单：类池每轮重捞，生不成的类照实说明**：候选类来自 `Instrumentation.getAllLoadedClasses()`，每轮刷新重捞一次——防的是"绑定时世界还没加载完、那一刻的快照是空的"（冻在快照上清单就永远出不来），纹理串探测（试构造一个实例、看它身上有没有 `.png` 串）按类记忆化，免得每 1.2 秒真去 new 一个一次性实体。更重要的是 `GameBinder.reportUnconstructable`：只报"哪个类被踢掉、为什么"还不够，**扫得到但反射进不去**的类也要分两类列出来——① 这个 jar 里连 `<init>` 都没有的死壳类（真机 in-20100227 的 `a.c.a.a/b/c/d/f` 与 `a.c.b.b/c`：`javap` 只剩 `e()/b()/f()`，ProGuard 把没人调用的构造器整条删了，那几年 mob 本来就被移除了）；② 构造器存在但签名不是单个 `(World)` 的类，连真实签名一起列出（如 `a.c.c.a(World, float, float, float)`、`client.f.j(World, double ×6)`），本工具不会替这些参数编值——坐标之外的参数一填错就是渲染器 NPE，正是上一轮把游戏打死的那类。绕开构造器的路子（`Unsafe.allocateInstance`）只会造出 `id`/碰撞盒/纹理全空的壳，同样不做。**一条硬约束**：可读源（RetroMCP 反编译产物）会**补出**真实 jar 里并不存在的构造器，所以关于"能不能反射生成"的结论只能下在 `javap` 上——本项目上一轮就是吃了这个亏。这批死壳类在类定义之前到底能不能补回 `<init>`、怎么补，见 [docs/premain-ctor-patch.md](docs/premain-ctor-patch.md)。
10. **换关卡自动跟随**：老 Minecraft 换世界不是"清空复用同一个对象"，而是 `new World(...)` 之后把游戏实例上那个世界字段整个换掉；旧 World 里的登记表原样躺在内存中，只是游戏再也不去遍历它。所以绑定时抓到的那个实例必须有人复查——`WorldWatch` 每轮刷新问一次"游戏实例上那个字段还指着我们手上这个 World 吗"，被换掉就搬 `handles.world`、拆掉旧登记表上的代理、在新世界上重挂游戏线程通道（不换的后果不只是列表停在旧世界，还有排进队列的生成/移除/改坐标**永远不会执行**）。代理只能装在"元素是实体"的 `List` 上，而刚切进新世界的那一刻格子表常常还是空的（真机 0227 日志：切换完成、在场实体 0 个、补挂试满 5 次就永久放弃了），所以补挂判据改成**看表里有没有实体**：空表安静等着，一旦出现实体就每 5 秒补挂一次、累计 10 次为止。
11. **卸载还原**：关闭窗口后 `TickPump.uninstall()` 把字段换回原始 `ArrayList`，代理不留痕。

---

## 📊 实测结果（本机 Windows + JDK 1.8.0_202）

### 1. 实物 jar 离线探针（`run.bat probe all`）

与分析文档 `minecraft-verification-analysis/minecraft-attach-api-entity-manipulation.md` §1 的矩阵逐格对照，**7 个 jar × 4 个锚点 = 28 项，全部 `[OK]`，0 项 `[DIFF]`**：

| 版本 | World | 实体基类 | EntityList | EntityMap 网格 | `(World)` 构造器可直连的具体实体类 |
| ---- | ----- | -------- | ---------- | -------------- | ---------------------------------- |
| in-20100223 | `a.a.g` ✅ | `a.c.b` ✅ | 无 ✅ | `a.a.i` ✅ | 13 个 |
| inf-20100227 | `a.a.e` ✅ | `a.c.b` ✅ | 无 ✅ | `a.a.h` ✅ | 5 个 |
| inf-20100313 | `a.a.f` ✅ | `a.c.b` ✅ | 无 ✅ | `a.a.g` ✅ | 5 个 |
| inf-20100316 | `a.a.e` ✅ | `a.c.b` ✅ | 无 ✅ | 无（单一 `loadedEntityList`） ✅ | 11 个 |
| inf-20100413 | `a.a.g` ✅ | `a.c.b` ✅ | `a.c.e` ✅ | 无 ✅ | **5 个**（0413/0414 构造器剥除案） |
| inf-20100414 | `a.a.g` ✅ | `a.c.b` ✅ | `a.c.e` ✅ | 无 ✅ | **5 个** |
| inf-20100415 | `a.a.g` ✅ | `a.c.b` ✅ | `a.c.e` ✅ | 无 ✅ | 13 个（叶类构造器恢复） |

同时探针还独立复现了文档的两条"结论级"事实：**0227/0313 是死壳期**（World 上单实体参数的 void 方法数 = 0，`IWorldAccess` 定界不到）；**0223/0316/0413/0414/0415 有实体方法通路**（0223 找到 `a(Entity)`+`b(Entity)`，0413+ 找到 `a(Entity)`+`static b(Entity)`）。实体基类在六个年代里混淆名**始终是 `a.c.b`**，而 `Block`（`a.a.b.x`，32 个子类、8 连 double）被"实例登记表"判据正确排除。

> 最后一列是**纯形状**的计数（有 `(World)` 构造器的具体实体子类）。运行期的可生成清单还要再过一遍"渲染安全"判据——0223 那 13 个里就混着 `EntityItem` 这种"空构造 + 渲染要用的字段没人填"的类型，放出来是崩游戏主线程，所以会被踢掉并写明原因。

### 2. Mock 端到端验证（`run.bat mock`）

三套仿造世界分别复刻 0413（9 连 `double` + 每 tick 泵 + 静态置死 + `EntityList` + 构造器剥除的 mob 叶类）、0223（`EntityMap` 网格 + 11 连 `float` + 立即摘除口、无注册表）、0227（死壳：只有格子、无 API、无泵）。每套完整跑一遍真实链路：**捞根 → 形状定界 → 借游戏线程 → 探针语义分类 → 生成 3 个实体 → 改坐标 → 改字段 → 注册表往返 → 移除 → 批量清空 → 换关卡 → 卸载还原**。

```text
断言 206 项，失败 0 项
```

覆盖到的关键断言：三套的 World / 实体基类 / `IWorldAccess` / AABB / 三个三元组字段名全部命中预期；`EntityLiving` 不会被误判成实体基类；0413 与 0223 的主通道都判定为 `list`（而不是 `access`）；探针实验在 0413 上分类出生成口 `a` + 置死口 `b` + 死亡标志 `u`、在 0223 上分类出生成口 `a` + 立即摘除口 `b`、在 0227 上正确地"什么都分类不出来并在日志标注死壳期"；0413 上 `idOf()` 经 `EntityList` 返回 `"Creeper"`，且那个没有 `(World)` 构造器的 `Skeleton` 叶类被自动排除在"可生成清单"之外、但注册进 `EntityList` 后 `idOf` 立刻认得它。

真机踩到的坑各自有独立的回归段（不再是"跑一遍主流程顺带覆盖"）：

- **改坐标反弹**：0223 仿造世界的 `a_()` 完全照可读源 `Entity.java:304-306` 写——动量先作用在碰撞盒上、**坐标再从盒子反推**。断言要求改完坐标后**连续跨 15 个 tick** 仍守着新位置（只读一次不算数），并且碰撞盒中心、`EntityMap` 格子索引三者一起落在目标位置。
- **生成实体崩游戏**：`mock.v0223...a.c.f` 是照 `EntityItem` 建模的陷阱类（`(World)` 空构造 + 渲染要用的 `item` 恒为 null + 一个模拟 `RenderItem.a()` 解引用的方法）。断言钉住三件事：形状判据确实会放行它（所以**必须**过滤）、安全清单里没有它而过滤原因写进了 `lastFilterNote`、清单里放行的类型逐个试构造后走渲染路径都不抛。
- **渲染引擎陷阱**：真机 0223 的 `World` 一度被认成 `client.a.b`（渲染引擎满足"世界形状"、方法数还更多，实体只存在于 `EntityMap` 网格里、玩家字段全是 null）。这一段专门复现该状态，验证绑定走的是"从实体身上反抓世界"。
- **换关卡后列表不再更新**：每套仿造世界的 `client.d.a()`（= 重新载入一个关卡，`new` 一个新 World 顶掉旧字段）都在测试中途调一次，断言 `WorldWatch` 能发现取代者、`handles.world` 换成了新实例、旧世界的玩家不再出现在表里、重复检查不会来回抖，并在新世界上真的生成一个实体（这一步同时证明游戏线程通道重挂上了——通道没重挂的话排进队列的操作会永远没反应）。
- **类池重捞 + "生不成的类要照实说"**：`mock.v0227...a.c.c` 照真机 javap 的 `EntityMob` 建模成**非 abstract**、带 `(World)` 构造器的中间基类，叶类 `a.e.b`（cow）挂在它下面。第一组断言把叶类从类池里摘掉（模拟"快照里缺它"）：这时清单里留下的正是那个基类——真机 0227 看到的"只剩一个类型"就是这个形状；把叶类放回池子后，基类要立刻被"有更具体的子类"踢掉、叶类出现在清单上，而且踢人原因里写的是**类全名**（真机日志只报末段的 `a：…`、`e：…` 时根本分不清说的是哪个类）。第二组断言管 `reportUnconstructable`：`a.e.c` 只有 `(World, int)` 构造器，必须连**签名**一起出现在说明里（javac 总归要生成一个构造器，所以真机那种"零构造器的死壳类"没法在 mock 里复刻，那一条是 `javap inf-20100227.jar` 的直接结论）。

### 3. GUI 冒烟（`run.bat mock gui`）

用 mock 世界真的把窗口建出来、跨过好几个 1.2 秒刷新周期，验证选中行/字段表/类型下拉选中项都还在，点一次"生成"走完整条链，最后再让 mock **重新载入一个关卡**：断言不用重新注入，窗口自己就跟上新的 `World` 实例（列表换成新世界的实体、游戏线程通道重挂成功）。**34 项断言，失败 0 项**。这一段是真机三轮界面反馈（字段表闪一下变白、下拉被抹空、操作区被布局挤没）与"换世界后列表不动"的回归。

### 4. 真机注入记录（in-20100223，BetaCraft 实物）

| 轮次 | 现象 | 定位到的根因 | 处理 |
| ---- | ---- | ---- | ---- |
| 1 | 弹窗后游戏整个无响应 | 窗口在 EDT 上抢焦点，与游戏线程的 `AWTTreeLock` 三方互锁 | `setFocusableWindowState(false)` + 400ms 后 `javax.swing.Timer` 还焦 |
| 2 | 字段表闪一下变白、一个字都打不进 | 刷新循环整表重建 + 单元格默认不可编辑 | 不再重建模型；`isCellEditable` 修好 |
| 2 | 看不到"生成/移除"按钮 | 布局把操作区挤成 0 高度 | 改 `JSplitPane` 布局 |
| 3 | 类型下拉选不上、"添加实体"像没实现 | `removeAllElements()` 每 1.2 秒把选中项抹成 null；生成结论没有回流界面 | 保留选中项 + 状态区回显生成结果 |
| 4 | 生成的实体进不去、稳定 18 行 | `World` 被认成渲染引擎 `client.a.b`（实体只在 `EntityMap` 网格里） | 改成"先从登记表拿一个实体，再读实体身上的世界字段" |
| 5 | `创建新实体会直接空指针`（游戏主线程 `NullPointerException`） | 生成清单里有 `EntityItem`：可读源 `EntityItem.java:33-35` 的 `(World)` 空构造能过，但 `item`（同文件第 13 行）是 null，`RenderManager.entityRenderMap` 里 `EntityItem -> RenderItem`，下一帧渲染就崩 | 可生成清单/探针统一走"渲染安全"过滤，被踢掉的类型连原因一起显示在"类型"页 |
| 5 | `改实体位置会反弹` | `Entity.moveEntity` 末尾从碰撞盒反推坐标（`Entity.java:304-306`）——盒子是账本、坐标是读数；而且 `EntityMap.updateEntities` 先 `lastTick := pos` 再判跨格，传送后永远判不出跨格，格子也不会跟着搬 | `applyPosition` 一次做完"写 pos/prev + 平移碰撞盒（优先走游戏的 `offset`，认不出就直写六元组）+ 搬 `EntityMap` 格子"，并在 1.5 秒后回读打日志 |
| 6 | `世界切换的时候程序这边没有更新实体列表` | 换关卡是 `new World(...)` 顶掉游戏实例上的世界字段，旧 World 里的登记表原样躺在内存里、只是不再被遍历；我们绑定的还是那个旧对象，于是列表冻结。更糟的是 `TickPump` 的代理装在**旧**登记表上，排进队列的生成/移除/改坐标再也不会被执行 | 新增 `WorldWatch`：每轮刷新复查那个字段，被换掉就搬 `handles.world` + 拆旧代理 + 在新世界上重挂通道；状态页有"换关卡监视"一行，切换结论同时进日志与状态区。**真机 0227 已复验"实例跟上了"**（日志连着四行"世界切换：World 实例 … -> …"），但那一轮暴露出"切进新世界时登记表还是空的 → 代理包装不上 → 补挂试满 5 次就永久放弃"，于是补挂判据改成**看实体在不在场**：空表安静等着，一旦表里有实体就每 5 秒补挂一次，累计 10 次才认输 |
| 7 | `测 0227 时，发现只能找到一个实体类型（没有 0223 那么多）` → `我知道了，那个 a.c.a.e 是 Monster 基类` | **`javap inf-20100227.jar` 的结论，不是判据 bug**：那个 jar 里有 `(World)` 构造器的具体实体类一共只有 5 个（`a.c.a`、`a.c.c`、`a.c.e`、`a.c.e.a`、`a.c.a.e`），其中 2 个是玩家祖先、2 个是基类，剩下的就是 `a.c.a.e`（`EntityMob`）。它下面那 7 只怪（`a.c.a.a/b/c/d/f` 与 `a.c.b.b/c`）在字节码里**一个构造器都没有**——只剩 `e()/b()/f()` 这些方法，`<init>` 被 ProGuard 整条删了（那几年 mob 本来就被移除了）。可读源里那些 `public EntityPig(World)` 是 **RetroMCP 反编译时补出来的，不能当依据**：我上一轮就是照那份源推出"叶类用到才加载、所以类池要活/要预加载"的判断，真机加预加载跑了一遍（273 个类名全部解析成功）证明候选数 **5→5 纹丝不动**，于是把 `ClassPreloader` 撤掉 | ① 类池仍每轮重捞（防的是"绑定时世界还没加载完、快照是空的"，与 0227 无关）；② `GameBinder.reportUnconstructable` 把"扫得到但生不成"的类分两类照实写进"类型"页与日志：**这个 jar 里连构造器都没有**（报总数 + 类名）与**构造器签名不是单个 `(World)`**（连真实签名一起列出，本工具不替参数编值）；③ 过滤原因、下拉框与日志一律改说**类全名**——真机日志里 `a：…`、`e：…` 这种只报末段的行，在同一段混淆包名下根本分不清说的是哪个类。离线回归 206 项已过，**真机待复验**（要看"类型"页是否把那 7 只死壳怪与 `(World, double×6)` 那批签名讲清楚） |

同一轮还修了三元组分辨：0223 的探针实体刚构造出来时 `prevPos` 恰为 0，原来的"按幅度猜"会把 `prevPos` 当成 `motion`，导致写位置时漏写 `prevPos`（渲染插值当场从旧位置拉回来）。现在三组齐全时按声明顺序拿。

### 5. 尚未实测的部分（如实声明）

- **真机验证目前覆盖 in-20100223 与 in-20100227**：其余五个实物 jar 走的是"离线探针（判据与运行期同一份代码）+ 仿造世界端到端"，还没有逐个真机注入过。如果你在其他版本上跑不通，请把 `entity-hack-agent.log` 与版本号一起提交到 Issues。
- **0223 的 `LevelLoader` 字符串工厂 id 通路**：文档记录存在，本工具未实现（0223 的类型识别退回"类名"）。
- **Classic / Beta / 1.0+**：未做结构核对，不做支持承诺。

---

## ⚠️ 已知限制

- **0227 / 0313 死壳期**：World 上既没有生成/摘除 API，也没有 `IWorldAccess`，`EntityMap` 的格子表开局还是空的（32770 张格子里只有玩家那一项），所以**注入那一刻常常借不到游戏线程**——操作就地执行，与游戏 tick 循环存在数据竞争。真机 0227 的日志显示：世界里有实体之后补挂是能成功的（`包装 net.minecraft.a.a.h.g (2 项) … callbacks=136 -> 游戏线程通道可用`），因此工具会在表里出现实体后每 5 秒再试一次；写 `EntityMap` 格子时坐标必须落在正确格子里（`(int)(pos / 8.0F)`），否则实体会被"看不见地"漏掉。窗口"状态"页会明确标出通道在不在。
- **0227 的可生成清单只有一个类型，是这个 jar 的事实**：`javap inf-20100227.jar` 显示有 `(World)` 构造器的具体实体类一共只有 5 个，其中 2 个是玩家祖先、2 个是基类，剩下的那个就是 `net.minecraft.a.c.a.e`（`EntityMob`）；它下面 7 只怪（`a.c.a.a/b/c/d/f`、`a.c.b.b/c`）在字节码里**连 `<init>` 都没有**（只剩 `e()/b()/f()` 这类方法，ProGuard 把没人调用的构造器删掉了——那几年 mob 本来就被移除了）。所以这不是"工具没扫到"，在"附加到已在跑的游戏"这个形态下也没有绕法：`Unsafe.allocateInstance` 那种硬造只会得到 `id`/碰撞盒/纹理全空的壳，下一帧照样把游戏主线程打死。工具能做的是把这件事**写在界面上**（死壳类总数 + 类名、以及 `(World, float, float, float)` / `(World, double ×6)` 那批"签名不合"的真实签名）。附带一条教训：`src-minecraft-*` 那份可读源是 RetroMCP 反编译产物，会**补出**真实 jar 里不存在的构造器，关于 ctor 的结论只能下在 `javap` 上。补构造器的唯一合法时机（类定义之前：premain 加载期插桩 / 离线改 jar）、逐版本死壳清单、以及"渲染侧要不要一起补"都已取证归档在 [docs/premain-ctor-patch.md](docs/premain-ctor-patch.md)。
- **可生成清单来自"进程里已加载的类"**：候选类每轮刷新从 `Instrumentation.getAllLoadedClasses()` 重捞一次（防的是"绑定时世界还没加载完、快照是空的"）。真机试过更主动的一步——按实体基类的 `CodeSource` 把整个 jar 的类名预加载进来（0227 上 273 个类名全部解析成功），结果候选数 5→5 纹丝不动，说明懒加载对这个年代根本不构成瓶颈，该机制已撤掉。
- **可生成清单是"渲染安全"过滤后的清单，不是全部实体类**：形状上能构造、但渲染器会当场 NPE 的类型（Indev 0223 的掉落物 `EntityItem` 是实测案例，箭 / TNT / 画同族）被挡在生成口外面——放它们进去的代价是**游戏主线程崩掉**，不是工具报错。窗口"类型"页与日志会列出被踢掉的类型和原因；确实需要这些实体时，走游戏自己的逻辑（丢方块/射箭）而不是从清单里硬生成。
- **改位置是"搬账本"而不是"写读数"**：老版 `moveEntity` 每 tick 末尾从碰撞盒反推坐标（`Entity.java:304-306`），所以工具会连着搬碰撞盒与 `EntityMap` 格子。若某个版本认不出盒子的六元组（或盒子当下是 null），日志会照实写"可能仍会被拽回原位"，并在 1.5 秒后回读一次打出"守住了 / 被游戏改回去了"——**不要**把界面显示的即时回读当成最终结果。
- **0413 / 0414 的构造器剥除案**：与 0227 同族，`javap` 实测七个叶类（`a.c.a.a/b/c/d/f`、`a.c.b.b/c`）**声明构造数 = 0**，即连 `<init>` 整条都没有（本条早期描述有误，写成"只剩 `(World, int)` 之类的签名"，已按字节码更正）。工具的处理办法是把它们排除在"可生成清单"之外（而不是硬闯），并在"类型"页照实写出这批"连构造器都没有"的类全名；确实需要这些 mob 时走 `EntityList` 注册表 + NBT 通路，或在类定义之前补 `<init>`（见 [docs/premain-ctor-patch.md](docs/premain-ctor-patch.md)）。
- **换关卡监视的前提**：复查的是"游戏实例（`Minecraft`）上那个声明为 World 类型的字段"。真机 0223/0413 都是这种写法（`Minecraft.currentLevel`），所以换世界能自动跟上；如果某个版本把世界藏得更深（要绕两跳才够到），工具会在"状态"页明说"认不出游戏实例上的世界字段，换世界后必须重新注入"，而不是假装在监视。另外**回菜单、世界已卸载**这段时间不会把列表清空——它仍显示旧世界最后一次扫到的内容，等你进新世界后一并刷新。
- **写操作排队生效**：借到游戏线程时，操作是排在队列里等下一个 tick 执行的（生成后约一帧才出现在表里），所以界面显示"已提交"而不是"已完成"。
- **实体不落盘的年代**：0227~0316 没有实体持久化，重启世界这些实体自然消失；0413+ 才有 `EntityList` + NBT。

---

## 📄 许可证与版权声明 (License & Copyright)

- **开源协议**：本项目采用 **[GNU General Public License v3 (GPL v3)](LICENSE)** 协议开源。
- **版权所有**：Copyright (c) 2026 **CreatorCSIE**. All rights reserved.
- **法律免责声明**：
  - 本项目为开源第三方工具，与 **Mojang Studios** 或 **Microsoft** 无任何关联。
  - 本仓库**不包含、不分发**任何受版权保护的 Minecraft 二进制资源。使用本工具需要用户自行准备正版 Minecraft。
  - Minecraft 的商标及所有相关资产版权归其各自所有者所有。

---

## ❓ 常见问题

- **为什么构建要 JDK，运行却只要 JRE？** 编译期需要 `tools.jar` 里的 attach API；运行时所需的 attach 类与原生库已随项目分发（`lib\` + `natives\`），因此 JRE 就够了。
- **注入后窗口没出现？** 确认游戏已进入世界（世界里已有玩家），再看 `~/entity-hack-agent.log`（用户主目录）与窗口"状态"页。日志里 `candidate ... failed` 那一行会写明卡在哪一步。
- **换了世界（回菜单再进、Indev 换关卡）之后要重新注入吗？** 不用。工具的刷新循环每 1.2 秒复查一次游戏实例上的世界字段，发现被换掉就搬句柄、把游戏线程通道重挂到新的登记表上，日志里会有一行"世界切换：World 实例 … -> …"。如果"状态"页写着"认不出游戏实例上的世界字段"，那一版就得重新注入（把日志一起提到 Issues，我们可以补那条通路）。
- **为什么不 ASM/retransform 改写方法体？** 只需要"拿到游戏线程"，不需要"拿到方法调用现场"。包装登记表 `List` 这一条更轻、更稳、零依赖，而且不碰字节码——被误报为恶意注入的概率也低得多。
- **为什么"生成"按钮里某些类型选不到？** 三种原因，窗口"类型"页会照实写是哪一种：① 该版本那个类的构造器**不是单个 `(World)`**（0227 的 `(World, double ×6)` / `(World, float ×3)` 那批——说明里连真实签名一起给出）；② 那个类在这个 jar 里**连 `<init>` 都没有**（0227/0313 与 0413/0414 各 7 只、0316–0330 只剩 Giant 一只，`<init>` 被 ProGuard 整条删了，反射无门）；③ 它有 `(World)` 空构造、但渲染要用的字段没人填（0223 的掉落物 `EntityItem` 就是实测案例）——放出来是**把游戏主线程打成空指针崩溃**，不是让它显示不出来。选不到的类型仍会出现在"字段编辑"里（挑一个能生成的同类实体再改字段），需要这些实体时走游戏自己的逻辑或注册表通路。
- **为什么 0227 的类型清单比 0223 少，还只剩一个看不懂的类（`net.minecraft.a.c.a.e`）？** 那个类是**怪物基类**（`EntityMob`）而不是玩家，而且"只剩它一个"是那个 jar 的客观事实：`javap` 显示有 `(World)` 构造器的具体实体类一共只有 5 个（2 个玩家祖先 + 2 个基类 + `a.c.a.e`），`a.c.a.e` 下面那 7 只怪（`a.c.a.a/b/c/d/f`、`a.c.b.b/c`）**一个构造器都没有**——`<init>` 被 ProGuard 整条删了，只剩 `e()/b()/f()` 这些方法（那几年 mob 被移除了，没人 new 它们）。工具现在会把这两类"扫得到但生不成"的情况连类全名和真实签名一起写在"类型"页与日志里，不再让人以为扫描漏了。
- **这种事为什么不能用 Attach API 注入解决，非要 mod？** 因为要"生成一个实体"就必须执行那个类的 `<init>`，而 0227 的 jar 里那段字节码**不存在**——注入只能调用进程里已有的代码，反射变不出一个构造器。三条路各自撞的墙：① `getDeclaredConstructor(World)` → `NoSuchMethodException`，没东西可调；② `Unsafe.allocateInstance`（Objenesis 同理）能绕过 `<init>` 造出一个壳，但所有字段都是零值：`Entity.id` 没分配（自增写在 `<init>` 里）、`pos` 全 0、碰撞盒是 null、纹理串没设、`world` 引用没填——下一帧 `RenderManager`/tick 循环解引用就是上轮那种把游戏主线程打死的 NPE，而要把这些补全，等于**自己给每只怪补一个 `<init>`**（实测这些死壳类自带字段极少——Giant 一个都没有、羊只有一个 boolean，所以真正的墙不是工作量，而是"只能在类定义之前动手"这件事本身）；③ 字节码插桩在 `agentmain`（运行期附加）这条路下也不行——`ClassFileTransformer` 在类**已经定义之后**只能改方法体，不能增删方法，补不出 `<init>`；要补只能在类定义之前动手（`-javaagent`/premain 的加载期插桩，或直接改 jar），那就不是"附加到已在跑的游戏"这个工具形态了。那条路本身是**可行且已经取证的**：合成一个 `<init>` 只要四行字节码，渲染侧还查了——`RenderManager` 在 jar 里本来就把这批类全注册好了（连 Giant 都带着 6.0f 缩放的渲染器），补完构造器就能被渲染。细节见 [docs/premain-ctor-patch.md](docs/premain-ctor-patch.md)。所以结论是：**Attach 能做到的是调用已有代码（0223/0413 那批有构造器的版本），做不到的是无中生有**。0227 这边我们只把事实讲清楚，不假装能生成。
- **"死壳期"是什么意思？** 指 Indev 0227 与 Infdev 0313 这段：实体系统只剩一张按空间分格的 `EntityMap`，World 上没有任何"接受一个实体"的方法，也没有 `IWorldAccess` 部件表。所以既不能请求生成、注入那一刻也借不到游戏线程（格子表还空着，代理没地方装），只能直写格子并承担竞态风险；真机 0227 实测世界里有实体之后补挂是能成功的。工具不会假装这跟 0413 一样好用。
- **0223 和 0413 的"移除"有什么不同？** 0223 的摘除口是**立即**从登记表摘掉（`releaseEntitySkin`）；0316/0413 只有**置死口**，实体要等每 tick 泵 `isDead → loadedEntityList.remove(i--)` 才真的消失。工具两种语义都实现了，界面上分别是"移除选中"和"切换死亡位"。
- **`run.bat probe` 有什么用？** 不用开游戏就能验证定界逻辑认不认识某个 jar，输出与注入期用的判据完全同源。想给自己手头其他 2010 上半年的版本做兼容性预检，就用它。
- **双击 `run.bat` / `compile.bat` 完全没反应或像卡死？** 检查 `.bat` 的行尾是否为 **CRLF**。Windows 的 `cmd` 遇到只有 LF 的批处理会原地打转（本项目实测：CPU 一直涨、一行输出都没有）。仓库里的 `.gitattributes` 已经强制 `*.bat eol=crlf`，自己另存文件时别丢掉这个换行。
- **杀毒软件报毒？** Attach 其他进程属于注入行为，易被误报。项目全开源，可自查源码或自行编译。

---

## 🙏 参考与致谢 (Credits)

- 本项目的可行性论证与逐版锚点核对来自前置分析文档
  **[minecraft-attach-api-entity-manipulation.md](file:///e:/4k/github-projects/minecraft-verification-analysis/minecraft-attach-api-entity-manipulation.md)**（用 JDK Attach API 注入 Indev/Infdev 运行时操控实体的实操手册），其中的操作矩阵已被本项目的离线探针逐格复现。
- 代码骨架、构建/运行脚本与 README 格式沿用姊妹项目 **[Position-Hack](https://github.com/CreatorCSIE/Position-Hack)**（旧版 Minecraft 单机坐标修改器）；两者的共同点是外置式 Attach 注入 + 启发式跨版本定位。
- 思路参考了 **[RetroWrapper](https://github.com/NeRdTheNed/RetroWrapper)** / RetroTweaker 向旧版 Minecraft 注入功能的做法，本项目将其外置化（无需安装、不改游戏文件）。
- 感谢所有旧版 Minecraft 研究与逆向社区的工具与资料。
