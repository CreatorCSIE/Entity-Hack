package mock;

import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JTable;
import javax.swing.SwingUtilities;

import org.github.creatorcsie.entityhack.agent.EntityOps;
import org.github.creatorcsie.entityhack.agent.EntityWindow;
import org.github.creatorcsie.entityhack.agent.GameBinder;
import org.github.creatorcsie.entityhack.agent.GameFinder;
import org.github.creatorcsie.entityhack.agent.GameShape;
import org.github.creatorcsie.entityhack.agent.Handles;
import org.github.creatorcsie.entityhack.agent.TickPump;
import org.github.creatorcsie.entityhack.agent.WorldWatch;

/**
 * 端到端验证：用三套"仿造的老 Minecraft 世界"跑完注入后的完整链路——
 * 找根 -> 形状定界 -> 借游戏线程 -> 探针语义分类 -> 实体增/删/改 -> <b>换关卡</b> -> 卸载还原。
 *
 * <p>三套 mock 覆盖的是实测出来的三种形态（不是随便挑的年份）：
 * <ul>
 *   <li>{@code v0413}：Infdev 0413/0415 —— loadedEntityList + 每 tick 泵 + 静态 setEntityDead + EntityList 注册表，
 *       并且mob 叶类没有 {@code (World)} 构造器（"构造器剥除案"）。</li>
 *   <li>{@code v0223}：Indev 0223 —— EntityMap 网格 + 主列表 + float 坐标 + 立即摘除口，无类型注册表。</li>
 *   <li>{@code v0227}：Indev 0227（以及 0313 的更差形态）—— <b>死壳期</b>：只有格子，没有生成/摘除 API、
 *       没有每 tick 泵、没有 addWorldAccess，因此借不到游戏线程，只能就地直写格子。</li>
 * </ul>
 *
 * 运行：{@code compile.bat test} 之后 {@code run.bat mock}。退出码非 0 表示有断言失败。
 */
public final class MockMain {

    private static int total;
    private static final List<String> FAILS = new ArrayList<String>();
    private static final List<String> LOGS = new ArrayList<String>();

    private static final GameBinder.Log SINK = new GameBinder.Log() {
        public void log(String line) {
            String[] parts = String.valueOf(line).split("\n");
            for (int i = 0; i < parts.length; i++) {
                synchronized (LOGS) {
                    LOGS.add(parts[i]);
                }
                System.out.println("        | " + parts[i]);
            }
        }
    };

    public static void main(String[] args) {
        if (args.length > 0 && "gui".equalsIgnoreCase(args[0])) {
            try {
                guiSmoke();
            } catch (Throwable t) {
                fail("GUI 冒烟崩溃: " + t);
                t.printStackTrace(System.out);
            }
            System.out.println();
            System.out.println("断言 " + total + " 项，失败 " + FAILS.size() + " 项");
            for (int i = 0; i < FAILS.size(); i++) {
                System.out.println("  - " + FAILS.get(i));
            }
            System.exit(FAILS.isEmpty() ? 0 : 1);
            return;
        }
        System.out.println("=== Entity-Hack mock 端到端验证 ===");
        System.out.println("java=" + System.getProperty("java.version") + "  os=" + System.getProperty("os.name"));
        Variant[] variants = new Variant[] { v0413(), v0223(), v0227() };
        for (int i = 0; i < variants.length; i++) {
            try {
                run(variants[i]);
            } catch (Throwable t) {
                fail(variants[i].id + " 整套用例崩溃: " + t);
                t.printStackTrace(System.out);
            }
        }
        try {
            renderEngineTrap();
        } catch (Throwable t) {
            fail("0223 渲染引擎陷阱用例崩溃: " + t);
            t.printStackTrace(System.out);
        }
        try {
            spawnTrap();
        } catch (Throwable t) {
            fail("0223 生成清单陷阱用例崩溃: " + t);
            t.printStackTrace(System.out);
        }
        System.out.println();
        System.out.println("断言 " + total + " 项，失败 " + FAILS.size() + " 项");
        for (int i = 0; i < FAILS.size(); i++) {
            System.out.println("  - " + FAILS.get(i));
        }
        System.exit(FAILS.isEmpty() ? 0 : 1);
    }

    // ------------------------------------------------------------------ 形态定义

    private static final class Variant {
        String id;
        String title;
        String pkg;
        String classDir;
        String worldClass;
        String entityClass;
        String accessClass;
        String pos;
        String prev;
        String motion;
        boolean expectPump;
        boolean expectEntityList;
        /** 这个年代的实体是按 {@code List[]} 格子索引的（Indev/Infdev 的 EntityMap）；0413 是平表，没有格子。 */
        boolean expectGrid;
        String spawn;
        String hardRemove;
        String deadMark;
        String deadFlag;
        String probeClass;
        /** 有 {@code (World)} 构造器的具体子类总数（未过滤）。 */
        int rawSpawnableCount;
        /** 过滤后"塞进世界不会把渲染线程打 NPE"的类型数。 */
        int spawnableCount;
        boolean playerInRegistry = true;
        String field;
        String fieldValue;
        /**
         * "具体怪物基类"（真机 0227 的 {@code net.minecraft.a.c.a.e} = EntityMob：
         * {@code public class}、有 {@code (World)} 构造器）。非 null 时这一套世界要多跑两组回归：
         * {@link #livePoolCheck}（类池缺叶类时基类会被当成唯一叶子）与
         * {@link #unconstructableCheck}（生不成的类要连签名一起说清楚）。
         */
        String mobBaseClass;
        /** {@link #mobBaseClass} 的具体子类；回归里会先从类池里摘掉它，模拟"快照里缺叶类"。 */
        String lazyLeafClass;
        /** 有构造器、但签名不是单个 {@code (World)} 的实体类（清单说明里要连签名一起报出来）。 */
        String altCtorClass;
    }

    /** Infdev 0413 / 0415：能力最完整的一档。 */
    private static Variant v0413() {
        Variant v = new Variant();
        v.id = "0413";
        v.title = "Infdev 20100413 / 0415：loadedEntityList + 每 tick 泵 + 静态置死 + EntityList";
        v.pkg = "mock.v0413.net.minecraft.";
        v.classDir = "build-test/mock/v0413";
        v.worldClass = "a.a.g";
        v.entityClass = "a.c.b";
        v.accessClass = "a.a.a";
        v.pos = "h,i,j";
        v.prev = "e,f,g";
        v.motion = "k,l,m";
        v.expectPump = true;
        v.expectEntityList = true;
        v.spawn = "a";
        v.hardRemove = null;
        v.deadMark = "b";
        v.deadFlag = "u";
        v.probeClass = "a.e.c";
        v.rawSpawnableCount = 3;
        v.spawnableCount = 1;
        v.field = "J";
        v.fieldValue = "4242";
        return v;
    }

    /** Indev 0223：EntityMap 网格 + float 坐标 + 立即摘除口，没有类型注册表。 */
    private static Variant v0223() {
        Variant v = new Variant();
        v.id = "0223";
        v.title = "Indev 20100223：EntityMap 网格 + float 三元组 + 立即摘除口（无注册表）";
        v.pkg = "mock.v0223.net.minecraft.";
        v.classDir = "build-test/mock/v0223";
        v.worldClass = "a.a.g";
        v.entityClass = "a.c.b";
        v.accessClass = "a.a.d";
        v.pos = "h,i,j";
        v.prev = "e,f,g";
        v.motion = "k,l,m";
        v.expectPump = true;
        v.expectEntityList = false;
        v.expectGrid = true;
        v.spawn = "a";
        v.hardRemove = "b";
        v.deadMark = null;
        v.deadFlag = null;
        v.probeClass = "a.e.b";
        v.rawSpawnableCount = 3;
        v.spawnableCount = 1;
        v.field = "N";
        v.fieldValue = "renamed";
        return v;
    }

