# 死壳实体类与「补构造器」的可行时机（0227 / 0313 / 0316–0330 / 0413–0414）

> 结论性笔记。起因：Entity-Hack 在这些版本上扫得到实体类、却生成不出来 —— 因为 jar 里那些类**根本没有构造器**。
> 本文回答两件事：① 为什么运行中注入（Attach / agentmain）永远补不了；② 为什么随 JVM 刚拉起来（premain / 离线改 jar）**可以**补，以及补完还差什么。
> 所有类名、条数、字节码片段都是本机 `%APPDATA%\.betacraft\versions\*.jar` 上 `javap` 实测的，复现命令见文末。
> 可读源 `src-minecraft-*`（RetroMCP 反编译产物）会补出真实 jar 里不存在的构造器，**关于 `<init>` 的任何结论只能下在 javap 上**。

---

## 1. 一句话结论

| 时机 | 能不能新增 `<init>` | 原因 |
| --- | --- | --- |
| `Premain-Class`（`-javaagent`，随 JVM 一起拉起） | **能** | transformer 拿到的是还没 `defineClass` 的字节流，随便增删方法 |
| 离线 ASM/Javassist 直接改 jar 里的 `.class` | **能** | 同上，等价于"类定义之前" |
| `Agent-Class`（Attach API，游戏已在跑） | **不能** | 类已定义；JVMTI Redefine/Retransform 禁止改变类的形状（增删方法/字段、改继承） |

所以"只能在类定义之前动手"这句话是对的，而 **premain 恰好是唯一来得及的时机** —— 原因见 §3：这批类的定义时刻远早于任何注入动作。

---

## 2. 版本实况（javap 实测）

实体基类在本机这批 jar 里恒为 `net.minecraft.a.c.b`（`a.c.b`），但 **World 的混淆名每版都在漂**（`a.a.e` / `a.a.f` / `a.a.g`），这就是不能硬编码类名的直接证据。

| jar | World 混淆名 | 带 `(World)` 的具体实体类 | **零构造器死壳类** | 死壳清单 |
| --- | --- | --- | --- | --- |
| `inf-20100227` | `net.minecraft.a.a.e` | 5 | **7** | `a.c.a.a` `a.c.a.b` `a.c.a.c` `a.c.a.d` `a.c.a.f` `a.c.b.b` `a.c.b.c` |
| `inf-20100313` | `net.minecraft.a.a.f` | 5 | **7** | 同上（类名一致） |
| `inf-20100316` | `net.minecraft.a.a.e` | 11 | **1** | `a.c.a.a` |
| `inf-20100321` | `net.minecraft.a.a.f` | 11 | **1** | `a.c.a.a` |
| `inf-20100325` | `net.minecraft.a.a.g` | 11 | **1** | `a.c.a.a` |
| `inf-20100327` | `net.minecraft.a.a.f` | 11 | **1** | `a.c.a.a` |
| `inf-20100330` | `net.minecraft.a.a.f` | 11 | **1** | `a.c.a.a` |
| `inf-20100413` | `net.minecraft.a.a.g` | 5 | **7** | `a.c.a.a` `a.c.a.b` `a.c.a.c` `a.c.a.d` `a.c.a.f` `a.c.b.b` `a.c.b.c` |
| `inf-20100414` | `net.minecraft.a.a.g` | 5 | **7** | 同上 |

"带 `(World)` 的具体实体类"这一列与本仓库离线探针（`run.bat probe all`）的计数逐版一致（0227/0313/0413/0414 = 5，0316–0330 = 11），两条独立取证互相咬合。

死壳类的结构（0227 为例）：

```
a.c.b              Entity（abstract，(World)）
├── a.c.e          LivingEntity        (World)                      ← 可直连
│   ├── a.c.e.a    EntityPlayer        (World)                      ← 可直连
│   │   └── client.g.a  EntityPlayerSP (client.d, World, client.k)
│   └── a.c.c      Mob                 (World)                      ← 可直连
│       ├── a.c.a.e  Monster           (World)                      ← 可直连（基类）
│       │   ├── a.c.a.a  ← 零构造器
│       │   ├── a.c.a.b  ← 零构造器
│       │   ├── a.c.a.c  ← 零构造器
│       │   ├── a.c.a.d  ← 零构造器（自带来 4 个 private int）
│       │   └── a.c.a.f  ← 零构造器
│       └── a.c.b.a  ← abstract，(World)
│           ├── a.c.b.b  ← 零构造器（自带 1 个 public boolean）
│           └── a.c.b.c  ← 零构造器
└── a.c.a          (World) / (World,int,int,int,int)                ← 可直连
```

