package org.github.creatorcsie.entityhack.agent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.List;

/**
 * 三类操控的落地：添加 / 移除 / 修改（+ 类型注册表 = 那个年代的"id 空间"）。
 * 所有会动到登记表/渲染表的写操作都经 {@link TickPump} 排队到游戏线程；
 * 纯读（列实体、读字段）由界面线程直接反射读，和 Position-Hack 读 AABB 同风险级。
 */
public final class EntityOps {

    /**
     * 最近一次操作的结论（成功条数 / 失败原因）。
     * 排队到游戏线程执行的操作发生在点击之后，光写在日志里界面上看不出名堂，
     * 所以在这里留一份，由 {@link EntityWindow} 的刷新循环捞出来显示到状态区。
     */
    public static volatile String lastNote;

    private static void note(String line) {
        lastNote = line;
        AgentMain.log(line);
    }

    /**
     * 候选类池。每轮刷新现捞一次 {@code Instrumentation.getAllLoadedClasses()}：
     * 绑定时世界可能还没加载完，那一刻的快照是空的，冻住就再也不出清单了。
     *
     * <p>注意它<b>不是</b>真机 in-20100227"清单只剩 {@code a.c.a.e}（EntityMob 基类）"的解药——
     * 那一版只剩一个类型是那个 jar 的客观事实：5 只怪 + 2 只被动怪在字节码里<b>一个构造器都没有</b>
     * （ProGuard 把没人调用的 {@code <init>} 整条删了，那个年代 mob 本来就被移除了），
     * 有 {@code (World)} 构造器的实体类一共只有 5 个，其余 4 个是玩家与基类。
     * 预加载整个 jar 的类名（真机试过：273 个名字全部解析成功）也证明候选数 5→5 纹丝不动。
     */
    public interface ClassSource {
        List<Class<?>> classes();
    }

    private final Handles h;
    private final TickPump pump;
    private final ClassSource classes;
    /**
     * 每个实体"最后一次要求去的位置"。回查线程各睡各的 1.5 秒，连点两次移动时，
     * 第一轮回查读到的会是第二次的目标——不比对就会误报"被游戏改回去了"。
     */
    private final Map<Object, double[]> lastMoveTarget =
            Collections.synchronizedMap(new IdentityHashMap<Object, double[]>());

    public EntityOps(Handles h, TickPump pump, ClassSource classes) {
        this.h = h;
        this.pump = pump;
        this.classes = classes;
    }

    /** 固定的一份类池（回归测试要模拟"注入瞬间的快照"，真机走上面那个活的类池）。 */
    public EntityOps(Handles h, TickPump pump, final List<Class<?>> gameClasses) {
        this(h, pump, new ClassSource() {
            public List<Class<?>> classes() {
                return gameClasses;
            }
        });
    }

    public Handles handles() {
        return h;
    }

    /** 列类型时被踢掉的原因（界面要照实说：用户会问"清单里为什么没有掉落物/箭"）。 */
    public volatile String lastFilterNote;