    /** Indev 0227（0313 同形）：死壳期——没有 API、没有泵、没有部件表。 */
    private static Variant v0227() {
        Variant v = new Variant();
        v.id = "0227";
        v.title = "Indev 20100227 / 0313 死壳期：只有 EntityMap 格子，直写直删 + 就地执行";
        v.pkg = "mock.v0227.net.minecraft.";
        v.classDir = "build-test/mock/v0227";
        v.worldClass = "a.a.e";
        v.entityClass = "a.c.b";
        v.accessClass = null;
        v.pos = "h,i,j";
        v.prev = "e,f,g";
        v.motion = "k,l,m";
        v.expectPump = false;
        v.expectEntityList = false;
        v.expectGrid = true;
        v.spawn = null;
        v.hardRemove = null;
        v.deadMark = null;
        v.deadFlag = null;
        v.probeClass = "a.e.b";
        v.rawSpawnableCount = 3;
        v.spawnableCount = 1;
        v.playerInRegistry = false;
        v.field = "bg";
        v.fieldValue = "/mob/cream.png";
        v.mobBaseClass = "a.c.c";
        v.lazyLeafClass = "a.e.b";
        v.altCtorClass = "a.e.c";
        return v;
    }

    // ------------------------------------------------------------------ 主流程

    private static void run(final Variant v) throws Exception {
        System.out.println();
        System.out.println("=== " + v.id + " — " + v.title);
        String gameName = v.pkg + "client.d";
        Class<?> gameClass = Class.forName(gameName);
        Object game = gameClass.getDeclaredConstructor(new Class[0]).newInstance(new Object[0]);
        gameClass.getMethod("a", new Class[0]).invoke(game, new Object[0]);
        Thread gameThread = new Thread((Runnable) game, "mock-game-" + v.id);
        gameThread.setDaemon(true);
        gameThread.start();

        final List<Class<?>> classes = scanClasses(new File(v.classDir));
        ok(v.id + "：扫到 mock 游戏类（" + classes.size() + " 个）", classes.size() >= 8);

        // 1) 捞根：老 MC 没有 theMinecraft 单例，只能从 Thread.target 捞
        boolean rooted = false;
        for (Object o : GameFinder.collectRootCandidates()) {
            if (o == game) {
                rooted = true;
            }
        }
        eq(v.id + "：GameFinder 从线程 target 捞到游戏实例", true, rooted);

        // 2) 形状定界
        final Handles h = GameBinder.bind(game, classes, SINK);
        eq(v.id + "：World 定界", v.pkg + v.worldClass, h.worldClass.getName());
        eq(v.id + "：Entity 基类定界", v.pkg + v.entityClass, h.entityBase.getName());
        eq(v.id + "：IWorldAccess 定界", v.accessClass == null ? null : v.pkg + v.accessClass,
                h.accessInterface == null ? null : h.accessInterface.getName());
        eq(v.id + "：实体上的 World 字段", "d", h.worldField.getName());
        eq(v.id + "：World 上的玩家字段", true, h.playerField != null);
        eq(v.id + "：AABB 字段", "r", h.aabbField == null ? null : h.aabbField.getName());
        eq(v.id + "：pos 三元组", v.pos, names(h.posTriple));
        eq(v.id + "：prevPos 三元组", v.prev, names(h.prevTriple));
        eq(v.id + "：motion 三元组", v.motion, names(h.motionTriple));
        eq(v.id + "：EntityList 注册表", v.expectEntityList, h.entityListClass != null);
        // 形状判据本身（离线 JarProbe 用的就是这一套，这里用实例类再验一遍）
        ok(v.id + "：实体基类通过形状判据", GameShape.isEntityBase(h.entityBase));
        ok(v.id + "：World 通过形状判据", GameShape.isWorldLike(h.worldClass, h.entityBase));
        ok(v.id + "：AABB 通过形状判据", GameShape.isAabbLike(h.aabbField.getType()));
        Class<?> living = findClass(classes, v.pkg + "a.c.a");
        ok(v.id + "：EntityLiving 不会被误判成实体基类", living != null && !GameShape.isEntityBase(living));
        ok(v.id + "：玩家实例被认作实体", GameShape.isEntityLike(GameBinder.currentPlayer(h).getClass()));

        // 3) 借游戏线程：主通道 = 包装登记表 List
        final TickPump pump = new TickPump(h);
        boolean live = pump.install(SINK);
        eq(v.id + "：拿到游戏线程", v.expectPump, live);
        eq(v.id + "：pumpRunning 与 handles 同步", v.expectPump, h.pumpRunning);
        if (v.expectPump) {
            ok(v.id + "：包装了登记表（" + pump.wrappedLists() + " 张）", pump.wrappedLists() >= 1);
            eq(v.id + "：主通道是 list 而不是 access", "list", pump.channel());
            ok(v.id + "：游戏线程回调 > 0", pump.callbackCount() > 0);
        } else {
            eq(v.id + "：死壳期没有可包装的登记表", 0, pump.wrappedLists());
        }

        // 4) 探针语义分类（必须排在游戏线程上跑）
        final boolean[] verified = new boolean[1];
        synchronized (LOGS) {
            LOGS.clear();
        }
        pump.submit(new Runnable() {
            public void run() {
                try {
                    GameBinder.verifyCapabilities(h, classes, SINK);
                } finally {
                    verified[0] = true;
                }
            }
        });
        ok(v.id + "：探针实验完成", await(verified, 6000));
        eq(v.id + "：生成口", v.spawn, name(h.spawnMethod));
        eq(v.id + "：立即摘除口", v.hardRemove, name(h.hardRemoveMethod));
        eq(v.id + "：置死口", v.deadMark, name(h.deadMarkMethod));
        eq(v.id + "：死亡标志字段", v.deadFlag, h.deadFlag == null ? null : h.deadFlag.getName());
        if (!v.expectPump) {
            ok(v.id + "：日志标注了死壳期", logContains("死壳期"));
        }

        // 5) 增 / 删 / 改
        final EntityOps ops = new EntityOps(h, pump, classes);
        final Class<?> type = Class.forName(v.pkg + v.probeClass);
        eq(v.id + "：有 (World) 构造器的具体子类总数", v.rawSpawnableCount,
                GameBinder.concreteEntitySubclasses(h, classes).size());
        eq(v.id + "：可生成类型清单（过滤掉渲染器会当场 NPE 的类型）", v.spawnableCount,
                ops.spawnableTypes().size());
        ok(v.id + "：过滤原因写进了 lastFilterNote（界面\"类型\"页要照实说）",
                v.rawSpawnableCount > v.spawnableCount ? ops.lastFilterNote != null : true);
        if (v.mobBaseClass != null) {
            unconstructableCheck(v, ops.lastFilterNote);
            livePoolCheck(v, h, pump, classes);
        }

        List<Object> spawned = new ArrayList<Object>();
        ops.spawn(type, 100, 70, 100, 3);
        for (int i = 0; i < 3; i++) {
            final int want = i + 1;
            ok(v.id + "：添加第 " + want + " 个实体", await(new Checker() {
                public boolean done() {
                    return count(h, ops, type) >= want;
                }
            }, 6000));
        }
        spawned.addAll(ofType(h.allEntities(), type));
        eq(v.id + "：登记表里有 3 个新实体", 3, spawned.size());

        final Object moved = spawned.get(0);
        final Object removed = spawned.get(1);
        ops.moveTo(moved, 110, 70, 110);
        ok(v.id + "：修改坐标生效", await(new Checker() {
            public boolean done() {
                double[] p = GameBinder.readTriple(h.posTriple, moved);
                return near(p[0], 110) && near(p[1], 70) && near(p[2], 110);
            }
        }, 6000));
        ok(v.id + "：传送后实体仍在登记表里", h.contains(moved));
        // 真机 0223 的"改完反弹"：坐标写完那一刻是对的，下一 tick 游戏从旧碰撞盒把读数覆盖回去。
        // 所以断言必须连着跨过好几个 tick 都还守着，一次性读到不算数。
        ok(v.id + "：改坐标不会反弹（连着 15 个 tick 还守着新位置）", stays(new Checker() {
            public boolean done() {
                double[] p = GameBinder.readTriple(h.posTriple, moved);
                return near(p[0], 110) && near(p[1], 70) && near(p[2], 110);
            }
        }, 800L));
        final double[] box = boxCenter(h, moved);
        ok(v.id + "：碰撞盒跟着坐标一起搬了（游戏就是按盒子反推坐标的）: 盒中心="
                + fmt3(box), box != null && near(box[0], 110) && near(box[1], 70) && near(box[2], 110));
        if (v.expectGrid) {
            ok(v.id + "：EntityMap 的格子也搬对了（渲染只遍历格子）", inCellOf(h, moved, 110, 70, 110));
        }
        // 搬回去：moveTo 现在会连坐标、碰撞盒、格子一起搬，不再是"只改读数留一条过期索引"。
        // 注意 moveTo 是排队到游戏线程的，所以这里也得等，不能读完就算。
        ops.moveTo(moved, 100, 70, 100);
        if (v.expectGrid) {
            ok(v.id + "：搬回原坐标，格子也跟着搬回来了", await(new Checker() {
                public boolean done() {
                    return inCellOf(h, moved, 100, 70, 100);
                }
            }, 6000));
        }

        final String want = v.fieldValue;
        ops.setField(moved, v.field, want);
        ok(v.id + "：通用字段修改生效（" + v.field + "=" + want + "）", await(new Checker() {
            public boolean done() {
                return want.equals(String.valueOf(readField(moved, v.field)));
            }
        }, 6000));
        ok(v.id + "：字段清单能读出该字段", hasFieldNamed(ops, moved, v.field));

        if (v.expectEntityList) {
            eq(v.id + "：idOf 走 EntityList", "Creeper", ops.idOf(moved));
            registryRoundtrip(ops, h, classes);
        }

        ops.remove(removed);
        ok(v.id + "：移除单个实体", await(new Checker() {
            public boolean done() {
                return !h.contains(removed);
            }
        }, 6000));
        ok(v.id + "：被移除的实体已从所有登记表消失", !h.contains(removed));
        ok(v.id + "：玩家没有被误删", !v.playerInRegistry || h.contains(GameBinder.currentPlayer(h)));

        ops.removeMatching(null);
        final int expectLeft = v.playerInRegistry ? 1 : 0;
        ok(v.id + "：批量移除到只剩玩家", await(new Checker() {
            public boolean done() {
                return h.allEntities().size() == expectLeft;
            }
        }, 6000));
        eq(v.id + "：最终登记表规模", expectLeft, h.allEntities().size());
        ok(v.id + "：探针实体没有残留", count(h, ops, type) == 0);

        // 5b) 换关卡：游戏 new 一个新 World 顶掉字段，旧句柄必须跟着搬过去
        //（真机表现是"换了世界，列表不再更新"，而且排队的操作永远不执行）
        final Object oldWorld = h.world;
        final Object oldPlayer = GameBinder.currentPlayer(h);
        WorldWatch watch = new WorldWatch(h, pump, SINK);
        ok(v.id + "：这个世界监视得了换关卡（游戏实例上找得到世界字段）", watch.watching);
        ok(v.id + "：世界没换时不会误报", !watch.tick());
        gameClass.getMethod("a", new Class[0]).invoke(game, new Object[0]); // = 重新载入一个关卡
        ok(v.id + "：换关卡前，列表还停在旧世界（扫的还是旧那张表）", h.world == oldWorld
                && GameBinder.currentPlayer(h) == oldPlayer
                && (!v.playerInRegistry || h.contains(oldPlayer)));
        ok(v.id + "：WorldWatch 发现了新载入的 World", watch.tick());
        ok(v.id + "：h.world 已指向新的 World 实例", h.world != oldWorld);
        final Object newPlayer = GameBinder.currentPlayer(h);
        ok(v.id + "：在场实体已经是新世界的（旧玩家不再出现）", newPlayer != oldPlayer
                && !h.contains(oldPlayer) && (!v.playerInRegistry || h.contains(newPlayer)));
        ok(v.id + "：再检查一次不会来回抖", !watch.tick());
        ok(v.id + "：切换结论可显示给状态区", watch.lastNote != null
                && watch.lastNote.contains(v.expectPump ? "已重挂" : "就地执行"));
        ops.spawn(type, 100, 70, 100, 1);
        ok(v.id + "：新世界照样能生成实体（说明游戏线程通道重挂上了）", await(new Checker() {
            public boolean done() {
                return count(h, ops, type) >= 1;
            }
        }, 6000));
        if (v.expectPump) {
            eq(v.id + "：通道重挂后 pumpRunning 恢复", Boolean.TRUE, h.pumpRunning);
        }

        // 6) 卸载还原
        pump.uninstall();
        eq(v.id + "：卸载后 pumpRunning=false", false, h.pumpRunning);
        ok(v.id + "：登记表字段已从代理还原成原 List", noProxyLeft(h));
        gameClass.getMethod("b", new Class[0]).invoke(game, new Object[0]);
        gameThread.interrupt();
    }