为什么会被删：ProGuard 把**没有任何代码 `new` 过**的类的 `<init>` 整条删掉了。这些类仍然活在 jar 里、仍然被渲染器按类名注册（见 §4），只是没人调用过构造器 —— 于是留下"扫得到、生不成"的死壳。0316–0330 只剩一个死壳，恰好说明那几版已经把绝大多数怪接进了刷怪逻辑，**唯一的例外是 Giant**（§5）。

---

## 3. 为什么 agentmain 一定来不及

1. **反射这条路没有可调的对象**：`getDeclaredConstructors()` 返回长度 0，`clazz.newInstance()` 只会抛 `NoSuchMethodException`。
2. **`Unsafe.allocateInstance` 只造零值壳**：`Entity.id` 没分配、坐标全 0、碰撞盒 null、world 引用空 —— 主线程下一 tick 就 NPE。缺的东西全在原构造器里。
3. **类已定义后 JVMTI 不许增删方法**：`ClassFileTransformer` 在 `Redefine/Retransform` 模式下只能改方法体，不能新增 `<init>`。这是 JVM 规范层面的限制，不是工具不够聪明。
4. **时机上还有一刀**：这批类在进主菜单时就被渲染注册表 `ldc` 引用（§4），也就是说它们的 `defineClass` 发生在任何 Attach 动作之前。即便第 3 条不存在，也抢不到。

---

## 4. 渲染侧完全不用补（决定性的好消息）

`net.minecraft.client.a.a.i` 就是 **RenderManager**：一个 `Map<实体类, 渲染器>`，在它的私有构造器里逐个 `map.put`。0227 实测片段：

```
ldc  class net/minecraft/a/c/a/b      →  new client/a/a/m            <init>()V
ldc  class net/minecraft/a/c/b/b      →  new client/a/a/k  (client/b/a, client/b/d, 0.7f)
ldc  class net/minecraft/a/c/a/a      →  new client/a/a/l  (client/b/g, 0.5f, 6.0f)   ← 模型 6 倍放大
ldc  class net/minecraft/a/c/a/d      →  new client/a/a/c            <init>()V
```

0316 的注册表结构一致（同一批 `ldc class net/minecraft/a/c/...`）。查找逻辑是：

```java
map.get(entity.getClass())   // 未命中则沿 Class.getSuperclass() 一路往上找
```

**所以只要实体对象存在，渲染是现成的**，不需要任何额外注册。纹理也不缺：`net.minecraft.client.a.i` 是纹理缓存（`Map<String,Integer>` GL 纹理 id + `getResourceAsStream`），键来自实体类自己那几个还活着的方法（0227 的 `a.c.b.b` 里 `f()/g()/h()` 全部 `ldc "mob.sheep"`），jar 内也确实存在 `mob/sheep.png`、`mob/zombie.png` 等资源。注意 jar 里**没有** `/mob/giant.png` —— Giant 复用僵尸模型（`client.b.g`）加 6.0f 缩放，这与历史事实一致。

顺带一句：`ldc class X` 会在首次执行该指令时解析并加载 `X` —— 这正是 §3 第 4 点的机制来源。

---

## 5. Giant 专项：0316–0330 唯一缺 `<init>` 的实体

- 0316 / 0321 / 0325 / 0327 / 0330 全版的死壳类**只有一个**：`net.minecraft.a.c.a.a`（`Monster` 的子类）。
- 身份确认（字节码级）：`inf-20100327` 与 `inf-20100330` 里该类有

  ```
  public final java.lang.String a();
     0: ldc  #2   // String Giant
  ```

- 0227 / 0313 / 0316 / 0413 / 0414 里同位置的类没有这个字符串方法，但它被注册到 `client.a.a.l`（僵尸模型 `client.b.g` + `0.5f` + **`6.0f` 缩放**）—— 同一个类。
- 该类**不声明任何字段**（0227/0316/0413 三版实测皆然）。

含义：Giant 在整条 Infdev 线上是"文件里有、代码从不 new"的实体 —— 自然刷怪表不含它，所以构造器从头到尾被 ProGuard 删掉。**给 0316–0330 补一个构造器，收益就是解锁 Giant 这一只；给 0227/0313/0413/0414 补，收益是解锁 7 只（4 只怪 + 2 只被动怪 + Giant）。**

---

## 6. 要补的字节码有多小

死壳类的父链都**真的有** `(World)`（`a.c.a.e` / `a.c.c` / `a.c.e`，0413 起为 `a.c.a.e` / `a.c.c` / `a.c.f`），而 id 分配、world 引用、坐标与随机数初始化全在 `Entity.<init>(World)` 里做完。于是合成体只需要四行：

```
ALOAD 0
ALOAD 1
INVOKESPECIAL <superName>.<init>(Lnet/minecraft/a/a/e;)V
RETURN
```

