package org.github.creatorcsie.entityhack.agent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 纯结构判据（只看类元数据，不需要实例、不初始化类）。
 * 运行期的 GameBinder 与离线的 JarProbe 共用这一套判据，
 * 因此"离线 probe 能认出 World/Entity/生成口/移除口"就等于"注入后也能认出"。
 *
 * 判据全部来自对实物 jar 的 javap 核对（Indev 20100223 ~ Infdev 20100415）：
 * - 混淆字母跨版漂移（0223 World=a.a.g / 0227=a.a.e / 0313=a.a.f / 0316=a.a.e / 0413=a.a.g），
 *   所以只认"形状"，不认名字。
 * - World 形状：既有"实体类型字段"（playerEntity），又有"容器字段"（List / List[] / EntityMap）。
 * - Entity 基类形状：自己声明了 >=3 连续同型 float/double（pos/prevPos/motion）且声明了大量数值字段。
 * - AABB 形状：6~8 连续同型 float/double 的纯数据类（同 Position-Hack）。
 */
public final class GameShape {

    private GameShape() {
    }

    /** 按声明顺序找出 {@code declaring} 自己声明的"连续同型 float/double 非 static 字段"游程（长度 >= 3）。 */
    public static List<Field[]> numericRuns(Class<?> declaring) {
        List<Field[]> out = new ArrayList<Field[]>();
        Field[] fields;
        try {
            fields = declaring.getDeclaredFields();
        } catch (Throwable ignored) {
            return out;
        }
        List<Field> run = new ArrayList<Field>();
        Class<?> runType = null;
        for (Field f : fields) {
            Class<?> t = f.getType();
            boolean numeric = (t == float.class || t == double.class) && !Modifier.isStatic(f.getModifiers());
            if (!numeric || (runType != null && t != runType)) {
                if (run.size() >= 3) {
                    out.add(run.toArray(new Field[run.size()]));
                }
                run = new ArrayList<Field>();
                runType = numeric ? t : null;
                if (numeric) {
                    run.add(f);
                }
                continue;
            }
            runType = t;
            run.add(f);
        }
        if (run.size() >= 3) {
            out.add(run.toArray(new Field[run.size()]));
        }
        return out;
    }