    /** 类型注册表的增删（只有 0413+ 有；这里顺便验一把"构造器剥除案"里那个叶类）。 */
    private static void registryRoundtrip(EntityOps ops, Handles h, List<Class<?>> classes) {
        String id = "Skeleton";
        Class<?> skeleton = findClass(classes, "mock.v0413.net.minecraft.a.e.d");
        ok("0413：找到那个没有 (World) 构造器的叶类", skeleton != null);
        ok("0413：它确实不在可生成清单里", !containsType(ops.spawnableTypes(), skeleton));
        ops.unregisterId(id);
        ops.registerId(skeleton, id);
        ok("0413：注册表出现新 id", await(containsId(ops, id), 6000));
        try {
            Object e = skeleton.getDeclaredConstructor(h.worldClass, int.class).newInstance(h.world, 5);
            eq("0413：注册后 idOf 认得它", id, ops.idOf(e));
        } catch (Throwable t) {
            fail("0413：构造未注册的实体失败: " + t);
        }
        ops.unregisterId(id);
        ok("0413：解除注册", await(notContainsId(ops, id), 6000));
    }

    // ------------------------------------------------------------------ 真机 0223 回归

    /**
     * 真机 in-20100223 注入时踩到的坑（窗口出来了、标题写着 {@code net.minecraft.client.a.b}、
     * 游戏里 HUD 显示 20 个实体，表里却一个都扫不到）的回归用例：
     * <ol>
     *   <li>玩家不挂在任何字段上（{@code Minecraft.e} / {@code World.y} 全是 null），
     *       实体<b>只</b>存在于 {@code EntityMap} 的 {@code List[]} 网格与主列表里；</li>
     *   <li>旁边站着一个 {@code client.a.b}（渲染引擎）：它满足"世界形状"、方法数还比真正的 World 多。</li>
     * </ol>
     * 老实现靠"形状 + 方法数"选优就会把渲染引擎当成 World。现在的实现是
     * "先从登记表里拿到一个实体，再读<b>这个实体身上</b>的世界字段"，没有第二条路可选。
     */
    private static void renderEngineTrap() throws Exception {
        System.out.println();
        System.out.println("=== 0223 — 渲染引擎陷阱：实体只在登记表里、玩家字段全为 null");
        Class<?> gameClass = Class.forName("mock.v0223.net.minecraft.client.d");
        Object game = gameClass.getDeclaredConstructor(new Class[0]).newInstance(new Object[0]);
        gameClass.getMethod("a", new Class[0]).invoke(game, new Object[0]);
        Object world = field(gameClass, "d").get(game);
        Object engine = field(gameClass, "z").get(game);
        // 把"字段上的玩家"抹掉：只留下登记表里的实体（真机刚进世界时就是这个样子）
        field(gameClass, "e").set(game, null);
        field(world.getClass(), "y").set(world, null);

        List<Class<?>> classes = scanClasses(new File("build-test/mock/v0223"));
        Class<?> engineClass = engine.getClass();
        Class<?> worldClass = world.getClass();
        ok("0223 陷阱：渲染引擎确实满足世界形状判据", GameShape.isWorldShape(engineClass));
        ok("0223 陷阱：渲染引擎方法数(" + GameShape.methodCount(engineClass) + ")多于 World("
                + GameShape.methodCount(worldClass) + ")",
                GameShape.methodCount(engineClass) > GameShape.methodCount(worldClass));

        boolean entityFieldSomewhere = false;
        for (Object o : GameFinder.reachable(game, 3)) {
            for (Field f : GameShape.allFields(o.getClass())) {
                Object v = GameShape.read(f, o);
                if (v != null && v != o && GameShape.isEntityLike(v.getClass())) {
                    entityFieldSomewhere = true;
                }
            }
        }
        ok("0223 陷阱：可达对象里确实没有任何实体类型字段", !entityFieldSomewhere);
        Object entityMap = readField(world, "r");
        ok("0223 陷阱：实体此刻只存在于登记表容器里（" + ((List<?>) readField(entityMap, "e")).size()
                + " 个）", !((List<?>) readField(entityMap, "e")).isEmpty());

        Handles h = GameBinder.bind(game, classes, SINK);
        eq("0223：World 认成 a.a.g（而不是渲染引擎 " + engineClass.getSimpleName() + "）",
                "mock.v0223.net.minecraft.a.a.g", h.worldClass.getName());
        ok("0223：World 实例就是原来那个", h.world == world);
        eq("0223：Entity 基类", "mock.v0223.net.minecraft.a.c.b", h.entityBase.getName());
        eq("0223：实体身上的世界字段", "d", h.worldField.getName());
        eq("0223：游戏主循环实例", game, h.minecraft);
        eq("0223：绑定时机不对就不会误认成渲染引擎", false, h.world == engine);
        List<Object> listed = h.allEntities();
        ok("0223：窗口那张表扫得到实体（" + listed.size() + " 个）", !listed.isEmpty());
        boolean allEntities = true;
        for (int i = 0; i < listed.size(); i++) {
            allEntities = allEntities && h.entityBase.isAssignableFrom(listed.get(i).getClass());
        }
        ok("0223：表里每一项都是实体", allEntities);
    }