ASM 形态（premain 的 `transform` 内）：

```java
MethodVisitor mv = cn.visitMethod(Opcodes.ACC_PUBLIC, "<init>", desc, null, null);
mv.visitCode();
mv.visitVarInsn(ALOAD, 0);
mv.visitVarInsn(ALOAD, 1);
mv.visitMethodInsn(INVOKESPECIAL, cn.superName, "<init>", desc, false);
mv.visitInsn(RETURN);
mv.visitMaxs(2, 2);   // 直线代码、无分支 → 不需要 StackMapTable，老 class 版本也过得了 verifier
mv.visitEnd();
```

`desc` **不要写死**：从父类的 `<init>` 里挑"恰好一个引用类型参数"的那条，参数内部名就是该版本的 World（`a.a.e` / `a.a.f` / `a.a.g` 逐版不同，见 §2 表）。

字段初值的现实检查（实测）：

| 类 | 自带字段 | 零值是否安全 |
| --- | --- | --- |
| `a.c.a.a`（Giant） | 无 | 安全 |
| `a.c.b.b`（羊，`f()/g()/h()` 返回 `mob.sheep`） | `public boolean a` 1 个 | 零值即 false，按"未剪毛"语义大概率正好；接入前对着反编译原值核一眼 |
| `a.c.a.d` | `private int O/P/Q/R` 4 个 | AI 计时器，0 无害 |
| 其余死壳 | 无 | 安全 |

---

## 7. 目标类怎么认（比类名表稳）

按结构特征筛，别按名字筛：

1. 继承链能追溯到实体根（那个同时持有 AABB 双字段与数值三元组的抽象类，本机各版为 `net.minecraft.a.c.b`）；
2. **非抽象、非接口**；
3. `getDeclaredConstructors().length == 0`（javap 里表现为整个类体没有一条 `<init>`）；
4. 其父类存在一条"单个引用类型参数"的 `<init>`。

四条同时成立就补。这样写出来的 transformer 对 0227（补 7 个）、0316–0330（补 1 个 Giant）、0413/0414（补 7 个）一次性通吃，且天然幂等（补完第 3 条就不成立了）。

---

## 8. 补完仍然要自己解决的事

1. **生成口**：`World` 没有对外的 addEntity（0227/0313 连"收一个 Entity 的 void 方法"都找不到，只能直写 `EntityMap` 格子）。补构造器只让 `newInstance` 成立，塞进哪个世界仍是另一件事 —— 这一条本仓库的 `EntityOps` 已经走通了。
2. **自然刷怪与刷怪蛋**：补 `<init>` 不会把怪写进刷怪表。夜里刷不出来是预期的。
3. **名称注册表**：`EntityList.addMapping(Class, String)` 从 **0413 才出现**（`net.minecraft.a.c.e`，static Map 字段 `a,b`）；0227/0313/0316–0330 都没有它。所以"按名字找类"这条路只在 0413+ 可用，早期版本只能按结构认。
4. **崩溃风险**：`desc` 写错一个字符就是 `VerifyError`/`NoClassDefFoundError`，且发生在类加载期、没有挽回机会。transform 里必须整段 try/catch，失败返回 `null`（原始字节）。
5. **交付形态**：premain 要求启动命令带 `-javaagent`，BetaCraft 这类启动器不一定让你改 JVM 参数；离线用同一套 ASM 代码重写 jar 里的 `.class` 更省事，代价是改的是玩家本地版本文件（需要让用户备份）。

---

## 9. 取证命令（本机可复现）

```powershell
# 全 jar 的类形状 + 构造器计数（每版一次调用，产物 build/scan-<ver>.txt）
$names = jar tf $jar | ? { $_ -like "*.class" -and $_.StartsWith("net/minecraft/") } | % { (($_ -replace "\.class$","") -replace "/",".") }
javap -p -classpath $jar $names

# 解析：按 extends 建继承树，取 Entity 后代，统计构造器条数（build/scan-parse.ps1）

# 渲染注册表与纹理键
javap -p -c -classpath $jar net.minecraft.client.a.a.i   # RenderManager：map.put(实体类, 渲染器)
javap -p -c -classpath $jar net.minecraft.client.a.i     # 纹理缓存：Map<String,Integer> + getResourceAsStream
javap -p -c -classpath $jar net.minecraft.a.c.b.b        # 死壳羊：f()/g()/h() -> ldc "mob.sheep"
javap -p -c -classpath $jar net.minecraft.a.c.a.a        # Giant：0327/0330 可见 ldc "Giant"

# 与在线注入互相咬合的离线探针
run.bat probe all   # 产物 probe-utf.txt（GBK 原文，需转 UTF-8 再看）
```