    /** 类自己声明的数值字段（float/double/int/short/long/byte）个数。 */
    public static int declaredNumericCount(Class<?> declaring) {
        int n = 0;
        Field[] fields;
        try {
            fields = declaring.getDeclaredFields();
        } catch (Throwable ignored) {
            return 0;
        }
        for (Field f : fields) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            Class<?> t = f.getType();
            if (t == float.class || t == double.class || t == int.class || t == short.class
                    || t == long.class || t == byte.class) {
                n++;
            }
        }
        return n;
    }

    /** 纯数据碰撞箱类：6~8 个连续同型 float/double，且继承链数值字段总数 < 10。 */
    public static boolean isAabbLike(Class<?> clazz) {
        int maxRun = 0;
        for (Field[] run : numericRuns(clazz)) {
            if (run.length > maxRun) {
                maxRun = run.length;
            }
        }
        return maxRun >= 6 && maxRun <= 8 && countNumericInHierarchy(clazz) < 10;
    }

    /** 继承链上 float/double 字段总数（AABB 是纯数据类，个数很少；实体子类会有一大把）。 */
    public static int countNumericInHierarchy(Class<?> clazz) {
        int count = 0;
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                for (Field f : c.getDeclaredFields()) {
                    Class<?> t = f.getType();
                    if (t == float.class || t == double.class) {
                        count++;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return count;
    }

    /**
     * 实体基类：<b>候选</b>判据（宽松版）。自己声明了 >=3 连续同型数值游程（pos/prevPos/motion），
     * 并且自己声明了 >=8 个数值字段。
     *
     * <p>注意：单靠这一条在实物 jar 里会同时命中 {@code Block}（6~8 连 double）与
     * {@code EntityLiving}（5 连、25 个数值字段），所以真正的"选谁"必须走
     * {@link #rankEntityBase(java.util.Collection)} 的打分排序——
     * 对实物 0223~0415 六个 jar 的 javap 实测见该方法注释。
     */
    public static boolean isEntityBase(Class<?> clazz) {
        if (clazz == null || clazz == Object.class || clazz.isInterface()) {
            return false;
        }
        return longestRun(clazz) >= 3 && declaredNumericCount(clazz) >= 8;
    }

    /** 类自己声明的最长"连续同型 float/double 游程"。实体基类是 9（prev+pos+motion 三组），0223 是 13。 */
    public static int longestRun(Class<?> clazz) {
        int max = 0;
        for (Field[] run : numericRuns(clazz)) {
            if (run.length > max) {
                max = run.length;
            }
        }
        return max;
    }

    /**
     * "实例登记表"特征：static 字段里类型是自己/自己父类的个数。
     * {@code Block}（0223~0415 实测 59~62 个）、{@code Material}、{@code Item} 这类
     * 枚举式单例表全都有；实体基类一个都没有（{@code Entity} 实测恒为 0）。
     */
    public static int selfRegistryStatics(Class<?> clazz) {
        int n = 0;
        Field[] fields;
        try {
            fields = clazz.getDeclaredFields();
        } catch (Throwable ignored) {
            return 0;
        }
        for (Field f : fields) {
            if (!Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            Class<?> t = f.getType();
            if (t == clazz || (t.isPrimitive() == false && !t.isArray() && clazz.isAssignableFrom(t))) {
                n++;
            }
        }
        return n;
    }

    /**
     * 从一堆候选里挑实体基类（离线探针 / 无实例兜底用；运行期有玩家实例时走
     * {@link #findEntityBase(Class)} 更准）。
     *
     * <p>打分依据 = 对 {@code %APPDATA%\.betacraft\versions} 里 6 个实物 jar 的实测统计
     * （每档都跑过，数字稳定）：
     * <table border=1>
     *   <tr><th>类</th><th>abstract</th><th>最长游程</th><th>数值字段</th><th>子类</th><th>静态自身字段</th></tr>
     *   <tr><td>Entity（{@code a.c.b}）</td><td>是</td><td>9~13</td><td>32</td><td>26~27</td><td>0</td></tr>
     *   <tr><td>EntityLiving（{@code a.c.e/f}）</td><td>否</td><td>5</td><td>25</td><td>12</td><td>0</td></tr>
     *   <tr><td>Block（{@code a.a.b.x}）</td><td>否</td><td>6~8</td><td>11</td><td>31~32</td><td>59~62</td></tr>
     *   <tr><td>Render / GuiContainer 等</td><td>否</td><td>3~5</td><td>9~25</td><td>0~9</td><td>0~3</td></tr>
     * </table>
     * 只有 Entity 同时满足"一个类里连排 9 个同型坐标"（=prevPos+pos+motion 三元组全在它身上），
     * 所以这一条给最高权重；"实例登记表"直接把 Block 排除。
     */
    public static Class<?> rankEntityBase(Collection<Class<?>> pool) {
        if (pool == null) {
            return null;
        }
        List<Class<?>> candidates = new ArrayList<Class<?>>();
        List<Class<?>> strong = new ArrayList<Class<?>>();
        for (Class<?> c : pool) {
            if (c == null || !isEntityBase(c)) {
                continue;
            }
            if (selfRegistryStatics(c) >= 3) {
                continue; // Block / Material / Item 这类实例表
            }
            candidates.add(c);
            if (isTriplesBase(c)) {
                strong.add(c);
            }
        }
        // 只要存在"完整三元组"类，就绝不再看宽松候选（真机 0223 的计时器/渲染器全是宽松误报）
        if (!strong.isEmpty()) {
            candidates = strong;
        }
        Class<?> best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Class<?> c : candidates) {
            int subs = 0;
            for (Class<?> d : candidates) {
                if (d != c && c.isAssignableFrom(d)) {
                    subs++;
                }
            }
            int score = 0;
            if (longestRun(c) >= 9) {
                score += 100; // 三元组完整声明在这一个类里
            }
            if (declaredNumericCount(c) >= 20) {
                score += 50;
            }
            if (Modifier.isAbstract(c.getModifiers())) {
                score += 20;
            }
            if (findAabbField(c, true) != null) {
                score += 30;
            }
            score += 2 * subs; // 实体基类天然被一堆 mob 继承
            if (score > bestScore || (score == bestScore && best != null && c.isAssignableFrom(best))) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    /**
     * 实体基类的<b>强</b>判据（真机实测版）：一个类自己把 prev+pos+motion 三组坐标<b>连排</b>声明出来
     * （>=9 个同型 float/double），数值字段 >=8，且不是"实例登记表"类。
     *
     * <p>为什么必须用 9 而不是 3：注入 in-20100223 真机时，宽松判据（>=3）同时命中了
     * {@code net.minecraft.client.g}（游戏计时器：3 连 float + 9 个数值字段，{@code final}）、
     * {@code net.minecraft.client.a.b}（RenderEngine：4 连 float，{@code final}，而且它是
     * {@code worldAccesses} 里唯一的元素）和 {@code net.minecraft.client.a.e}，
     * 于是"找玩家实体"抓到的是计时器，绑定必然失败。而真体系统里只有
     * {@code Entity}（{@code a.c.b}，13 连 float / 41 个数值字段 / abstract / 27 个子类）
     * 满足"9 连同型坐标"——prevPos+pos+motion 一定整组声明在实体基类自己身上。
     */
    public static boolean isTriplesBase(Class<?> clazz) {
        if (clazz == null || clazz == Object.class || clazz.isInterface()) {
            return false;
        }
        if (selfRegistryStatics(clazz) >= 3) {
            return false; // Block / Item / Material 这类枚举式实例表
        }
        return longestRun(clazz) >= 9 && declaredNumericCount(clazz) >= 8;
    }

    /** 继承链上最高的"完整三元组"类；没有则 null。 */
    public static Class<?> findTriplesBase(Class<?> clazz) {
        Class<?> best = null;
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            if (isTriplesBase(c)) {
                best = c;
            }
        }
        return best;
    }

    public static Class<?> findEntityBase(Class<?> from) {
        Class<?> best = findTriplesBase(from);
        if (best != null) {
            return best;
        }
        for (Class<?> c = from; c != null && c != Object.class; c = c.getSuperclass()) {
            if (isEntityBase(c)) {
                best = c;
            }
        }
        return best;
    }

    /** 实体特征：继承链里有"完整三元组"基类，或至少有一个实体基类 + AABB 字段。 */
    public static boolean isEntityLike(Class<?> clazz) {
        if (findTriplesBase(clazz) != null) {
            return true;
        }
        Class<?> base = findEntityBase(clazz);
        return base != null && findAabbField(base, true) != null;
    }

    /**
     * 碰撞箱上"就地整体平移"的方法：{@code void method(同型 float/double x3)}。
     *
     * <p>实物 0223 是 {@code AxisAlignedBB.offset(FFF)V}（{@code Entity.java:198} 就是用它搬盒子的）；
     * 同名的 {@code addCoord/expand} 都返回<b>新</b>对象、不改自己，所以这里必须只认 void 返回——
     * 认错一次就把盒子当成了"绝对坐标写入"，实体会被瞬间压到原点。
     */
    public static Method findAabbOffset(Class<?> boxClass) {
        for (Class<?> c = boxClass; c != null && c != Object.class; c = c.getSuperclass()) {
            Method[] methods;
            try {
                methods = c.getDeclaredMethods();
            } catch (Throwable ignored) {
                return null;
            }
            for (Method m : methods) {
                if (Modifier.isStatic(m.getModifiers()) || m.getReturnType() != void.class) {
                    continue;
                }
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 3 || ps[0] != ps[1] || ps[1] != ps[2]) {
                    continue;
                }
                if (ps[0] != float.class && ps[0] != double.class) {
                    continue;
                }
                return m;
            }
        }
        return null;
    }

    /**
     * 碰撞箱的六个角：public 数值字段，声明顺序 = {@code minX,minY,minZ,maxX,maxY,maxZ}
     * （0223 是 6 连 float，0413 起是 6 连 double；0223 那个 {@code private float epsilon} 不是 public，
     * 正好被这条判据挡在外面）。凑不满 6 个就返回空数组，让调用方去走别的路。
     */
    public static Field[] aabbCorners(Class<?> boxClass) {
        List<Field> out = new ArrayList<Field>();
        for (Class<?> c = boxClass; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || !Modifier.isPublic(f.getModifiers())) {
                        continue;
                    }
                    Class<?> t = f.getType();
                    if (t == float.class || t == double.class) {
                        out.add(f);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return out.size() == 6 ? out.toArray(new Field[out.size()]) : new Field[0];
    }

    /** 继承链上引用 AABB 类的字段。 */
    public static Field findAabbField(Class<?> clazz, boolean includingInherited) {
        for (Class<?> c = clazz; c != null && (!includingInherited || c != Object.class); c = c.getSuperclass()) {
            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }
            for (Field f : fields) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) {
                    continue;
                }
                if (isAabbLike(f.getType())) {
                    return f;
                }
            }
            if (!includingInherited) {
                break;
            }
        }
        return null;
    }

    /**
     * World 形状：同时具备
     *  (a) 一个"实体类型"的引用字段（playerEntity：0223/0316/0413 都有），
     *  (b) 一个容器字段：{@code List} / {@code List[]} / 或类型里含 {@code List[]} 字段（EntityMap）。
     * 不要求任何方法存在——0227/0313 的 World 就是"没有任何增删方法"的死壳，也必须认出来。
     */
    public static boolean isWorldLike(Class<?> clazz, Class<?> entityBase) {
        if (clazz == null || entityBase == null || clazz.isInterface()) {
            return false;
        }
        if (entityBase.isAssignableFrom(clazz)) {
            return false; // 实体继承链自己不算世界（注意：只按继承关系排除，0313/0316 的 World 恰好也"形状像实体"）
        }
        boolean hasEntityField = false;
        boolean hasContainer = false;
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }
            for (Field f : fields) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                Class<?> t = f.getType();
                if (!t.isPrimitive() && entityBase.isAssignableFrom(t)) {
                    hasEntityField = true;
                }
                if (t == List.class || t == ArrayList.class || t == List[].class) {
                    hasContainer = true;
                } else if (!t.isPrimitive() && !t.isArray() && declaresListArray(t)) {
                    hasContainer = true; // EntityMap（public List[] d）形态
                }
            }
        }
        return hasEntityField && hasContainer;
    }

    /** 类型里是否声明了 {@code List[]} 字段（EntityMap 的网格）。 */
    public static boolean declaresListArray(Class<?> clazz) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }
            for (Field f : fields) {
                if (f.getType() == List[].class) {
                    return true;
                }
            }
        }
        return false;
    }

    /** World 上"单参数且参数是实体（或其父类）"的 void 方法——生成口/置死口/立即摘除口都在这里面。 */
    public static List<Method> entityOpsCandidates(Class<?> worldClass, Class<?> entityBase) {
        List<Method> out = new ArrayList<Method>();
        Method[] methods;
        try {
            methods = worldClass.getDeclaredMethods();
        } catch (Throwable ignored) {
            return out;
        }
        for (Method m : methods) {
            if (m.getReturnType() != void.class || m.getParameterTypes().length != 1) {
                continue;
            }
            Class<?> p = m.getParameterTypes()[0];
            if (p.isPrimitive() || p.isArray()) {
                continue;
            }
            if (entityBase.isAssignableFrom(p)) {
                out.add(m);
            }
        }
        return out;
    }

    /**
     * IWorldAccess 形状：接口，含"单实体参数 void 方法"或"多 float/double 坐标的 sound/particle 方法"，
     * 并且还有一个无参 void 方法（每 tick 会被 World 调用的那个，如 0413 的 {@code d()}、0223 的 {@code a()}）。
     * 满足这个形状的接口，就能用 {@link java.lang.reflect.Proxy} 挂进 World 的 accessList 借到游戏线程。
     */
    public static boolean isAccessLike(Class<?> clazz, Class<?> entityBase) {
        if (clazz == null || !clazz.isInterface()) {
            return false;
        }
        boolean entityMethod = false;
        boolean soundMethod = false;
        boolean noArgVoid = false;
        Method[] methods;
        try {
            methods = clazz.getDeclaredMethods();
        } catch (Throwable ignored) {
            return false;
        }
        for (Method m : methods) {
            Class<?>[] ps = m.getParameterTypes();
            if (m.getReturnType() == void.class) {
                if (ps.length == 0) {
                    noArgVoid = true;
                }
                if (ps.length == 1 && !ps[0].isPrimitive() && entityBase != null && entityBase.isAssignableFrom(ps[0])) {
                    entityMethod = true;
                }
                if (ps.length >= 4 && ps[0] == String.class && countNumeric(ps) >= 3) {
                    soundMethod = true;
                }
            }
        }
        return noArgVoid && (entityMethod || soundMethod);
    }

    /** World 上的 addAccess(IWorldAccess) / removeAccess(IWorldAccess) 单参数方法。 */
    public static Method findAddAccess(Class<?> worldClass, Class<?> accessInterface) {
        for (Method m : entityParamVoidMethods(worldClass, accessInterface)) {
            return m;
        }
        return null;
    }

    /**
     * World 上的 addAccess/removeAccess 暴露出的 IWorldAccess 接口类型。
     * 0223 = {@code a.a.d}（addAccess/removeAccess 参数）、0316 = {@code a.a.b}、0413/0415 = {@code a.a.a}。
     * 0227/0313 死壳期没有任何这类方法，返回 null（此时只能放弃"游戏线程同步"这条捷径）。
     */
    public static Class<?> findAccessInterface(Class<?> worldClass, Class<?> entityBase) {
        Method[] methods;
        try {
            methods = worldClass.getDeclaredMethods();
        } catch (Throwable ignored) {
            return null;
        }
        for (Method m : methods) {
            if (m.getReturnType() != void.class || m.getParameterTypes().length != 1) {
                continue;
            }
            Class<?> p = m.getParameterTypes()[0];
            if (isAccessLike(p, entityBase)) {
                return p;
            }
        }
        return null;
    }

    /** World 上"单参数且参数是 accessInterface"的 void 方法（通常是 addAccess；0413/0316 里 addAccess 在前）。 */
    public static List<Method> entityParamVoidMethods(Class<?> worldClass, Class<?> paramType) {
        List<Method> out = new ArrayList<Method>();
        Method[] methods;
        try {
            methods = worldClass.getDeclaredMethods();
        } catch (Throwable ignored) {
            return out;
        }
        for (Method m : methods) {
            if (m.getReturnType() == void.class && m.getParameterTypes().length == 1
                    && m.getParameterTypes()[0] == paramType) {
                out.add(m);
            }
        }
        return out;
    }

    /**
     * EntityList 形状（0413+ 的类型注册表，就是"数字 id 空间"本身）：
     * 两个 static {@code Map} 字段 + 一个 "static Entity method(NBT, World)" + 一个 "static String method(Entity)"。
     */
    public static boolean isEntityListLike(Class<?> clazz, Class<?> entityBase, Class<?> worldClass) {
        Field[] maps = staticMapFields(clazz);
        if (maps.length < 2) {
            return false;
        }
        boolean create = false;
        boolean idString = false;
        Method[] methods;
        try {
            methods = clazz.getDeclaredMethods();
        } catch (Throwable ignored) {
            return false;
        }
        for (Method m : methods) {
            if (!Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            Class<?>[] ps = m.getParameterTypes();
            if (m.getReturnType() != void.class && entityBase != null && entityBase.isAssignableFrom(m.getReturnType())
                    && ps.length == 2 && worldClass != null && ps[1] == worldClass) {
                create = true;
            }
            if (m.getReturnType() == String.class && ps.length == 1 && entityBase != null
                    && entityBase.isAssignableFrom(ps[0])) {
                idString = true;
            }
        }
        return create && idString;
    }

    /** 类里声明的 static {@code java.util.Map} 字段。 */
    public static Field[] staticMapFields(Class<?> clazz) {
        List<Field> out = new ArrayList<Field>();
        Field[] fields;
        try {
            fields = clazz.getDeclaredFields();
        } catch (Throwable ignored) {
            return new Field[0];
        }
        for (Field f : fields) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == java.util.Map.class) {
                out.add(f);
            }
        }
        return out.toArray(new Field[out.size()]);
    }

    /** addMapping(Class, String)：private static void。 */
    public static Method findAddMapping(Class<?> entityListClass) {
        Method[] methods;
        try {
            methods = entityListClass.getDeclaredMethods();
        } catch (Throwable ignored) {
            return null;
        }
        for (Method m : methods) {
            if (Modifier.isStatic(m.getModifiers()) && m.getReturnType() == void.class
                    && m.getParameterTypes().length == 2 && m.getParameterTypes()[0] == Class.class
                    && m.getParameterTypes()[1] == String.class) {
                return m;
            }
        }
        return null;
    }

    /**
     * 从 World 实例上收集所有"实体登记表"：List 字段 + EntityMap 里的 List[] 网格 + EntityMap 自己的 List 字段。
     * 去重用 {@link java.util.IdentityHashMap}：EntityMap 的格子有 4096~32768 个，
     * 用 {@code List.contains} 去重会退化成 O(n²)，界面每 1.2 秒刷一次会当场卡死。
     */
    public static void collectRegistries(Object world, List<Object> out) {
        collectRegistries(world, out, new java.util.IdentityHashMap<Object, Boolean>());
    }

    private static void collectRegistries(Object world, List<Object> out, java.util.IdentityHashMap<Object, Boolean> seen) {
        try {
            for (Field f : allFields(world.getClass())) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                f.setAccessible(true);
                Object v = f.get(world);
                if (v == null || seen.containsKey(v)) {
                    continue;
                }
                seen.put(v, Boolean.TRUE);
                if (v instanceof List) {
                    out.add(v);
                } else if (v instanceof List[]) {
                    addGrid((List[]) v, out, seen);
                } else if (!isJdkClass(v.getClass()) && declaresListArray(v.getClass())) {
                    // EntityMap：钻进去，网格 + 主列表都算登记表
                    collectRegistries(v, out, seen);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** World 上元素类型为 accessInterface 的 List 字段值（就是 accessList/parts，把它当插入点）。 */
    public static List<Object> findAccessLists(Object world, Class<?> accessInterface) {
        List<Object> out = new ArrayList<Object>();
        try {
            for (Field f : allFields(world.getClass())) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType() != List.class) {
                    continue;
                }
                Object v = read(f, world);
                if (!(v instanceof List)) {
                    continue;
                }
                List<?> list = (List<?>) v;
                // accessList 里至少已有渲染引擎（+ 单人的 IInWorldManager），非空；
                // 空列表不认，避免把 loadedEntityList 之类的误当插入点。
                if (!list.isEmpty() && accessInterface.isInstance(list.get(0))) {
                    out.add(v);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void addGrid(List[] grid, List<Object> out, java.util.IdentityHashMap<Object, Boolean> seen) {
        for (int i = 0; i < grid.length; i++) {
            if (grid[i] != null && !seen.containsKey(grid[i])) {
                seen.put(grid[i], Boolean.TRUE);
                out.add(grid[i]);
            }
        }
    }

    /** 类 + 父类的所有声明字段。 */
    public static List<Field> allFields(Class<?> clazz) {
        List<Field> out = new ArrayList<Field>();
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field[] fs = c.getDeclaredFields();
                for (int i = 0; i < fs.length; i++) {
                    out.add(fs[i]);
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static int countNumeric(Class<?>[] ps) {
        int n = 0;
        for (int i = 1; i < ps.length; i++) {
            if (ps[i] == double.class || ps[i] == float.class || ps[i] == int.class) {
                n++;
            }
        }
        return n;
    }

    /** JDK 自带/AWT 类不可能是游戏对象。 */
    public static boolean isJdkClass(Class<?> clazz) {
        String name = clazz.getName();
        String lower = name.toLowerCase();
        return lower.startsWith("java.") || lower.startsWith("javax.") || lower.startsWith("sun.")
                || lower.startsWith("jdk.") || lower.startsWith("com.sun.") || lower.contains(".awt.");
    }

    /** 反射读字段值，失败返回 null。 */
    public static Object read(Field f, Object owner) {
        try {
            f.setAccessible(true);
            return f.get(owner);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** World 上"世界形状"的字段：见 {@link #looksWorldLike}。 */
    public static Field findWorldField(Class<?> entityBase) {
        Field best = null;
        int bestScore = -1;
        for (Class<?> c = entityBase; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : declaredFields(c)) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                Class<?> t = f.getType();
                if (t.isPrimitive() || t.isArray() || !looksWorldLike(t, entityBase)) {
                    continue;
                }
                int score = methodCount(t);
                if (score > bestScore) {
                    bestScore = score;
                    best = f;
                }
            }
        }
        return best;
    }

    private static Field[] declaredFields(Class<?> c) {
        try {
            return c.getDeclaredFields();
        } catch (Throwable ignored) {
            return new Field[0];
        }
    }

    public static int methodCount(Class<?> c) {
        try {
            return c.getDeclaredMethods().length;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * 世界形状：有容器字段（List / List[] / 含 List[] 的 EntityMap），
     * 并且声明了与实体相关的成员（字段/方法参数/方法返回值里出现实体类型）。
     * 0223 的 World 没有实体类型字段（玩家通过 {@code i()} 方法暴露），所以三种出口都要认。
     */
    public static boolean looksWorldLike(Class<?> clazz, Class<?> entityBase) {
        if (clazz == null || clazz.isInterface() || entityBase == null
                || entityBase.isAssignableFrom(clazz)) {
            return false;
        }
        boolean container = false;
        boolean entityRelated = false;
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    Class<?> t = f.getType();
                    if (t == List.class || t == ArrayList.class || t == List[].class
                            || (!t.isPrimitive() && !t.isArray() && declaresListArray(t))) {
                        container = true;
                    }
                    if (!t.isPrimitive() && entityBase.isAssignableFrom(t)) {
                        entityRelated = true;
                    }
                }
            } catch (Throwable ignored) {
            }
            try {
                for (Method m : c.getDeclaredMethods()) {
                    Class<?>[] ps = m.getParameterTypes();
                    for (int i = 0; i < ps.length; i++) {
                        if (!ps[i].isPrimitive() && entityBase.isAssignableFrom(ps[i])) {
                            entityRelated = true;
                        }
                    }
                    Class<?> r = m.getReturnType();
                    if (!r.isPrimitive() && entityBase.isAssignableFrom(r)) {
                        entityRelated = true;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return container && entityRelated;
    }

    /**
     * <b>不依赖实体基类</b>的"世界形状"判据，用于真机 0223 那条兜底路：
     * Minecraft 实例上找不到实体字段（玩家不挂在 Minecraft 上，而是挂在 World 的实体类型字段上），
     * 于是必须先认出 {@code client.d.d : net.minecraft.a.a.g} 这个 World 字段，再从 World 反抓玩家。
     *
     * <p>条件：有容器字段（{@code List} / {@code List[]} / 内含 {@code List[]} 的类型），
     * 并且字段或方法的参数/返回值里引用了"完整三元组"类型（{@link #findTriplesBase}）。
     * 真机 0223 校验：World {@code a.a.g} 命中（{@code y : a.c.e} 且 {@code a.c.e} 链上有
     * {@code a.c.b}）；{@code client.a.b}（RenderEngine，4 连 float + 一个 {@code List} 字段）
     * 引用的全是 World/客户端类，没有三元组类型 → 排除。
     */
    public static boolean isWorldShape(Class<?> clazz) {
        if (clazz == null || clazz.isInterface() || clazz == Object.class) {
            return false;
        }
        boolean container = false;
        boolean entityRelated = false;
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    Class<?> t = f.getType();
                    if (t == List.class || t == ArrayList.class || t == List[].class
                            || (!t.isPrimitive() && !t.isArray() && declaresListArray(t))) {
                        container = true;
                    }
                    if (refsTriplesBase(t)) {
                        entityRelated = true;
                    }
                }
            } catch (Throwable ignored) {
            }
            try {
                for (Method m : c.getDeclaredMethods()) {
                    Class<?>[] ps = m.getParameterTypes();
                    for (int i = 0; i < ps.length; i++) {
                        if (refsTriplesBase(ps[i])) {
                            entityRelated = true;
                        }
                    }
                    if (refsTriplesBase(m.getReturnType())) {
                        entityRelated = true;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return container && entityRelated;
    }

    private static boolean refsTriplesBase(Class<?> t) {
        if (t == null || t.isPrimitive() || t.isArray()) {
            return false;
        }
        return findTriplesBase(t) != null;
    }
}