    private static Field field(Class<?> c, String name) throws Exception {
        Field f = c.getField(name);
        f.setAccessible(true);
        return f;
    }

    /**
     * 真机 in-20100223"添加新实体直接空指针"的回归。那个崩溃不是我们写错了什么字段，
     * 而是<b>清单里就不该有这个类型</b>：
     * <ul>
     *   <li>可读源 {@code EntityItem.java:33-35} 有个 {@code public EntityItem(World world1) { super(world1); }}
     *       ——形状判据（有 {@code (World)} 构造器的具体实体子类）当然会把它当成"能生成"；</li>
     *   <li>但渲染要用的 {@code public ItemStack item}（同文件第 13 行）只有另一个构造器才填，
     *       从这条路捏出来的实例永远是 null；</li>
     *   <li>{@code RenderManager.entityRenderMap} 里又恰好写着 {@code EntityItem -> RenderItem}
     *       （可读源 {@code RenderManager.java:55}），于是实体一进登记表，下一帧
     *       {@code RenderItem.a()} 解引用 null 就把<b>游戏主线程</b>打成 NPE——
     *       用户贴回来的栈顶 {@code net.minecraft.client.a.a.e.a} 就是这一句。</li>
     * </ul>
     * 这里用 {@code mock.v0223...a.c.f}（照 EntityItem 建模的陷阱类）把三件事钉住：
     * 形状判据确实会放行它、安全清单必须踢掉它并说清原因、放行清单里的类型试构造后渲染不许崩。
     */
    private static void spawnTrap() throws Exception {
        System.out.println();
        System.out.println("=== 0223 — 生成清单陷阱：EntityItem 会把渲染线程当场打 NPE");
        Class<?> gameClass = Class.forName("mock.v0223.net.minecraft.client.d");
        Object game = gameClass.getDeclaredConstructor(new Class[0]).newInstance(new Object[0]);
        gameClass.getMethod("a", new Class[0]).invoke(game, new Object[0]);
        List<Class<?>> classes = scanClasses(new File("build-test/mock/v0223"));
        Handles h = GameBinder.bind(game, classes, SINK);
        EntityOps ops = new EntityOps(h, null, classes);

        final Class<?> trap = Class.forName("mock.v0223.net.minecraft.a.c.f");
        final Class<?> mob = Class.forName("mock.v0223.net.minecraft.a.e.b");
        List<Class<?>> raw = GameBinder.concreteEntitySubclasses(h, classes);
        ok("0223 陷阱：光看形状确实会把 EntityItem 当成可生成类型（所以才必须过滤）", raw.contains(trap));
        ok("0223 陷阱：EntityItem 交给渲染器就是 NullPointerException（真机那条崩栈）",
                throwsOnRender(trap, h.worldClass, h.world));

        List<EntityOps.TypeInfo> safe = ops.spawnableTypes();
        ok("0223：安全清单里没有 EntityItem（" + describe(safe) + "）", !containsType(safe, trap));
        ok("0223：安全清单里留着有纹理串的活物 a.e.b", containsType(safe, mob));
        ok("0223：过滤原因写进了 lastFilterNote（界面\"类型\"页要照实说）: " + ops.lastFilterNote,
                ops.lastFilterNote != null && ops.lastFilterNote.contains("纹理串"));
        for (int i = 0; i < safe.size(); i++) {
            Class<?> c = safe.get(i).type;
            ok("0223：清单里的 " + c.getSimpleName() + " 交给渲染器不会崩",
                    !throwsOnRender(c, h.worldClass, h.world));
        }
    }

    /**
     * "扫得到、但反射生不成"的两类要照实说（真机 0227 清单只剩 {@code a.c.a.e}，用户以为工具没扫到怪）。
     *
     * <p>mock 只能复刻第二类：javac 总会隐式生成一个构造器，做不出真机那种"零构造器的死壳类"——
     * 那一类是 javap 直接看 {@code inf-20100227.jar} 定的结论（{@code a.c.a.a/b/c/d/f} 与
     * {@code a.c.b.b/c} 只剩 {@code e()/b()/f()}，{@code <init>} 被 ProGuard 整条删掉了；
     * 可读源里那些 {@code public EntityPig(World)} 是 RetroMCP 补出来的，不能当依据）。
     */
    private static void unconstructableCheck(Variant v, String note) {
        if (v.altCtorClass == null) {
            return;
        }
        ok(v.id + "：签名不是 (World) 的类要连签名一起说清楚（" + v.pkg + v.altCtorClass + "）",
                note != null && note.contains("构造器不是单个 (World)")
                        && note.contains(v.pkg + v.altCtorClass)
                        && note.contains(", int)"));
    }

