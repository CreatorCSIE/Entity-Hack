package org.github.creatorcsie.entityhack.agent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * 现场定界：把一个运行中的 Indev/Infdev 世界绑成一组可用的反射句柄（{@link Handles}）。
 * 全程不写死混淆字母（0223 的 World 是 a.a.g、0227 是 a.a.e、0313 是 a.a.f、0413 又是 a.a.g），
 * 只用形状判据 + 运行期"探针实验"分类方法语义。
 *
 * 探针实验（必须在游戏线程执行，由 TickPump 排队）：
 * 造一个一次性实体，挨个调用 World 上"单实体参数的 void 方法"，观察两件事——
 *   1. 它是否出现在实体登记表里        -> 出现=生成口，消失=立即摘除口
 *   2. 它是否让实体的某个 boolean 翻成 true -> 该 boolean 就是死亡标志，该方法就是置死口
 * 语义靠可观测结果确定，不靠猜名字。
 */
public final class GameBinder {

    public interface Log {
        void log(String line);
    }

    private GameBinder() {
    }

    /** 结构性绑定（只读反射，安全）；探针语义分类另由 {@link #verifyCapabilities} 完成。 */
    public static Handles bind(Object root, List<Class<?>> gameClasses, Log log) throws Exception {
        List<Object> pool = GameFinder.reachable(root, 3);
        Handles h = new Handles();

        // 1) 找一个实体实例（优先玩家：挂在 Minecraft/World 的实体类型字段上）
        Object player = null;
        Field playerOwnerField = null;
        Object playerOwner = null;
        for (Object o : pool) {
            for (Field f : GameShape.allFields(o.getClass())) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType().isArray()) {
                    continue;
                }
                Object v = GameShape.read(f, o);
                if (v == null || v == o || !GameShape.isEntityLike(v.getClass())) {
                    continue;
                }
                int score = o.getClass().getName().toLowerCase().contains("minecraft") ? 2 : 1;
                int best = playerOwner == null ? 0 : (playerOwner.getClass().getName().toLowerCase().contains("minecraft") ? 2 : 1);
                if (player == null || score > best) {
                    player = v;
                    playerOwnerField = f;
                    playerOwner = o;
                }
            }
        }
        if (player == null) {
            // 真机 0223 实测：玩家字段（{@code client.d.f} / {@code World.y}）在刚进世界的那一刻常常还是
            // null，而 mob 从一开始就<b>只</b>存在于登记表里（{@code World.r : a.a.i} 的 8192 格 {@code List[]}
            // 与 {@code List e}）。所以"字段值里没有实体"不等于"场上没有实体"，必须钻进容器里再找一遍。
            player = firstEntityUnder(root, 0);
            if (player == null) {
                for (Object o : pool) {
                    player = firstEntityUnder(o, 0);
                    if (player != null) {
                        break;
                    }
                }
            }
        }
        if (player != null) {
            h.minecraft = playerOwner;
            if (playerOwner != null) {
                h.minecraftClass = playerOwner.getClass();
            }
            if (playerOwnerField != null) {
                log.log("entity field: " + h.minecraftClass.getName() + "." + playerOwnerField.getName()
                        + " = " + player.getClass().getName());
            }
        } else {
            // 场上一个实体都没有（刚载入 / 全死光）：只能靠类信息把 World 的类型反推出来
            Object[] wf = findWorldInstance(pool, gameClasses, log);
            if (wf == null) {
                throw new IllegalStateException("在 " + root.getClass().getName()
                        + " 的可达对象里找不到实体，也找不到世界（游戏可能还没进入世界）");
            }
            h.world = wf[0];
            h.worldField = (Field) wf[1];
            h.worldClass = h.world.getClass();
            h.minecraft = wf[2];
            h.minecraftClass = h.minecraft.getClass();
            log.log("world field (无实体兜底): " + h.minecraftClass.getName() + "." + h.worldField.getName()
                    + " = " + h.worldClass.getName());
            player = firstEntityUnder(h.world, 0);
            if (player == null) {
                throw new IllegalStateException("世界 " + h.worldClass.getName()
                        + " 上没有非空实体字段，也没有在表实体，无法确定数值三元组");
            }
        }

        // 2) 实体基类 = 继承链上"最高的、自己连排声明了数值三元组"的类
        h.entityBase = GameShape.findEntityBase(player.getClass());
        if (h.entityBase == null) {
            throw new IllegalStateException(player.getClass().getName() + " 的继承链里找不到实体基类");
        }
        h.aabbField = GameShape.findAabbField(h.entityBase, true);

        // 3) World = <b>这个实体实例身上</b>"世界形状"字段的值（老 MC 没有静态单例，只能从实体反抓）。
        // 真机 0223：{@code a.c.b.d : a.a.g} —— 一个类里只有一个这种字段，所以没有任何歧义。
        // 绝不能用"哪个对象的形状像世界 + 方法多"来选（见 findWorldInstance 的注释）。
        if (h.world == null) {
            Field worldField = GameShape.findWorldField(h.entityBase);
            if (worldField == null) {
                throw new IllegalStateException(player.getClass().getName() + " 的继承链里没有指向 World 的字段");
            }
            worldField.setAccessible(true);
            Object world = worldField.get(player);
            if (world == null) {
                throw new IllegalStateException("实体字段 " + worldField.getName() + " 当前为 null");
            }
            if (!GameShape.isWorldShape(world.getClass())) {
                throw new IllegalStateException("实体字段 " + worldField.getName() + " 指向的 "
                        + world.getClass().getName() + " 不是世界形状");
            }
            h.world = world;
            h.worldField = worldField;
            h.worldClass = world.getClass();
        }
        if (h.minecraft == null) {
            h.minecraft = findGameHolder(pool, h.worldClass, h.world);
            if (h.minecraft != null) {
                h.minecraftClass = h.minecraft.getClass();
                log.log("game holder: " + h.minecraftClass.getName() + "（TickPump 认游戏线程用）");
            }
        }

        // 4) World 上的 playerEntity（0223 没有这个字段，改用"无参返回实体的方法"兜底）
        for (Field f : GameShape.allFields(h.worldClass)) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) {
                continue;
            }
            if (h.entityBase.isAssignableFrom(f.getType()) && GameShape.read(f, h.world) == player) {
                h.playerField = f;
                break;
            }
        }

        // 5) IWorldAccess 接口 + addAccess：这是"借游戏线程"的入口（不需要 ASM/retransform）
        h.accessInterface = GameShape.findAccessInterface(h.worldClass, h.entityBase);
        if (h.accessInterface != null) {
            List<Method> add = GameShape.entityParamVoidMethods(h.worldClass, h.accessInterface);
            h.addAccessMethod = add.isEmpty() ? null : add.get(0);
        }

        // 6) 数值三元组：[prevPos, pos, motion]（0223/0413 可读源与 javap 三方吻合），用幅度分辨
        resolveTriples(h, player, log);

        // 7) 类型注册表（0413+ 的 EntityList：双 static Map + createEntityFromNBT + getEntityString）
        bindEntityList(h, gameClasses, log);

        log.log("binding ok:\n" + h.describe());
        return h;
    }

    /**
     * 彻底没有实体时的兜底：只用类信息把 World 的<b>声明类型</b>反推出来（实体基类上那个世界字段的类型），
     * 再在可达对象图里做"精确类型匹配"。
     *
     * <p>为什么不再打分选优（真机 0223 的实测教训）：{@code net.minecraft.client.a.b}（RenderEngine）
     * 同样满足 {@link GameShape#isWorldShape}——它有一个 {@code List} 字段（worldAccesses），
     * 方法数还比真正的 World（{@code a.a.g}）多。一旦按"形状 + 方法数"选优就会选中渲染引擎，
     * 于是登记表永远是空的、生成口/探针全落在它身上，而窗口标题看起来却一切正常。
     * 类型是从实体身上反推的，唯一；匹配不上就返回 null，交给下一轮轮询，绝不猜。
     *
     * @return {world 实例, world 字段, 持有者} 或 null
     */
    private static Object[] findWorldInstance(List<Object> pool, List<Class<?>> gameClasses, Log log) {
        Class<?> guessed = guessedWorldClass(gameClasses);
        if (guessed == null) {
            return null;
        }
        for (Object o : pool) {
            for (Field f : GameShape.allFields(o.getClass())) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType().isArray()) {
                    continue;
                }
                Object v = GameShape.read(f, o);
                if (v != null && v != o && v.getClass() == guessed) {
                    return new Object[] { v, f, o };
                }
            }
        }
        log.log("按类型 " + guessed.getName() + " 找 World，可达对象里没有实例（等下一轮）");
        return null;
    }

    /** 只用类信息（不需要实例）反推 World 的声明类型：实体基类上的世界字段。 */
    private static Class<?> guessedWorldClass(List<Class<?>> gameClasses) {
        if (gameClasses == null || gameClasses.isEmpty()) {
            return null;
        }
        Class<?> base = GameShape.rankEntityBase(gameClasses);
        if (base == null) {
            return null;
        }
        Field wf = GameShape.findWorldField(base);
        if (wf == null) {
            return null;
        }
        return wf.getType();
    }

    /**
     * 游戏主循环实例：{@code new Thread(this)} 的那个 {@code Runnable}（TickPump 靠
     * {@code Thread.target == handles.minecraft} 认出游戏线程）。优先挑"手里攥着 World"的那个。
     */
    private static Object findGameHolder(List<Object> pool, Class<?> worldClass, Object world) {
        Object fallback = null;
        for (Object o : pool) {
            if (!(o instanceof Runnable) || o instanceof Thread || GameShape.isJdkClass(o.getClass())) {
                continue;
            }
            if (worldClass != null && worldClass.isAssignableFrom(o.getClass())) {
                continue; // World 自己一般也是 Runnable
            }
            if (GameShape.isEntityLike(o.getClass())) {
                continue;
            }
            boolean holdsWorld = false;
            for (Field f : GameShape.allFields(o.getClass())) {
                if (GameShape.read(f, o) == world) {
                    holdsWorld = true;
                    break;
                }
            }
            if (holdsWorld) {
                return o;
            }
            if (fallback == null && name(o).toLowerCase().contains("minecraft")) {
                fallback = o;
            }
        }
        return fallback;
    }

    private static String name(Object o) {
        return o == null ? "" : o.getClass().getName();
    }

    /**
     * 从这个对象往下找第一个实体：实体类型字段 → {@code List} 元素 → {@code List[]} 格子里的
     * {@code List} 元素 → 内含 {@code List[]} 的辅助对象（EntityMap）。深度上限 2 层，拿到就返回。
     */
    private static Object firstEntityUnder(Object o, int depth) {
        if (o == null || depth > 2) {
            return null;
        }
        for (Field f : GameShape.allFields(o.getClass())) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) {
                continue;
            }
            Class<?> t = f.getType();
            Object v = GameShape.read(f, o);
            if (v == null || v == o || Proxy.isProxyClass(v.getClass())) {
                continue;
            }
            if (!t.isPrimitive() && GameShape.isEntityLike(v.getClass())) {
                return v;
            }
            Object e = null;
            if (v instanceof List) {
                e = firstEntityIn((List<?>) v, o);
            } else if (v instanceof List[]) {
                List[] cells = (List[]) v;
                for (int i = 0; i < cells.length && e == null; i++) {
                    if (cells[i] != null) {
                        e = firstEntityIn(cells[i], o);
                    }
                }
            } else if (!t.isArray() && GameShape.declaresListArray(t)) {
                e = firstEntityUnder(v, depth + 1);
            }
            if (e != null) {
                return e;
            }
        }
        return null;
    }

    private static Object firstEntityIn(List<?> list, Object owner) {
        int size = list.size();
        for (int i = 0; i < size; i++) {
            Object e;
            try {
                e = list.get(i);
            } catch (Throwable ignored) {
                return null; // 游戏线程正在改这张表，别硬读
            }
            if (e != null && e != owner && GameShape.isEntityLike(e.getClass())) {
                return e;
            }
        }
        return null;
    }

    /**
     * 探针实验：确定生成口 / 立即摘除口 / 置死口 + 死亡标志字段。
     * 必须在游戏线程调用（TickPump 排队），因为会真的往登记表里塞一个临时实体。
     */
    public static void verifyCapabilities(Handles h, List<Class<?>> gameClasses, Log log) {
        List<Method> candidates = allEntityOpsCandidates(h.worldClass, h.entityBase);
        if (candidates.isEmpty()) {
            log.log("World 上没有任何单实体参数的方法（0227/0313 死壳期）：只能直写/直删登记表");
            return;
        }
        Object probe = newProbe(h, gameClasses, log);
        if (probe == null) {
            log.log("找不到可构造的实体探针（没有任何 (World) 构造器的具体子类），语义分类退化为按声明顺序猜测");
            guessFallback(h, candidates, log);
            return;
        }
        try {
            placeAtPlayer(h, probe);
            // getDeclaredMethods() 的顺序是不保证的：可能先轮到"摘除口"，而那时探针还不在登记表里，
            // 看不出任何效果。所以最多跑两遍——第一遍拿到生成口以后，第二遍先用它把探针放回表里再测剩下的。
            for (int pass = 0; pass < 2; pass++) {
                int classified = classified(h);
                for (Method m : candidates) {
                    if (m == h.spawnMethod || m == h.hardRemoveMethod || m == h.deadMarkMethod) {
                        continue;
                    }
                    if (pass > 0 && !h.contains(probe) && !place(h, probe)) {
                        continue;
                    }
                    boolean before = h.contains(probe);
                    boolean[] boolBefore = snapshotBooleans(h, probe);
                    try {
                        m.setAccessible(true);
                        m.invoke(Modifier.isStatic(m.getModifiers()) ? null : h.world, probe);
                    } catch (Throwable t) {
                        log.log("probe " + m.getName() + " threw " + root(t));
                        continue;
                    }
                    boolean after = h.contains(probe);
                    boolean[] boolAfter = snapshotBooleans(h, probe);
                    Field flipped = diffBoolean(h, probe, boolBefore, boolAfter);
                    if (!before && after) {
                        h.spawnMethod = m;
                        log.log("spawn  <- " + m.getName());
                    } else if (before && !after) {
                        h.hardRemoveMethod = m;
                        log.log("hardRemove <- " + m.getName());
                    } else if (flipped != null && h.deadFlag == null) {
                        h.deadFlag = flipped;
                        h.deadMarkMethod = m;
                        log.log("deadMark <- " + m.getName() + " (flag " + flipped.getName() + ")");
                    }
                }
                if (classified(h) == classified) {
                    break; // 这一遍没有任何新收获，不用再跑
                }
            }
            if (h.spawnMethod == null) {
                log.log("警告：探针实验没能确定生成口，登记表可能不在 World 的字段上");
            }
        } finally {
            // 善后：直接摘干净，避免探针实体真的活在游戏里（也避开一次 onUpdate）
            int n = h.removeFromRegistries(probe);
            log.log("probe cleanup: removed from " + n + " registries");
        }
    }

    private static int classified(Handles h) {
        int n = 0;
        if (h.spawnMethod != null) {
            n++;
        }
        if (h.hardRemoveMethod != null) {
            n++;
        }
        if (h.deadMarkMethod != null) {
            n++;
        }
        return n;
    }

    /** 用已经认出来的生成口把探针放回登记表（只在它确实不在表里时调用，避免登记表出现重复项）。 */
    private static boolean place(Handles h, Object probe) {
        if (h.spawnMethod == null) {
            return false;
        }
        try {
            h.spawnMethod.setAccessible(true);
            h.spawnMethod.invoke(Modifier.isStatic(h.spawnMethod.getModifiers()) ? null : h.world, probe);
            return h.contains(probe);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 生成口缺失时的兜底：按"实例方法在前、静态方法置死"的实测规律猜。 */
    private static void guessFallback(Handles h, List<Method> candidates, Log log) {
        for (Method m : candidates) {
            if (!Modifier.isStatic(m.getModifiers()) && h.spawnMethod == null) {
                h.spawnMethod = m;
            } else if (Modifier.isStatic(m.getModifiers()) && h.deadMarkMethod == null) {
                h.deadMarkMethod = m;
            }
        }
        log.log("guess fallback: spawn=" + h.spawnMethod + " deadMark=" + h.deadMarkMethod);
    }

    private static List<Method> allEntityOpsCandidates(Class<?> worldClass, Class<?> entityBase) {
        List<Method> out = new ArrayList<Method>();
        for (Class<?> c = worldClass; c != null && c != Object.class; c = c.getSuperclass()) {
            List<Method> ms = GameShape.entityOpsCandidates(c, entityBase);
            for (int i = 0; i < ms.size(); i++) {
                out.add(ms.get(i));
            }
        }
        return out;
    }

    /**
     * 探针实体：只从"可安全生成"的清单里挑（探针会真的被生成口放进登记表、活到善后那一刻，
     * 期间渲染线程每帧都要画它——挑中 0223 的 EntityItem 就等于当场把游戏主线程打 NPE）。
     */
    private static Object newProbe(Handles h, List<Class<?>> gameClasses, Log log) {
        List<Class<?>> subs = renderSafeEntitySubclasses(h, gameClasses, null);
        for (int i = 0; i < subs.size(); i++) {
            try {
                java.lang.reflect.Constructor<?> ctor = subs.get(i).getDeclaredConstructor(h.worldClass);
                ctor.setAccessible(true);
                Object e = ctor.newInstance(h.world);
                log.log("probe class: " + subs.get(i).getName());
                return e;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * 数值三元组分辨。可读源 + javap 实测：Indev 0223 的 {@code Entity.java:24/27/30} 与
     * Infdev 0413 的 {@code Entity.java:26/31/36} 都是 <b>prevPos → posX → motionX</b> 连着声明的，
     * 后面才是旋转角（0223 全是 float，所以游程能一直连到 {@code prevRotationPitch}）。
     *
     * <p>三组齐全时<b>直接按声明顺序拿</b>，不要拿数值大小猜：刚捏出来、还没走过一 tick 的实体
     * {@code prevPos} 正好是 0，比 {@code motion} 还小，猜大小会把 prevPos 当成 motion，
     * 于是 {@code prevTriple} 落空——写位置时不一起写 prevPos，渲染插值就会从旧位置拉回来。
     *
     * <p>只剩两组（或一组）时才退回幅度判据：motion 幅度远小于坐标（老 MC 坐标可达 ±32000，
     * 速度 &lt; 1.6）。
     */
    private static void resolveTriples(Handles h, Object player, Log log) {
        List<Field[]> runs = GameShape.numericRuns(h.entityBase);
        List<Field[]> triples = new ArrayList<Field[]>();
        int longest = 0;
        Field[] longestRun = null;
        for (int i = 0; i < runs.size(); i++) {
            if (runs.get(i).length > longest) {
                longest = runs.get(i).length;
                longestRun = runs.get(i);
            }
        }
        if (longestRun == null) {
            return;
        }
        for (int i = 0; i + 3 <= longestRun.length; i += 3) {
            Field[] t = new Field[] { longestRun[i], longestRun[i + 1], longestRun[i + 2] };
            triples.add(t);
        }
        if (triples.isEmpty()) {
            return;
        }
        double[] means = new double[triples.size()];
        for (int i = 0; i < triples.size(); i++) {
            means[i] = meanAbs(triples.get(i), player);
        }
        int min = 0;
        int max = 0;
        for (int i = 1; i < means.length; i++) {
            if (means[i] > means[max]) {
                max = i;
            }
            // 取"最后一个最小值"：静止的玩家 prev/pos 幅度相同，第一个最小值会误落到 prevPos 上
            if (means[i] <= means[min]) {
                min = i;
            }
        }
        int motionIdx;
        int posIdx;
        boolean separable = means.length >= 2 && means[max] > means[min] * 4.0 + 1.0;
        if (triples.size() >= 3) {
            posIdx = 1;
            motionIdx = 2;
        } else if (separable) {
            motionIdx = min;
            posIdx = motionIdx > 0 ? motionIdx - 1 : max;
        } else {
            posIdx = 0;
            motionIdx = means.length == 2 ? 1 : -1;
        }
        h.posTriple = triples.get(posIdx);
        h.motionTriple = motionIdx >= 0 ? triples.get(motionIdx) : null;
        // prevPos = pos 前面那一组（写位置时一起写，避免插值回弹）
        if (posIdx > 0) {
            Field[] prev = triples.get(posIdx - 1);
            h.prevTriple = prev;
        }
        log.log("triples: pos=" + names(h.posTriple) + " prev=" + names(h.prevTriple) + " motion=" + names(h.motionTriple)
                + " (声明顺序=" + (triples.size() >= 3 ? "是" : "组数不够，按幅度猜")
                + ", means=" + fmt(means) + ", separable=" + separable + ")");
    }

    private static String fmt(double[] means) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < means.length; i++) {
            sb.append(i > 0 ? "/" : "").append((long) means[i]);
        }
        return sb.toString();
    }

    private static String names(Field[] fs) {
        if (fs == null) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fs.length; i++) {
            sb.append(i > 0 ? "," : "").append(fs[i].getName());
        }
        return sb.toString();
    }

    private static double meanAbs(Field[] fs, Object owner) {
        double sum = 0;
        for (int i = 0; i < fs.length; i++) {
            sum += Math.abs(readNumber(fs[i], owner));
        }
        return sum / fs.length;
    }

    private static void bindEntityList(Handles h, List<Class<?>> gameClasses, Log log) {
        for (Class<?> c : gameClasses) {
            if (c == h.entityBase || c.isInterface() || Modifier.isAbstract(c.getModifiers())) {
                continue;
            }
            if (!GameShape.isEntityListLike(c, h.entityBase, h.worldClass)) {
                continue;
            }
            h.entityListClass = c;
            Field[] maps = GameShape.staticMapFields(c);
            if (maps.length >= 2) {
                h.stringToClassMap = maps[0];
                h.classToStringMap = maps[1];
            }
            h.addMappingMethod = GameShape.findAddMapping(c);
            for (Method m : c.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                if (m.getReturnType() == String.class && m.getParameterTypes().length == 1
                        && h.entityBase.isAssignableFrom(m.getParameterTypes()[0])) {
                    h.getEntityStringMethod = m;
                }
                if (h.entityBase.isAssignableFrom(m.getReturnType()) && m.getParameterTypes().length == 2
                        && m.getParameterTypes()[1] == h.worldClass) {
                    h.createFromNbtMethod = m;
                }
            }
            log.log("EntityList-like: " + c.getName());
            return;
        }
        log.log("没有 EntityList 形态的类型注册表（0223 用 LevelLoader 的字符串工厂、0316 及更差不落盘）");
    }

    /** World 里当前在场的所有实体子类（有 (World) 构造器的具体类）。 */
    public static List<Class<?>> concreteEntitySubclasses(Handles h, List<Class<?>> gameClasses) {
        List<Class<?>> out = new ArrayList<Class<?>>();
        for (Class<?> c : gameClasses) {
            if (c == h.entityBase || c.isInterface() || !h.entityBase.isAssignableFrom(c)) {
                continue;
            }
            if (Modifier.isAbstract(c.getModifiers())) {
                continue;
            }
            try {
                c.getDeclaredConstructor(h.worldClass);
                out.add(c);
            } catch (Throwable ignored) {
                // 0413/0414 的七个 mob 叶类就在这里被排除（构造器整体剥除）
            }
        }
        return out;
    }

    /** 已经报过过滤原因的世界（清单每 1.2 秒刷一次，同一个结论只说一遍）。 */
    private static final java.util.Set<Class<?>> FILTER_REPORTED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<Class<?>>());

    /**
     * 能<b>安全生成</b>的类型（生成口、探针都只从这里挑）。
     *
     * @param dropped 非 null 时收集"这个类型为什么被踢掉"（界面"类型"页要照实说，
     *                否则用户只会问"为什么列表里没有掉落物"）
     */
    public static List<Class<?>> renderSafeEntitySubclasses(Handles h, List<Class<?>> gameClasses,
            List<String> dropped) {
        List<Class<?>> subs = concreteEntitySubclasses(h, gameClasses);
        Object player = null;
        try {
            player = currentPlayer(h);
        } catch (Throwable ignored) {
        }
        List<Class<?>> out = new ArrayList<Class<?>>();
        for (int i = 0; i < subs.size(); i++) {
            Class<?> c = subs.get(i);
            String why = whyNotSpawnable(h, c, subs, player);
            if (why == null) {
                out.add(c);
            } else if (dropped != null) {
                dropped.add(fullName(c) + "：" + why);
            }
        }
        if (dropped != null) {
            reportUnconstructable(h, gameClasses, subs, dropped);
        }
        return out;
    }

    /** 一条过滤说明里最多列几个类名，多了只报总数（"类型"页与日志都要能看完）。 */
    private static final int NAMED_PER_BUCKET = 6;

    /**
     * "扫得到、但反射生不成"的实体子类也要照实说。真机 in-20100227 就是缺这一段：
     * 清单只剩 {@code net.minecraft.a.c.a.e}（EntityMob 基类），用户以为工具没扫到怪。
     * javap 那个 jar 得到的事实是两类：
     * <ol>
     *   <li><b>死壳类</b>——{@code a.c.a.a/b/c/d/f}（5 只怪）与 {@code a.c.b.b/c}（2 只被动怪）
     *       在字节码里<b>一个构造器都没有</b>，只剩 {@code e()/b()/f()} 这些方法：
     *       ProGuard 把没人调用的 {@code <init>} 整条删了（那几年 mob 本来就被移除了）。
     *       可读源里那些 {@code public EntityPig(World)} 是 RetroMCP 反编译时补出来的，<b>不能当作依据</b>。
     *       {@code Unsafe.allocateInstance} 那种绕法只会造出 {@code id}/碰撞盒/纹理全空的壳，
     *       下一帧就是上轮那种把游戏打死的 NPE，所以不做。</li>
     *   <li><b>签名不合</b>——构造器存在，但不是单个 {@code (World)}，
     *       例如 {@code a.c.c.a(World, float, float, float)}、{@code client.f.j(World, double ×6)}。
     *       本工具不会替这些参数编值（坐标之外的参数一填错就是渲染器 NPE），所以照实列出签名。</li>
     * </ol>
     */
    private static void reportUnconstructable(Handles h, List<Class<?>> pool, List<Class<?>> withWorldCtor,
            List<String> dropped) {
        List<String> shells = new ArrayList<String>();
        List<String> shaped = new ArrayList<String>();
        for (int i = 0; i < pool.size(); i++) {
            Class<?> c = pool.get(i);
            if (c == h.entityBase || java.lang.reflect.Modifier.isAbstract(c.getModifiers())
                    || c.isInterface() || !h.entityBase.isAssignableFrom(c)
                    || withWorldCtor.contains(c)) {
                continue;
            }
            java.lang.reflect.Constructor<?>[] cs;
            try {
                cs = c.getDeclaredConstructors();
            } catch (Throwable t) {
                continue;
            }
            if (cs.length == 0) {
                shells.add(fullName(c));
            } else {
                shaped.add(fullName(c) + signature(cs));
            }
        }
        if (!shells.isEmpty()) {
            dropped.add("这个 jar 里连构造器都没有（ProGuard 把没人调用的 <init> 删了）的实体类 "
                    + shells.size() + " 个，反射进不去：" + join(shells));
        }
        if (!shaped.isEmpty()) {
            dropped.add("构造器不是单个 (World)、本工具不会自己编参数的实体类 " + shaped.size()
                    + " 个：" + join(shaped));
        }
    }

    /** {@code (net.minecraft.a.a.e, double, double, double, net.minecraft.a.b.k)} 这样的签名串。 */
    private static String signature(java.lang.reflect.Constructor<?>[] cs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cs.length; i++) {
            Class<?>[] ps = cs[i].getParameterTypes();
            StringBuilder args = new StringBuilder();
            for (int j = 0; j < ps.length; j++) {
                args.append(j > 0 ? ", " : "").append(ps[j].isPrimitive()
                        ? ps[j].getSimpleName() : ps[j].getName());
            }
            sb.append(i > 0 ? " / " : "").append('(').append(args).append(')');
        }
        return sb.toString();
    }

    /** 超过 {@link #NAMED_PER_BUCKET} 个就只报前几个 + 总数。 */
    private static String join(List<String> names) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(names.size(), NAMED_PER_BUCKET);
        for (int i = 0; i < n; i++) {
            sb.append(i > 0 ? "、" : "").append(names.get(i));
        }
        if (names.size() > n) {
            sb.append("…");
        }
        return sb.toString();
    }

    /**
     * 这个类型能不能往世界里塞。判据全部是形状 + 试构造的可观测结果，不写死混淆名。
     *
     * <p>真机 in-20100223 的实测教训（客户端 NPE、整个游戏没了）：{@code EntityItem}
     * 确实有 {@code (World)} 构造器，可读源 {@code EntityItem.java:33-35} 是
     * {@code public EntityItem(World world1) { super(world1); }}——它只填了坐标，
     * 渲染要的那个 {@code public ItemStack item}（同文件第 13 行）保持 null。
     * 而 {@code RenderManager.entityRenderMap} 里恰好写着 {@code EntityItem -> RenderItem}，
     * 于是我们一调用生成口，下一帧 {@code RenderItem.a()} 就解引用 null 的 item：
     * 栈顶 {@code net.minecraft.client.a.a.e.a}（RenderItem）正是用户贴回来的那一行。
     *
     * <p>放行条件（满足任意一条）：
     * <ol>
     *   <li>试构造出来的实例身上有一个<b>含 {@code .png} 的非空 String 字段</b>——
     *       Indev 的活物把纹理串写在自己身上（{@code EntityLiving.java:19}
     *       {@code protected String texture = "/char.png";}，各 mob 构造器覆写成
     *       {@code /mob/pig.png} 之类），掉落物／箭／TNT／画一个都没有；</li>
     *   <li>实体基类与它之间<b>还有中间基类</b>——Infdev 起纹理挪进了客户端的 Render 类
     *       （0413 的映射表里已经没有 {@code EntityLiving.texture}），但"活物有中间基类"这一点没变，
     *       而 {@code EntityItem}/{@code EntityArrow}/{@code EntityTNTPrimed}/{@code EntityPainting}
     *       全是 {@code Entity} 的<b>直接</b>子类。</li>
     * </ol>
     *
     * @return null = 放行；否则是不能生成的原因
     */
    private static String whyNotSpawnable(Handles h, Class<?> c, List<Class<?>> subs, Object player) {
        if (player != null && c.isAssignableFrom(player.getClass())) {
            return "这是玩家本体，再捏一个会把渲染和输入一起搞乱";
        }
        for (int i = 0; i < subs.size(); i++) {
            Class<?> d = subs.get(i);
            if (d != c && c.isAssignableFrom(d)) {
                return "有更具体的子类 " + fullName(d) + "，基类不直接生成";
            }
        }
        if (hasTextureString(h, c)) {
            return null;
        }
        if (c.getSuperclass() != null && c.getSuperclass() != h.entityBase) {
            return null;
        }
        return "直接继承实体基类、又没有纹理串：渲染它需要的数据没人注入（0223 的 EntityItem 就是这么崩的）";
    }

    /**
     * "这个类的实例身上有没有 .png 纹理串"的结论。一个类的纹理串是写死在字节码里的，
     * 跟世界无关，所以查一次就记住——清单每 1.2 秒刷一次，每次都真去构造一个一次性实体
     * 既慢又可能在旧世界上做无用功。
     */
    private static final java.util.Map<Class<?>, Boolean> TEXTURE_PROBED =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Class<?>, Boolean>());

    /** 试构造一个实例，看它身上有没有写着 {@code .png} 的纹理串（构造失败也当成"没有"）。 */
    private static boolean hasTextureString(Handles h, Class<?> c) {
        Boolean known = TEXTURE_PROBED.get(c);
        if (known != null) {
            return known.booleanValue();
        }
        boolean has = probeTextureString(h, c);
        TEXTURE_PROBED.put(c, Boolean.valueOf(has));
        return has;
    }

    private static boolean probeTextureString(Handles h, Class<?> c) {
        Object e;
        try {
            java.lang.reflect.Constructor<?> ctor = c.getDeclaredConstructor(h.worldClass);
            ctor.setAccessible(true);
            e = ctor.newInstance(h.world);
        } catch (Throwable t) {
            return false;
        }
        for (Field f : GameShape.allFields(e.getClass())) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) {
                continue;
            }
            Object v = GameShape.read(f, e);
            if (v != null && String.valueOf(v).contains(".png")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 过滤原因里要说全名：混淆包下 {@code net.minecraft.a.c.a} 和 {@code net.minecraft.client.a}
     * 的末段都叫 {@code a}，只报末段的话日志（真机 0227 就是）根本分不清被踢掉的是哪个类。
     */
    private static String fullName(Class<?> c) {
        return c.getName();
    }

    /** 第一次遇到这个世界的过滤结论时播报一次（之后的刷新只改界面文字，不再刷日志）。 */
    public static void reportFilterOnce(Handles h, List<String> dropped, Log log) {
        if (log == null || dropped == null || dropped.isEmpty() || !FILTER_REPORTED.add(h.worldClass)) {
            return;
        }
        StringBuilder sb = new StringBuilder("可生成清单过滤掉 ");
        for (int i = 0; i < dropped.size(); i++) {
            sb.append(i > 0 ? "；" : "").append(dropped.get(i));
        }
        log.log(sb.toString());
    }

    private static void placeAtPlayer(Handles h, Object probe) {
        try {
            Object player = currentPlayer(h);
            double x = 0;
            double y = 0;
            double z = 0;
            if (player != null && h.posTriple != null) {
                x = readNumber(h.posTriple[0], player) + 2;
                y = readNumber(h.posTriple[1], player);
                z = readNumber(h.posTriple[2], player) + 2;
            }
            // 探针也走"完整"的写坐标：碰撞盒不跟着搬的话，它一进登记表就会被游戏按盒子拽回原点
            h.applyPosition(probe, x, y, z, false);
        } catch (Throwable ignored) {
        }
    }

    public static Object currentPlayer(Handles h) {
        if (h.playerField != null) {
            Object p = GameShape.read(h.playerField, h.world);
            if (p != null) {
                return p;
            }
        }
        // 0223：World 上没有玩家字段，用"无参返回实体的方法"（javap 实测 public final a.c.b i()）
        try {
            for (Method m : h.worldClass.getDeclaredMethods()) {
                if (m.getParameterTypes().length == 0 && h.entityBase.isAssignableFrom(m.getReturnType())) {
                    m.setAccessible(true);
                    Object v = m.invoke(h.world);
                    if (v != null) {
                        return v;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 换关卡之后游戏 {@code new} 了一个全新的 World 重新赋给 Minecraft 实例上的那个字段，
     * 我们手上这个 {@code h.world} 就成了被丢弃的旧对象。这里返回"取代它的那个实例"，
     * 没换 / 认不出来就返回 null。
     *
     * <p>只认 {@code h.minecraft} 自己字段上<b>类型就是 World</b> 的那个值（和 {@link #findWorldInstance}
     * 同一条铁律：绝不用"形状像世界 + 方法多"打分选优，否则会把渲染引擎当成世界）。
     * 渲染引擎不在这个类的实例上，所以不会认错。
     *
     * @param ignore 上一个刚被换掉的 World：某些版本会短暂同时攥着新旧两个世界，不排除就会来回切
     */
    public static Object replacementWorld(Handles h, Object ignore) {
        if (h.minecraft == null || h.worldClass == null) {
            return null;
        }
        for (Field f : GameShape.allFields(h.minecraft.getClass())) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) {
                continue;
            }
            Object v = GameShape.read(f, h.minecraft);
            if (v != null && v != h.world && v != ignore && h.worldClass.isInstance(v)) {
                return v;
            }
        }
        return null;
    }

    /** 游戏实例上有没有一个声明为 World 类型的字段——没有就根本监视不了换关卡（别谎称在监视）。 */
    public static boolean canWatchWorld(Handles h) {
        if (h.minecraft == null || h.worldClass == null) {
            return false;
        }
        for (Field f : GameShape.allFields(h.minecraft.getClass())) {
            if (declaresWorld(h, f)) {
                return true;
            }
        }
        return false;
    }

    private static boolean declaresWorld(Handles h, Field f) {
        return !Modifier.isStatic(f.getModifiers()) && !f.getType().isPrimitive()
                && f.getType() != null && h.worldClass.isAssignableFrom(f.getType());
    }

    public static double readNumber(Field f, Object owner) {
        Object v = GameShape.read(f, owner);
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        return 0;
    }

    public static void writeTriple(Field[] triple, Object owner, double x, double y, double z) {
        if (triple == null) {
            return;
        }
        writeNumber(triple[0], owner, x);
        writeNumber(triple[1], owner, y);
        writeNumber(triple[2], owner, z);
    }

    public static void writeNumber(Field f, Object owner, double value) {
        try {
            f.setAccessible(true);
            if (f.getType() == float.class) {
                f.setFloat(owner, (float) value);
            } else if (f.getType() == double.class) {
                f.setDouble(owner, value);
            } else if (f.getType() == int.class) {
                f.setInt(owner, (int) value);
            } else if (f.getType() == short.class) {
                f.setShort(owner, (short) value);
            } else if (f.getType() == long.class) {
                f.setLong(owner, (long) value);
            }
        } catch (Throwable ignored) {
        }
    }

    public static double[] readTriple(Field[] triple, Object owner) {
        if (triple == null) {
            return new double[] { 0, 0, 0 };
        }
        return new double[] { readNumber(triple[0], owner), readNumber(triple[1], owner), readNumber(triple[2], owner) };
    }

    /** 实体上所有 boolean 实例字段（含父类）的当前值，用于"置死口"效果对比。 */
    private static boolean[] snapshotBooleans(Handles h, Object entity) {
        List<Field> bools = booleanFields(h.entityBase, entity);
        boolean[] out = new boolean[bools.size()];
        for (int i = 0; i < out.length; i++) {
            Object v = GameShape.read(bools.get(i), entity);
            out[i] = Boolean.TRUE.equals(v);
        }
        return out;
    }

    private static Field diffBoolean(Handles h, Object entity, boolean[] before, boolean[] after) {
        List<Field> bools = booleanFields(h.entityBase, entity);
        for (int i = 0; i < bools.size() && i < before.length && i < after.length; i++) {
            if (!before[i] && after[i]) {
                return bools.get(i);
            }
        }
        return null;
    }

    private static List<Field> booleanFields(Class<?> base, Object entity) {
        List<Field> out = new ArrayList<Field>();
        for (Class<?> c = entity.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                for (Field f : c.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers()) && f.getType() == boolean.class) {
                        out.add(f);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static String root(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getClass().getName() + ": " + c.getMessage();
    }
}