    /**
     * 可生成的实体类型（<b>安全</b>清单：有 {@code (World)} 构造器、且渲染器不会当场 NPE），
     * 带注册表里的 id 串。被踢掉的类型和原因记在 {@link #lastFilterNote}。
     */
    public List<TypeInfo> spawnableTypes() {
        List<String> dropped = new ArrayList<String>();
        List<Class<?>> subs = GameBinder.renderSafeEntitySubclasses(h, classes.classes(), dropped);
        GameBinder.reportFilterOnce(h, dropped, AGENT_LOG);
        lastFilterNote = dropped.isEmpty() ? null : join(dropped);
        List<TypeInfo> out = new ArrayList<TypeInfo>();
        java.util.Map<Object, Object> classToName = readMap(h.classToStringMap);
        for (Class<?> c : subs) {
            String id = classToName == null ? null : str(classToName.get(c));
            out.add(new TypeInfo(c, id == null ? c.getName() : id, id != null));
        }
        java.util.Collections.sort(out, new java.util.Comparator<TypeInfo>() {
            public int compare(TypeInfo a, TypeInfo b) {
                if (a.registered != b.registered) {
                    return a.registered ? -1 : 1;
                }
                return a.display().compareToIgnoreCase(b.display());
            }
        });
        return out;
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            sb.append(i > 0 ? "；" : "").append(parts.get(i));
        }
        return sb.toString();
    }

    private static final GameBinder.Log AGENT_LOG = new GameBinder.Log() {
        public void log(String line) {
            AgentMain.log(line);
        }
    };

    /** 生成：配方 A（标准反射 + spawnEntityInWorld）。0413/0414 的 mob 叶类不会出现在类型清单里。 */
    public void spawn(final Class<?> type, final double x, final double y, final double z, final int count) {
        pump.submit(new Runnable() {
            public void run() {
                Constructor<?> ctor;
                try {
                    ctor = type.getDeclaredConstructor(h.worldClass);
                } catch (Throwable t) {
                    note("生成失败：" + type.getName() + " 没有 (" + h.worldClass.getSimpleName() + ") 构造器");
                    return;
                }
                ctor.setAccessible(true);
                int ok = 0;
                String error = null;
                String how = null;
                for (int i = 0; i < count; i++) {
                    try {
                        Object e = ctor.newInstance(h.world);
                        double ox = x + (i % 5) * 1.5;
                        double oz = z + (i / 5) * 1.5;
                        // 坐标 + 碰撞盒一起写：只写坐标的话，游戏每 tick 会从旧盒子里把坐标反推出来（见 Handles.applyPosition）
                        how = h.applyPosition(e, ox, y, oz, false);
                        GameBinder.writeTriple(h.motionTriple, e, 0, 0, 0);
                        if (h.spawnMethod != null) {
                            h.spawnMethod.setAccessible(true);
                            h.spawnMethod.invoke(h.world, e);
                        } else {
                            h.addToRegistries(e);
                        }
                        ok++;
                    } catch (Throwable t) {
                        error = cause(t);
                    }
                }
                note("生成 " + type.getName() + "：" + ok + "/" + count + " 个 @ "
                        + TRIM.format(x) + ", " + TRIM.format(y) + ", " + TRIM.format(z)
                        + (how == null ? "" : "（" + how + "）")
                        + (error == null ? "" : "；失败原因：" + error)
                        + (h.spawnMethod == null ? "（没有生成口，直接写登记表）" : "（走 " + h.spawnMethod.getName() + "）"));
            }
        });
    }

    private static final java.text.DecimalFormat TRIM = new java.text.DecimalFormat("0.##");

    /** 移除：优先游戏自己的置死口（下一 tick 泵自己摘干净），其次立即摘除口，最后才直写登记表。 */
    public void remove(final Object entity) {
        pump.submit(new Runnable() {
            public void run() {
                removeNow(entity);
            }
        });
    }

    /** 批量移除：按 id 过滤（null/空 = 全部，但绝不碰玩家）。 */
    public int removeMatching(final String idFilter) {
        final List<Object> targets = new ArrayList<Object>();
        Object player = GameBinder.currentPlayer(h);
        for (Object e : h.allEntities()) {
            if (e == player) {
                continue;
            }
            if (idFilter == null || idFilter.length() == 0 || idOf(e).equalsIgnoreCase(idFilter)) {
                targets.add(e);
            }
        }
        pump.submit(new Runnable() {
            public void run() {
                for (int i = 0; i < targets.size(); i++) {
                    removeNow(targets.get(i));
                }
            }
        });
        return targets.size();
    }

    private void removeNow(Object entity) {
        String what = simpleName(entity);
        if (h.deadMarkMethod != null) {
            try {
                h.deadMarkMethod.setAccessible(true);
                h.deadMarkMethod.invoke(Modifier.isStatic(h.deadMarkMethod.getModifiers()) ? null : h.world, entity);
                note("移除 " + what + "：走游戏自己的置死口 " + h.deadMarkMethod.getName() + "()");
                return;
            } catch (Throwable t) {
                AgentMain.log("deadMark failed, try hard remove: " + cause(t));
            }
        }
        if (h.hardRemoveMethod != null) {
            try {
                h.hardRemoveMethod.setAccessible(true);
                h.hardRemoveMethod.invoke(h.world, entity);
                note("移除 " + what + "：走立即摘除口 " + h.hardRemoveMethod.getName() + "()");
                return;
            } catch (Throwable t) {
                AgentMain.log("hardRemove failed, try dead flag: " + cause(t));
            }
        }
        if (h.deadFlag != null) {
            try {
                h.deadFlag.setAccessible(true);
                h.deadFlag.setBoolean(entity, true);
                note("移除 " + what + "：置死标志 " + h.deadFlag.getName() + " = true");
                return;
            } catch (Throwable t) {
                AgentMain.log("dead flag failed: " + cause(t));
            }
        }
        int n = h.removeFromRegistries(entity);
        note("移除 " + what + "：没有可用的游戏口，直接从 " + n + " 张登记表摘掉");
    }

    /**
     * 修改：位移。走 {@link Handles#applyPosition}——坐标、前置三元组、碰撞盒、EntityMap 格子四件事一起做，
     * 少做任何一件，下一 tick 游戏就会把它按自己的账本改回去（真机 0223 上的"改完反弹"）。
     * 另外 1.5 秒后回查一次并写日志：反弹是"写完还在、过一会儿没了"，只看写完那一刻是查不出来的。
     */
    public void moveTo(final Object entity, final double x, final double y, final double z) {
        pump.submit(new Runnable() {
            public void run() {
                String how = h.applyPosition(entity, x, y, z, true);
                double[] back = GameBinder.readTriple(h.posTriple, entity);
                note("已把 " + simpleName(entity) + " 写到 " + TRIM.format(x) + ", " + TRIM.format(y) + ", "
                        + TRIM.format(z) + "（" + how + "；立刻回读 " + TRIM.format(back[0]) + ", "
                        + TRIM.format(back[1]) + ", " + TRIM.format(back[2]) + "）");
                lastMoveTarget.put(entity, new double[] { x, y, z });
                recheckMove(entity, x, y, z);
            }
        });
    }

    /** 延时回查：位置还守不守得住（守住 = 碰撞盒和格子都搬对了）。 */
    private void recheckMove(final Object entity, final double x, final double y, final double z) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(1500L);
                } catch (InterruptedException e) {
                    return;
                }
                synchronized (lastMoveTarget) {
                    double[] want = lastMoveTarget.get(entity);
                    if (want != null && (want[0] != x || want[1] != y || want[2] != z)) {
                        return; // 中途又下过一次移动指令，这一轮的目标已经不作数了
                    }
                    lastMoveTarget.remove(entity);
                }
                double[] p = GameBinder.readTriple(h.posTriple, entity);
                boolean stuck = near(p[0], x) && near(p[1], y) && near(p[2], z);
                AgentMain.log("移动回查（1.5 秒后）：" + simpleName(entity) + " = " + TRIM.format(p[0]) + ", "
                        + TRIM.format(p[1]) + ", " + TRIM.format(p[2]) + " -> "
                        + (stuck ? "守住了" : "被游戏改回去了（目标 " + TRIM.format(x) + ", " + TRIM.format(y) + ", "
                                + TRIM.format(z) + "）"));
            }
        }, "entity-hack-move-recheck");
        t.setDaemon(true);
        t.start();
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 0.75D;
    }

    public void setMotion(final Object entity, final double mx, final double my, final double mz) {
        pump.submit(new Runnable() {
            public void run() {
                GameBinder.writeTriple(h.motionTriple, entity, mx, my, mz);
                note("已把 " + simpleName(entity) + " 的动量写成 " + TRIM.format(mx) + ", " + TRIM.format(my)
                        + ", " + TRIM.format(mz) + "（字段 " + fieldNames(h.motionTriple) + "）");
            }
        });
    }

    private static String fieldNames(Field[] triple) {
        if (triple == null) {
            return "未定界";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < triple.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(triple[i].getName());
        }
        return sb.toString();
    }

    public void setDead(final Object entity, final boolean dead) {
        if (h.deadFlag == null) {
            note("没有识别出死亡标志字段，无法直接置死");
            return;
        }
        pump.submit(new Runnable() {
            public void run() {
                try {
                    h.deadFlag.setAccessible(true);
                    h.deadFlag.setBoolean(entity, dead);
                    note(simpleName(entity) + "." + h.deadFlag.getName() + " = " + dead);
                } catch (Throwable t) {
                    note("置死失败：" + cause(t));
                }
            }
        });
    }

    /** 通用字段修改（"修改"能力的兜底：血量、纹理串、年龄、动量之外的任何标量）。 */
    public void setField(final Object entity, final String fieldName, final String text) {
        pump.submit(new Runnable() {
            public void run() {
                Field f = findField(entity.getClass(), fieldName);
                if (f == null) {
                    note("改字段失败：字段 " + fieldName + " 不存在");
                    return;
                }
                try {
                    f.setAccessible(true);
                    Object old = f.get(entity);
                    applyValue(f, entity, text);
                    note("已写 " + simpleName(entity) + "." + fieldName + " : " + f.getType().getSimpleName()
                            + " = " + text + "（原值 " + old + "）");
                } catch (Throwable t) {
                    note("写 " + fieldName + " 失败：" + cause(t));
                }
            }
        });
    }

    private static void applyValue(Field f, Object owner, String text) throws Exception {
        Class<?> t = f.getType();
        String v = text == null ? "" : text.trim();
        if (t == int.class) {
            f.setInt(owner, Integer.parseInt(v));
        } else if (t == short.class) {
            f.setShort(owner, Short.parseShort(v));
        } else if (t == long.class) {
            f.setLong(owner, Long.parseLong(v));
        } else if (t == float.class) {
            f.setFloat(owner, Float.parseFloat(v));
        } else if (t == double.class) {
            f.setDouble(owner, Double.parseDouble(v));
        } else if (t == boolean.class) {
            f.setBoolean(owner, Boolean.parseBoolean(v));
        } else if (t == String.class) {
            f.set(owner, v);
        } else {
            throw new IllegalStateException("不支持的字段类型 " + t.getName());
        }
    }

    /** 实体上可读写的标量字段清单（含父类；跳过 static 与不支持的类型）。 */
    public List<FieldInfo> fields(Object entity) {
        List<FieldInfo> out = new ArrayList<FieldInfo>();
        for (Class<?> c = entity.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            Field[] fs;
            try {
                fs = c.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }
            for (int i = 0; i < fs.length; i++) {
                Field f = fs[i];
                if (Modifier.isStatic(f.getModifiers()) || !editable(f.getType())) {
                    continue;
                }
                out.add(new FieldInfo(f, c, GameShape.read(f, entity)));
            }
        }
        return out;
    }

    private static boolean editable(Class<?> t) {
        return t == int.class || t == short.class || t == long.class || t == float.class || t == double.class
                || t == boolean.class || t == String.class || t == byte.class;
    }

    private static Field findField(Class<?> from, String name) {
        for (Class<?> c = from; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    /** 实体的类型 id（0413+ 走 EntityList.getEntityString；否则退化成类名）。 */
    public String idOf(Object entity) {
        if (h.getEntityStringMethod != null) {
            try {
                h.getEntityStringMethod.setAccessible(true);
                Object v = h.getEntityStringMethod.invoke(null, entity);
                if (v != null) {
                    return String.valueOf(v);
                }
            } catch (Throwable ignored) {
            }
        }
        return simpleName(entity);
    }

    public double[] position(Object entity) {
        return GameBinder.readTriple(h.posTriple, entity);
    }

    /** 新增/劫持类型 id：走 EntityList 的 addMapping（0413 实测 private static void a(Class,String)）。 */
    public void registerId(final Class<?> type, final String id) {
        pump.submit(new Runnable() {
            public void run() {
                if (h.addMappingMethod != null) {
                    try {
                        h.addMappingMethod.setAccessible(true);
                        h.addMappingMethod.invoke(null, type, id);
                        note("addMapping(" + type.getName() + ", \"" + id + "\") 完成");
                        return;
                    } catch (Throwable t) {
                        AgentMain.log("addMapping failed: " + cause(t));
                    }
                }
                if (writeMaps(type, id)) {
                    note("直接写双 Map 完成 " + id);
                    return;
                }
                note("本版本没有类型注册表：见\"状态\"页签的 EntityList=none（"
                        + "Indev 的 LevelLoader 是硬编码 if 链，要改得 retransform，不在本工具范围）");
            }
        });
    }

    /** 解除注册（从两个 Map 里摘掉），用于撤销劫持。 */
    public void unregisterId(final String id) {
        pump.submit(new Runnable() {
            public void run() {
                java.util.Map<Object, Object> s2c = readMap(h.stringToClassMap);
                java.util.Map<Object, Object> c2s = readMap(h.classToStringMap);
                if (s2c == null || c2s == null) {
                    note("本版本没有类型注册表，无需解除");
                    return;
                }
                Object type = s2c.remove(id);
                if (type != null) {
                    c2s.remove(type);
                }
                note("unregister \"" + id + "\" -> " + type);
            }
        });
    }

    private boolean writeMaps(Class<?> type, String id) {
        java.util.Map<Object, Object> s2c = readMap(h.stringToClassMap);
        java.util.Map<Object, Object> c2s = readMap(h.classToStringMap);
        if (s2c == null || c2s == null) {
            return false;
        }
        s2c.put(id, type);
        c2s.put(type, id);
        return true;
    }

    /**
     * 当前注册表快照：id -&gt; 类名。
     *
     * <p>这张表是游戏进程里的<b>普通 {@code HashMap}</b>（{@code EntityList.entityClassToNameMap}
     * 那两张静态表），游戏线程在载入世界的过程中一直往里加东西，而我们是另一条线程在读它。
     * 遍历中途撞上写入就会抛 {@code ConcurrentModificationException}——真机（0413 那一族有注册表的版本）
     * 同样会撞，所以这里吞掉异常、把已经读到的半张表照实用出去，下一轮刷新（1.2 秒后）再读一遍。
     */
    public List<String[]> registryIds() {
        java.util.Map<Object, Object> s2c = readMap(h.stringToClassMap);
        List<String[]> out = new ArrayList<String[]>();
        if (s2c == null) {
            return out;
        }
        try {
            Iterator<Object> it = s2c.keySet().iterator();
            while (it.hasNext()) {
                Object k = it.next();
                Object v = s2c.get(k);
                out.add(new String[] { String.valueOf(k), v == null ? "null" : ((Class<?>) v).getName() });
            }
        } catch (Throwable concurrentWrite) {
            // 见上文：读到一半被写了，就先把这一半交出去
        }
        return out;
    }

    private static java.util.Map<Object, Object> readMap(Field f) {
        if (f == null) {
            return null;
        }
        Object v = GameShape.read(f, null);
        return v instanceof java.util.Map ? (java.util.Map<Object, Object>) v : null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String simpleName(Object e) {
        String n = e.getClass().getName();
        return n.substring(n.lastIndexOf('.') + 1);
    }

    private static String cause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }

    /** 类型清单项。 */
    public static final class TypeInfo {
        public final Class<?> type;
        public final String name;
        public final boolean registered;

        TypeInfo(Class<?> type, String name, boolean registered) {
            this.type = type;
            this.name = name;
            this.registered = registered;
        }

        /**
         * 一律带类全名：混淆包里 {@code a.c.a.a} 与 {@code client.a} 的末段都叫 {@code a}，
         * 下拉框里只写末段的话用户分不清自己选的是哪一个（真机 0227 就撞上过）。
         */
        public String display() {
            return registered ? name + "  [" + type.getName() + "]" : type.getName() + " (未注册)";
        }
    }

    /** 字段清单项。 */
    public static final class FieldInfo {
        public final Field field;
        public final Class<?> declaring;
        public final Object value;

        FieldInfo(Field field, Class<?> declaring, Object value) {
            this.field = field;
            this.declaring = declaring;
            this.value = value;
        }

        public String name() {
            return field.getName();
        }

        public String type() {
            return field.getType().getSimpleName();
        }

        public String valueText() {
            return value == null ? "null" : String.valueOf(value);
        }
    }
}