    /**
     * 类池得是<b>活的</b>：绑定时那份快照如果缺了叶类，"下面还有更具体的子类"这条判据就判不出来，
     * 非 abstract 的怪物基类（{@code v.mobBaseClass}，仿真机 0227 的 {@code EntityMob}）会被当成
     * 唯一的叶子留在清单上。池子补上叶类之后基类要立刻被踢掉。
     *
     * <p>注意：这一条<b>不是</b>真机 0227"清单只剩一个类型"的成因（那是那些 mob 类在 jar 里
     * 连 {@code <init>} 都没有，见 {@link #unconstructableCheck}），只是"绑定时机太早 / 类后来才加载"
     * 这种情形的保险。
     */
    private static void livePoolCheck(Variant v, Handles h, TickPump pump, List<Class<?>> classes)
            throws Exception {
        Class<?> mobBase = Class.forName(v.pkg + v.mobBaseClass);
        Class<?> leaf = Class.forName(v.pkg + v.lazyLeafClass);
        // 注入那一刻的快照：叶类还没被 JVM 载入
        // 快照里"没有叶类"= 注入瞬间 JVM 还没加载它（mock 不用真的卸载类，只要从池子里拿掉就等于没加载）
        final List<Class<?>> pool = new ArrayList<Class<?>>(classes);
        pool.remove(leaf);
        List<EntityOps.TypeInfo> t1 = new EntityOps(h, null, new ArrayList<Class<?>>(pool))
                .spawnableTypes();
        ok(v.id + "：类池是死快照时，怪物基类会被当成唯一的叶子（真机 0227 的现象："
                + describe(t1) + "）", containsType(t1, mobBase) && !containsType(t1, leaf));

        EntityOps live = new EntityOps(h, pump, new EntityOps.ClassSource() {
            public List<Class<?>> classes() {
                return pool;
            }
        });
        ok(v.id + "：活的类池一开始也只看得到基类", containsType(live.spawnableTypes(), mobBase));
        pool.add(leaf); // = 类池里终于有了那个叶类
        List<EntityOps.TypeInfo> t2 = live.spawnableTypes();
        ok(v.id + "：叶类一被加载就自己出现在清单上（不用重新注入）", containsType(t2, leaf));
        ok(v.id + "：基类随即被\"有更具体的子类\"踢掉（" + describe(t2) + "）", !containsType(t2, mobBase));
        ok(v.id + "：踢掉基类的原因说的是全名（混淆包末段全都重名，只报末段没法查）: " + live.lastFilterNote,
                live.lastFilterNote != null && live.lastFilterNote.contains(leaf.getName()));
    }

    /**
     * 按 {@code (World)} 构造器捏一个实例，再走一遍渲染器对该类型做的事：
     * 抛异常 = 这个类型塞进世界就是把游戏打死（陷阱类 {@code f} 就是这条）。
     */
    private static boolean throwsOnRender(Class<?> type, Class<?> worldClass, Object world) {
        try {
            Constructor<?> ctor = type.getDeclaredConstructor(worldClass);
            ctor.setAccessible(true);
            Object e = ctor.newInstance(world);
            type.getMethod("renderLikeRenderItem").invoke(e);
            return false;
        } catch (java.lang.reflect.InvocationTargetException t) {
            return t.getCause() instanceof NullPointerException;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String describe(List<EntityOps.TypeInfo> types) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < types.size(); i++) {
            sb.append(i > 0 ? "," : "").append(types.get(i).type.getSimpleName());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ GUI 冒烟

    /**
     * 真机反馈的三个界面问题的回归：刷新循环每 1.2 秒把表模型整表重建，于是
     * <ul>
     *   <li>点选中行展开的字段表"闪一下就没了、整张表变白、什么都改不了"
     *       —— {@code fireTableDataChanged()} 清了选中行，字段表跟着被重建；</li>
     *   <li>类型下拉框选不上（"添加实体没实现"）—— {@code removeAllElements()} 每 1.2 秒把选中项抹成 null；</li>
     *   <li>类型注册表页签一片空白，看着像坏了 —— 其实 Indev 根本没有 EntityList 那张表，现在写明。</li>
     * </ul>
     * 这里真的把窗口建出来，跨过好几个刷新周期，验证选中行、字段表、下拉选中项都还在，
     * 并且点一次"生成"能走完整条链（下拉选中项 -> EntityOps.spawn -> 队列 -> 游戏线程 -> 登记表）。
     *
     * <p>只在有显示器的环境跑（CI 上无头会自动跳过）：{@code run.bat mock gui}。
     */
    private static void guiSmoke() throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println();
            System.out.println("[跳过] 无显示器环境，不做 GUI 冒烟");
            return;
        }
        System.out.println();
        System.out.println("=== GUI 冒烟（0413：真建窗口，跨过多个 1.2 秒刷新周期）");
        Class<?> gameClass = Class.forName("mock.v0413.net.minecraft.client.d");
        final Object game = gameClass.getDeclaredConstructor(new Class[0]).newInstance(new Object[0]);
        gameClass.getMethod("a", new Class[0]).invoke(game, new Object[0]);
        Thread gameThread = new Thread((Runnable) game, "mock-v0413-gui");
        gameThread.setDaemon(true);
        gameThread.start();
        List<Class<?>> classes = scanClasses(new File("build-test/mock/v0413"));
        final Handles h = GameBinder.bind(game, classes, SINK);
        final TickPump pump = new TickPump(h);
        pump.install(SINK);
        ok("GUI: 游戏线程通道可用", h.pumpRunning);
        ok("GUI: 包装了登记表（" + pump.wrappedLists() + " 张）", pump.wrappedLists() >= 1);
        final EntityOps ops = new EntityOps(h, pump, classes);
        // 和 AgentMain 一样在普通线程上建窗：窗口自己会把"显示"这一步排给 EDT
        Thread setup = new Thread(new Runnable() {
            public void run() {
                new EntityWindow(ops, h, pump);
            }
        }, "mock-gui-setup");
        setup.setDaemon(true);
        setup.start();
        JFrame found = null;
        for (int i = 0; i < 40 && found == null; i++) {
            Thread.sleep(200L);
            found = findWindow();
        }
        final JFrame frame = found;
        ok("GUI: 窗口出来了", frame != null);
        ok("GUI: 建窗跑在 EDT 上（原生 show 只能在 EDT 做：交给游戏线程会把 EDT 堵死，表就永远是空的）: 实际="
                + EntityWindow.showThread(), "AWT-EventQueue-0".equals(EntityWindow.showThread()));
        if (frame == null) {
            return;
        }
        // 显示那一瞬是"不可激活"（WS_EX_NOACTIVATE），可聚焦性是延时 400ms 还回来的，等它落地
        for (int i = 0; i < 20 && !frame.getFocusableWindowState(); i++) {
            Thread.sleep(150L);
        }
        ok("GUI: 显示完成后窗口可以聚焦（NOACTIVATE 只在显示那一瞬用，之后必须能正常键入）",
                frame.getFocusableWindowState());
        ok("GUI: 显示流程走到底（4=出窗口 + 填完首轮数据；停在 2 说明原生 show 卡死）: 实际="
                + EntityWindow.showStep(), EntityWindow.showStep() >= 4);
        final JTable entities = tableWithColumns(frame, 7);
        final JTable fields = tableWithColumns(frame, 4);
        final JComboBox types = firstCombo(frame);
        ok("GUI: 找到实体表 / 字段表 / 类型下拉框", entities != null && fields != null && types != null);
        if (entities == null || fields == null || types == null) {
            dispose(frame);
            return;
        }
        // 首轮刷新排在 setVisible 之后的 EDT 任务里，窗口刚可见时表可能还是空的，等它落地
        for (int i = 0; i < 30 && (entities.getRowCount() == 0 || types.getItemCount() == 0); i++) {
            Thread.sleep(200L);
        }
        ok("GUI: 类型下拉框列出了 " + types.getItemCount() + " 个可生成类型", types.getItemCount() > 0);

        // 等世界里的实体长起来（0413 的 mob 是 tick 里生成的），至少要有一个可展开字段的样本
        for (int i = 0; i < 40 && h.allEntities().isEmpty(); i++) {
            Thread.sleep(200L);
        }
        ok("GUI: 实体表里有行了（" + entities.getRowCount() + " 行）", entities.getRowCount() > 0);
        if (entities.getRowCount() == 0) {
            dispose(frame);
            return;
        }
        final int pick = entities.getRowCount() > 1 ? 1 : 0;
        final Object picked = h.allEntities().get(pick);
        selectRow(entities, pick);
        Thread.sleep(400L);
        final int fieldRows = fields.getRowCount();
        ok("GUI: 选中实体后展开了字段（" + fieldRows + " 行）", fieldRows > 0);
        ok("GUI: 字段表的\"值\"列可编辑、其余列只读",
                fieldRows == 0 || (fields.isCellEditable(0, 2) && !fields.isCellEditable(0, 0)));
        ok("GUI: 实体表一个格子都不给编辑（AbstractTableModel 默认是全放开：点一下弹出编辑器，"
                + "1.2 秒后行更新又被 Swing 关掉，真机那句\"很快变白然后消失，整个不可编辑\"就是它）",
                !entities.isCellEditable(0, 0) && !entities.isCellEditable(0, 3));

        // 布局回归：真机 0223 上实体表挂在 BorderLayout.NORTH，18 行按首选尺寸硬占 460px，
        // 把"添加实体/修改/按钮/字段表"整片挤成 0 高度 —— 用户根本看不到生成按钮，也没有可输入的框。
        // 只查当前页签里的控件：没选中的页签本来就 isShowing()==false，不算被挤没。
        List<Object> tabsFound = new ArrayList<Object>();
        collect(frame, javax.swing.JTabbedPane.class, tabsFound);
        Container currentTab = tabsFound.isEmpty() ? frame
                : (Container) ((javax.swing.JTabbedPane) tabsFound.get(0)).getSelectedComponent();
        List<Object> controls = new ArrayList<Object>();
        collect(currentTab, JButton.class, controls);
        collect(currentTab, javax.swing.JTextField.class, controls);
        StringBuilder crushed = new StringBuilder();
        int checked = 0;
        for (int i = 0; i < controls.size(); i++) {
            Component c = (Component) controls.get(i);
            if (inScrollBar(c)) {
                continue; // 滚动条上的箭头按钮本来就是 0 尺寸（不需要滚动时不显示），不算被布局挤没
            }
            checked++;
            if (!c.isShowing() || c.getHeight() <= 0 || c.getWidth() <= 0) {
                crushed.append(c.getClass().getSimpleName()).append('(').append(c.getWidth()).append('x')
                        .append(c.getHeight()).append(") ");
            }
        }
        ok("GUI: 「实体」页签里 " + checked + " 个按钮/输入框全部可见且有尺寸（没有一个被布局挤没）: 挤没的="
                + crushed, checked >= 10 && crushed.length() == 0);
        setCombo(types, 0);

        // 关键：跨过 4 个刷新周期，看界面状态还在不在
        Thread.sleep(5200L);
        int selected = entities.getSelectedRow();
        ok("GUI: 刷新循环没有丢掉的选中实体（仍是同一个对象）",
                selected >= 0 && selected < h.allEntities().size() && h.allEntities().get(selected) == picked);
        ok("GUI: 刷新循环没有把字段表冲掉（仍有 " + fields.getRowCount() + " 行）",
                fields.getRowCount() == fieldRows);
        ok("GUI: 刷新循环没有把类型下拉的选中项抹成空", types.getSelectedIndex() == 0);
        ok("GUI: 字段值原地刷新过（第 0 行现在是 " + fields.getValueAt(0, 2) + "）",
                fieldRows == 0 || fields.getValueAt(0, 2) != null);

        final int before = h.allEntities().size();
        final String field0Before = fields.getRowCount() == 0 ? "" : String.valueOf(fields.getValueAt(0, 0));
        clickButton(frame, "生成");
        Thread.sleep(2000L);
        int after = h.allEntities().size();
        ok("GUI: 点\"生成\"后世界里多了一个实体（" + before + " -> " + after + "）", after == before + 1);
        ok("GUI: 实体表行数跟着长了一行", entities.getRowCount() == after);
        ok("GUI: 生成新实体没有把选中的实体换掉（字段表第一项还是 \"" + field0Before + "\"）",
                fields.getRowCount() > 0 && String.valueOf(fields.getValueAt(0, 0)).equals(field0Before));
        ok("GUI: 生成结论回报到了界面上: " + EntityOps.lastNote,
                EntityOps.lastNote != null && EntityOps.lastNote.indexOf("生成") >= 0);

        int targetRow = -1;
        for (int i = 0; i < fields.getRowCount(); i++) {
            if ("int".equals(String.valueOf(fields.getValueAt(i, 1)))) {
                targetRow = i;
                break;
            }
        }
        if (targetRow >= 0) {
            final int row = targetRow;
            final String fieldName = String.valueOf(fields.getValueAt(row, 0));
            final JButton writer = buttonNamed(frame, "写入选中字段");
            ok("GUI: 找到\"写入选中字段\"按钮", writer != null);
            if (writer != null) {
                // 改值和点按钮必须在同一个 EDT 回合里做完：否则刷新循环可能抢先把这格刷回实时值
                SwingUtilities.invokeAndWait(new Runnable() {
                    public void run() {
                        fields.setRowSelectionInterval(row, row);
                        fields.setValueAt("12345", row, 2);
                        writer.doClick();
                    }
                });
                Thread.sleep(1600L);
                Object written = readField(picked, fieldName);
                // 选中的这个 int 字段是游戏每 tick 自己会加的计数器（原值 438 → 一秒多变 12422）：
                // 只要它落进了 [12345, 13000) 就说明"我们写的 12345 生效了"，之后游戏又自己加了几十点。
                ok("GUI: 字段写到了对象上（" + fieldName + " = " + written + "）: " + EntityOps.lastNote,
                        written instanceof Integer && ((Integer) written).intValue() >= 12345
                                && ((Integer) written).intValue() < 13000);
            }
        } else {
            System.out.println("        | (字段表里没有 int 字段，跳过写入用例)");
        }

        // 移除：选最后那一行（刚生成的 mob，绝不可能是玩家）
        final int last = entities.getRowCount() - 1;
        final Object victim = h.allEntities().get(last);
        selectRow(entities, last);
        clickButton(frame, "移除选中");
        Thread.sleep(2000L);
        ok("GUI: 点\"移除选中\"真的把那个实体摘掉了", !containsIdentity(h.allEntities(), victim));
        ok("GUI: 移除结论回报到了界面上: " + EntityOps.lastNote,
                EntityOps.lastNote != null && EntityOps.lastNote.indexOf("移除") >= 0);

        // 行序稳定回归：真机 0223 的实体住在 EntityMap 的 8192 格 List[] 里，实体一走出格子范围，
        // 容器枚举出来的先后就变了（成员没变、只是顺序变）。把登记表原地倒序来复现这件事：
        // 窗口不该因此重排行、把选中行跳到别处、或者把展开的字段表冲掉。
        // 先把表撑到 3 行以上（只有 1 行的话倒序等于没倒，测不出东西）。
        clickButton(frame, "生成");
        Thread.sleep(900L);
        clickButton(frame, "生成");
        Thread.sleep(900L);
        final int anchor = Math.min(1, entities.getRowCount() - 1);
        selectRow(entities, anchor);
        Thread.sleep(400L);
        final List<String> orderBefore = columnTexts(entities, 2);
        final int fieldRowsBefore = fields.getRowCount();
        final String firstField = String.valueOf(fields.getValueAt(0, 0));
        reverseFirstRegistry(h);
        Thread.sleep(3000L);
        ok("GUI: 登记表换了枚举顺序，实体表的行没有重排（" + orderBefore + " -> " + columnTexts(entities, 2) + "）",
                columnTexts(entities, 2).equals(orderBefore));
        ok("GUI: 换了枚举顺序后选中行没跳（仍是第 " + entities.getSelectedRow() + " 行）、字段表没被冲掉（仍有 "
                + fields.getRowCount() + " 个字段）",
                entities.getSelectedRow() == anchor && fields.getRowCount() == fieldRowsBefore
                        && String.valueOf(fields.getValueAt(0, 0)).equals(firstField));

        // 换关卡回归（放在最后：以上断言都指着旧那批实体，换完世界它们就不成立了）。
        // 真机症状是"换了世界，界面不再更新"——这里让 mock 重新载入一个关卡，看窗口自己跟不跟得上。
        final Object guiOldWorld = h.world;
        gameClass.getMethod("a", new Class[0]).invoke(game, new Object[0]);
        boolean switched = false;
        for (int i = 0; i < 30 && !switched; i++) {
            Thread.sleep(200L);
            switched = h.world != guiOldWorld;
        }
        ok("GUI: 换关卡后不用重新注入，窗口自己就跟上了新的 World 实例", switched);
        ok("GUI: 换关卡后游戏线程通道重挂上了（否则按钮按下去永远没反应）", await(new Checker() {
            public boolean done() {
                return h.pumpRunning;
            }
        }, 6000));
        ok("GUI: 实体表已经换成新世界那批实体（旧世界选中的 \"" + picked.getClass().getSimpleName()
                        + "\" 不在表里了）",
                await(new Checker() {
                    public boolean done() {
                        return entities.getRowCount() >= 1 && !containsIdentity(h.allEntities(), picked);
                    }
                }, 6000));

        dispose(frame);
        pump.uninstall();
        try {
            gameClass.getField("running").setBoolean(game, false);
        } catch (Throwable ignored) {
        }
    }

    /** 把某一列的显示内容按行抄出来（验行序用）。 */
    private static List<String> columnTexts(JTable table, int col) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < table.getRowCount(); i++) {
            out.add(String.valueOf(table.getValueAt(i, col)));
        }
        return out;
    }

    /** 把第一张登记表原地倒序（真机 0223 的实体换格会让容器枚举顺序变，成员却没变）。 */
    @SuppressWarnings("unchecked")
    private static void reverseFirstRegistry(Handles h) {
        List<Object> regs = h.registries();
        for (int i = 0; i < regs.size(); i++) {
            Object r = regs.get(i);
            if (r instanceof List && ((List<?>) r).size() > 1) {
                java.util.Collections.reverse((List<Object>) r);
                return;
            }
        }
        throw new IllegalStateException("没有可倒序的登记表");
    }

    private static boolean containsIdentity(List<?> list, Object o) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == o) {
                return true;
            }
        }
        return false;
    }

    private static JFrame findWindow() {
        Frame[] fs = Frame.getFrames();
        for (int i = 0; i < fs.length; i++) {
            if (fs[i] instanceof JFrame && fs[i].isDisplayable()
                    && String.valueOf(fs[i].getTitle()).startsWith("Entity Hack")) {
                return (JFrame) fs[i];
            }
        }
        return null;
    }

    private static void collect(Container c, Class<?> what, List<Object> out) {
        Component[] cs = c.getComponents();
        for (int i = 0; i < cs.length; i++) {
            if (what.isInstance(cs[i])) {
                out.add(cs[i]);
            }
            if (cs[i] instanceof Container) {
                collect((Container) cs[i], what, out);
            }
        }
    }

    /** 组件是否挂在滚动条上（滚动条箭头按钮平时是 0 尺寸，不能算"被布局挤没"）。 */
    private static boolean inScrollBar(Component c) {
        for (Component p = c; p != null; p = p.getParent()) {
            if (p instanceof javax.swing.JScrollBar) {
                return true;
            }
        }
        return false;
    }

    /** 实体表 7 列、字段表 4 列、注册表 2 列——用列数认表，比按添加顺序数可靠。 */
    private static JTable tableWithColumns(JFrame frame, int columns) {
        List<Object> ts = new ArrayList<Object>();
        collect(frame, JTable.class, ts);
        for (int i = 0; i < ts.size(); i++) {
            if (((JTable) ts.get(i)).getColumnCount() == columns) {
                return (JTable) ts.get(i);
            }
        }
        return null;
    }

    private static JComboBox firstCombo(JFrame frame) {
        List<Object> cs = new ArrayList<Object>();
        collect(frame, JComboBox.class, cs);
        return cs.isEmpty() ? null : (JComboBox) cs.get(0);
    }

    private static void selectRow(final JTable table, final int row) throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                table.setRowSelectionInterval(row, row);
            }
        });
    }

    private static void setCombo(final JComboBox combo, final int index) throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                combo.setSelectedIndex(index);
            }
        });
    }

    private static void clickButton(Container root, String text) throws Exception {
        JButton target = buttonNamed(root, text);
        ok("GUI: 找到\"" + text + "\"按钮", target != null);
        if (target == null) {
            return;
        }
        final JButton button = target;
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                button.doClick();
            }
        });
    }

    private static JButton buttonNamed(Container root, String text) {
        List<Object> bs = new ArrayList<Object>();
        collect(root, JButton.class, bs);
        for (int i = 0; i < bs.size(); i++) {
            if (text.equals(((JButton) bs.get(i)).getText())) {
                return (JButton) bs.get(i);
            }
        }
        return null;
    }

    private static void dispose(final JFrame frame) throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                frame.dispose();
            }
        });
    }

    // ------------------------------------------------------------------ 断言与工具

    private interface Checker {
        boolean done();
    }

    private static void eq(String name, Object expect, Object actual) {
        boolean same = expect == null ? actual == null : expect.equals(actual);
        if (same) {
            ok(name, true);
        } else {
            fail(name + "  期望=" + expect + " 实际=" + actual);
            total++;
        }
    }

    private static void ok(String name, boolean condition) {
        total++;
        if (condition) {
            System.out.println("  [OK]   " + name);
        } else {
            System.out.println("  [FAIL] " + name);
            FAILS.add(name);
        }
    }

    private static void fail(String name) {
        System.out.println("  [FAIL] " + name);
        FAILS.add(name);
    }

    private static boolean await(boolean[] flag, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (flag[0]) {
                return true;
            }
            sleep(20);
        }
        return flag[0];
    }

    private static boolean await(Checker c, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (c.done()) {
                return true;
            }
            sleep(20);
        }
        return c.done();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean logContains(String needle) {
        synchronized (LOGS) {
            for (int i = 0; i < LOGS.size(); i++) {
                if (LOGS.get(i).contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int count(Handles h, EntityOps ops, Class<?> type) {
        return ofType(h.allEntities(), type).size();
    }

    private static List<Object> ofType(List<Object> entities, Class<?> type) {
        List<Object> out = new ArrayList<Object>();
        for (int i = 0; i < entities.size(); i++) {
            if (entities.get(i).getClass() == type) {
                out.add(entities.get(i));
            }
        }
        return out;
    }

    private static boolean hasFieldNamed(EntityOps ops, Object entity, String field) {
        List<EntityOps.FieldInfo> fs = ops.fields(entity);
        for (int i = 0; i < fs.size(); i++) {
            if (fs.get(i).name().equals(field)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsType(List<EntityOps.TypeInfo> types, Class<?> c) {
        for (int i = 0; i < types.size(); i++) {
            if (types.get(i).type == c) {
                return true;
            }
        }
        return false;
    }

    private static Checker containsId(final EntityOps ops, final String id) {
        return new Checker() {
            public boolean done() {
                return indexOf(ops, id) >= 0;
            }
        };
    }

    private static Checker notContainsId(final EntityOps ops, final String id) {
        return new Checker() {
            public boolean done() {
                return indexOf(ops, id) < 0;
            }
        };
    }

    private static int indexOf(EntityOps ops, String id) {
        List<String[]> ids = ops.registryIds();
        for (int i = 0; i < ids.size(); i++) {
            if (id.equals(ids.get(i)[0])) {
                return i;
            }
        }
        return -1;
    }

    private static Object readField(Object o, String name) {
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 登记表字段还原检查：所有 List 字段都不应该再是动态代理。 */
    private static boolean noProxyLeft(Handles h) {
        List<Object> regs = h.registries();
        for (int i = 0; i < regs.size(); i++) {
            if (Proxy.isProxyClass(regs.get(i).getClass())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 连着盯 {@code ms} 毫秒，<b>每一个</b>轮询点都必须成立。
     * {@link #await} 是"等到出现为止"，读中一次就算过；真机 0223 的反弹恰恰是
     * "写完那一刻对、下一 tick 游戏从旧碰撞盒把读数覆盖回去"，所以必须连续守满十几个 tick。
     */
    private static boolean stays(Checker c, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (!c.done()) {
                return false;
            }
            sleep(20);
        }
        return c.done();
    }

    /**
     * 碰撞盒的"读数"：x/z 取两角中点、y 取底角——照可读源 {@code Entity.java:304-306}
     * 里 {@code moveEntity} 反推坐标的那三行。拿不到盒子返回 null（断言会照样红）。
     */
    private static double[] boxCenter(Handles h, Object entity) {
        if (h.aabbField == null) {
            return null;
        }
        Object box = GameShape.read(h.aabbField, entity);
        if (box == null) {
            return null;
        }
        Field[] corners = GameShape.aabbCorners(box.getClass());
        if (corners.length != 6) {
            return null;
        }
        double minX = GameBinder.readNumber(corners[0], box);
        double minY = GameBinder.readNumber(corners[1], box);
        double minZ = GameBinder.readNumber(corners[2], box);
        double maxX = GameBinder.readNumber(corners[3], box);
        double maxZ = GameBinder.readNumber(corners[5], box);
        return new double[] { (minX + maxX) / 2.0D, minY, (minZ + maxZ) / 2.0D };
    }

    private static String fmt3(double[] v) {
        if (v == null) {
            return "无";
        }
        return round2(v[0]) + "," + round2(v[1]) + "," + round2(v[2]);
    }

    private static String round2(double v) {
        return String.valueOf(Math.round(v * 100.0D) / 100.0D);
    }

    /**
     * 实体是不是<b>只</b>待在 (x,y,z) 对应的那一个格子里。
     * 索引照实物 {@code EntityMapSlot.init(pos)}：先 {@code (int)(pos / 8)}、再夹紧到
     * {@code [0, dim-1]}、最后按 {@code (z*depth + y)*width + x} 取号。
     * 渲染（{@code RenderGlobal.renderEntities}）只遍历格子，格子搬错＝传送后画不出来。
     */
    private static boolean inCellOf(Handles h, Object entity, double x, double y, double z) {
        Object[] hit = findGrid(h.world, 0);
        if (hit == null) {
            return false;
        }
        List[] grid = (List[]) hit[0];
        int[] dims = gridDims(hit[1]);
        int want = cellIndex(new double[] { x, y, z }, dims, grid.length);
        if (want < 0) {
            return false;
        }
        int cells = 0;
        for (int i = 0; i < grid.length; i++) {
            List cell = grid[i];
            if (cell != null && cell.contains(entity)) {
                if (i != want) {
                    return false; // 还留在旧格子里 = 搬家没成功
                }
                cells++;
            }
        }
        return cells == 1;
    }

    /** 从 owner 出发（最多钻两层）找那张 {@code List[]} 网格，返回 {@code {网格, 网格的主人}}。 */
    private static Object[] findGrid(Object owner, int depth) {
        List<Field> fs = GameShape.allFields(owner.getClass());
        for (int i = 0; i < fs.size(); i++) {
            Field f = fs.get(i);
            Object v = GameShape.read(f, owner);
            if (v == null || v == owner) {
                continue;
            }
            if (v instanceof List[]) {
                return new Object[] { v, owner };
            }
            if (depth < 2 && !v.getClass().isPrimitive() && GameShape.declaresListArray(v.getClass())) {
                Object[] hit = findGrid(v, depth + 1);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    /** 网格尺寸 = owner 上声明顺序最靠前的三个正 int 字段（实物 EntityMap 的 width / depth / height）。 */
    private static int[] gridDims(Object owner) {
        int[] out = new int[] { 32, 32, 32 };
        int i = 0;
        List<Field> fs = GameShape.allFields(owner.getClass());
        for (int k = 0; k < fs.size() && i < 3; k++) {
            Field f = fs.get(k);
            if (f.getType() != int.class) {
                continue;
            }
            int v = (int) GameBinder.readNumber(f, owner);
            if (v > 0 && v <= 4096) {
                out[Math.min(i, 2)] = v;
                i++;
            }
        }
        return out;
    }

    /** 格子边长那个年代固定是 8（{@code EntityMapSlot.init} 里写死的 {@code / 8.0F}）。 */
    private static int cellIndex(double[] pos, int[] dims, int gridLength) {
        int x = clampCell((int) (pos[0] / 8), dims[0]);
        int y = clampCell((int) (pos[1] / 8), dims[1]);
        int z = clampCell((int) (pos[2] / 8), dims[2]);
        int index = (z * dims[1] + y) * dims[0] + x;
        return index >= 0 && index < gridLength ? index : -1;
    }

    private static int clampCell(int cell, int dim) {
        return cell < 0 ? 0 : (cell >= dim ? dim - 1 : cell);
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 0.01;
    }

    private static String names(Field[] fs) {
        if (fs == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fs.length; i++) {
            sb.append(i > 0 ? "," : "").append(fs[i].getName());
        }
        return sb.toString();
    }

    private static String name(Method m) {
        return m == null ? null : m.getName();
    }

    private static Class<?> findClass(List<Class<?>> classes, String name) {
        for (int i = 0; i < classes.size(); i++) {
            if (classes.get(i).getName().equals(name)) {
                return classes.get(i);
            }
        }
        return null;
    }

    /** 把 {@code build-test/mock/<版本>/} 目录下编出来的类全部加载，模拟 Instrumentation.getAllLoadedClasses()。 */
    private static List<Class<?>> scanClasses(File dir) {
        List<Class<?>> out = new ArrayList<Class<?>>();
        collect(dir, out);
        return out;
    }

    private static void collect(File dir, List<Class<?>> out) {
        File[] files = dir.listFiles();
        if (files == null) {
            System.out.println("  !! 目录不存在: " + dir.getPath() + "（先跑 compile.bat test）");
            return;
        }
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            if (f.isDirectory()) {
                collect(f, out);
                continue;
            }
            String n = f.getName();
            if (!n.endsWith(".class") || n.indexOf('$') >= 0) {
                continue;
            }
            String path = f.getPath().replace(File.separatorChar, '/');
            int cut = path.indexOf("build-test/");
            String cn = path.substring(cut + "build-test/".length(), path.length() - ".class".length())
                    .replace('/', '.');
            try {
                out.add(Class.forName(cn, true, MockMain.class.getClassLoader()));
            } catch (Throwable t) {
                System.out.println("  !! 无法加载 " + cn + ": " + t);
            }
        }
    }
}
