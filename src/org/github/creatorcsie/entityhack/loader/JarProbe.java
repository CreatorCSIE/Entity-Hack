package org.github.creatorcsie.entityhack.loader;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.github.creatorcsie.entityhack.agent.GameShape;

/**
 * 离线结构探针：把实物游戏 jar（{@code %APPDATA%\.betacraft\versions\*.jar}）用 URLClassLoader
 * 以 <b>不初始化</b> 的方式加载进来，然后用与注入期完全相同的那套形状判据（{@link GameShape}）
 * 把 World / 实体基类 / 生成口 / 移除口 / 置死口 / IWorldAccess / EntityList / EntityMap 全找一遍。
 *
 * 意义：不用启动 Minecraft、不用真的 attach，就能证明"定界逻辑认得这些混淆 jar"。
 * 判据与运行期共用一份，所以这里的结论可以直接外推到注入现场。
 */
public final class JarProbe {

    /** 与分析文档 §1 操作矩阵对照的期望值（javap 实测的混淆名）。 */
    private static final String[][] EXPECT = {
            { "in-20100223", "net.minecraft.a.a.g", "net.minecraft.a.c.b", null, "net.minecraft.a.a.i" },
            { "inf-20100227", "net.minecraft.a.a.e", "net.minecraft.a.c.b", null, "net.minecraft.a.a.h" },
            { "inf-20100313", "net.minecraft.a.a.f", "net.minecraft.a.c.b", null, "net.minecraft.a.a.g" },
            { "inf-20100316", "net.minecraft.a.a.e", "net.minecraft.a.c.b", null, null },
            { "inf-20100320", "net.minecraft.a.a.e", "net.minecraft.a.c.b", null, null },
            { "inf-20100321", null, "net.minecraft.a.c.b", null, null },
            { "inf-20100325", null, "net.minecraft.a.c.b", null, null },
            { "inf-20100327", null, "net.minecraft.a.c.b", null, null },
            { "inf-20100330", null, "net.minecraft.a.c.b", null, null },
            { "inf-20100413", "net.minecraft.a.a.g", "net.minecraft.a.c.b", "net.minecraft.a.c.e", null },
            { "inf-20100414", "net.minecraft.a.a.g", "net.minecraft.a.c.b", "net.minecraft.a.c.e", null },
            { "inf-20100415", "net.minecraft.a.a.g", "net.minecraft.a.c.b", "net.minecraft.a.c.e", null },
    };

    private final File jar;
    private final java.net.URLClassLoader loader;
    private final List<Class<?>> classes = new ArrayList<Class<?>>();
    private final StringBuilder report = new StringBuilder();

    private Class<?> entityBase;
    private Class<?> world;
    private Class<?> accessInterface;
    private Class<?> entityList;
    private Class<?> entityMap;

    public JarProbe(File jar) throws Exception {
        this.jar = jar;
        this.loader = new java.net.URLClassLoader(new java.net.URL[] { jar.toURI().toURL() }, null);
        ZipFile zf = new ZipFile(jar);
        try {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                String name = en.nextElement().getName();
                if (!name.endsWith(".class") || name.contains("$")) {
                    continue;
                }
                String cn = name.substring(0, name.length() - 6).replace('/', '.');
                if (!cn.startsWith("net.minecraft.")) {
                    continue;
                }
                Class<?> c = tryLoad(cn);
                if (c != null) {
                    classes.add(c);
                }
            }
        } finally {
            zf.close();
        }
    }

    private Class<?> tryLoad(String name) {
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable ignored) {
            return null; // 依赖缺失（lwjgl 等）或损坏条目：跳过即可
        }
    }

    /** 跑一轮定界，返回可读报告。 */
    public String run() {
        line("jar: " + jar.getName() + "   可加载类: " + classes.size());
        findEntityBase();
        findWorld();
        findAccessInterface();
        findOps();
        findEntityList();
        findEntityMap();
        findSpawnable();
        compareExpectations();
        return report.toString();
    }

    private void findEntityBase() {
        List<Class<?>> candidates = new ArrayList<Class<?>>();
        for (Class<?> c : classes) {
            if (GameShape.isEntityBase(c)) {
                candidates.add(c);
            }
        }
        Class<?> best = GameShape.rankEntityBase(classes);
        entityBase = best;
        line("实体基类: " + name(best) + "  (宽松候选 " + candidates.size() + " 个, 打分选中类的子类 "
                + countSubclasses(best) + " 个, 最长坐标游程 " + GameShape.longestRun(best)
                + ", 实例登记表字段 " + GameShape.selfRegistryStatics(best) + ")");
        if (best != null) {
            line("  落选候选: " + rejected(candidates, best));
        }
        if (best != null) {
            Field[] trip = firstTriple(best);
            line("  数值三元组: " + fieldNames(trip) + "   AABB 字段: "
                    + fieldName(GameShape.findAabbField(best, true)));
        }
    }

    private int countSubclasses(Class<?> c) {
        if (c == null) {
            return 0;
        }
        int n = 0;
        for (Class<?> d : classes) {
            if (d != c && c.isAssignableFrom(d)) {
                n++;
            }
        }
        return n;
    }

    /** 把落选候选连同"为什么落选"一起打出来，方便核对判据。 */
    private String rejected(List<Class<?>> candidates, Class<?> best) {
        List<String> out = new ArrayList<String>();
        for (Class<?> c : candidates) {
            if (c == best) {
                continue;
            }
            int reg = GameShape.selfRegistryStatics(c);
            out.add(c.getName() + "[游程" + GameShape.longestRun(c) + "/数值"
                    + GameShape.declaredNumericCount(c) + (reg >= 3 ? "/实例表" + reg : "") + "]");
        }
        return out.isEmpty() ? "无" : out.toString();
    }

    private void findWorld() {
        if (entityBase == null) {
            return;
        }
        Class<?> best = null;
        int bestScore = -1;
        for (Class<?> c : classes) {
            if (!GameShape.looksWorldLike(c, entityBase)) {
                continue;
            }
            int score = GameShape.methodCount(c);
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        world = best;
        line("World: " + name(best) + "  (方法数 " + bestScore + ")");
        if (best != null) {
            Field wf = GameShape.findWorldField(entityBase);
            line("  实体 -> World 字段: " + fieldName(wf) + "   （这就是'没有静态单例'时的抓世界通路）");
            Field pf = firstFieldOfType(best, entityBase);
            line("  World -> 玩家字段: " + (pf == null ? "无（0223 靠 i() 方法）" : fieldName(pf)));
        }
    }

    private void findAccessInterface() {
        accessInterface = world == null ? null : GameShape.findAccessInterface(world, entityBase);
        line("IWorldAccess: " + name(accessInterface)
                + (accessInterface == null ? "  （死壳期：没有 addAccess，借不到游戏线程）"
                        : "   每 tick 回调的无参方法: " + noArgMethods(accessInterface)));
    }

    private void findOps() {
        if (world == null || entityBase == null) {
            return;
        }
        List<Method> ops = new ArrayList<Method>();
        for (Class<?> c = world; c != null && c != Object.class; c = c.getSuperclass()) {
            ops.addAll(GameShape.entityOpsCandidates(c, entityBase));
        }
        StringBuilder sb = new StringBuilder();
        for (Method m : ops) {
            sb.append(m.getName()).append("(Entity)").append(Modifier.isStatic(m.getModifiers()) ? "[static] " : " ");
        }
        line("单实体参数的 void 方法（生成/置死/摘除候选）: " + (sb.length() == 0 ? "无" : sb.toString().trim()));
        if (ops.isEmpty()) {
            line("  死壳期：没有任何实体方法，只能按索引直写 EntityMap 格子（配方 D）");
        }
    }

    private void findEntityList() {
        for (Class<?> c : classes) {
            if (GameShape.isEntityListLike(c, entityBase, world)) {
                entityList = c;
                break;
            }
        }
        line("EntityList(类型注册表): " + name(entityList)
                + (entityList == null ? "  （没有：实体不落盘 / 0223 用 LevelLoader 字符串工厂）" : ""));
        if (entityList != null) {
            line("  static Map 字段: " + fieldsToString(GameShape.staticMapFields(entityList))
                    + "   addMapping: " + methodSig(GameShape.findAddMapping(entityList)));
        }
    }

    /**
     * EntityMap：必须是 <b>World 字段引用到的</b>、且自己声明了 {@code List[]} 的类型。
     * 只看"有没有 List[] 字段"会误命中 {@code net.minecraft.client.f.d}（那是 EntityRenderers，
     * 按实体类型分桶的渲染器表，不是按空间分格的实体网格）。
     */
    private void findEntityMap() {
        if (world == null) {
            line("EntityMap(List[] 网格): 无（没定界到 World）");
            return;
        }
        for (Class<?> c = world; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : safeFields(c)) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                Class<?> t = f.getType();
                if (t == List[].class) {
                    entityMap = c;
                    line("EntityMap(List[] 网格): " + c.getName() + "（World 自己就声明了 List[] 字段 "
                            + f.getName() + "）");
                    return;
                }
                if (!t.isPrimitive() && !t.isArray() && GameShape.declaresListArray(t)
                        && !Modifier.isAbstract(t.getModifiers())) {
                    entityMap = t;
                    line("EntityMap(List[] 网格): " + t.getName() + "  (World 字段 " + f.getName() + ")");
                    return;
                }
            }
        }
        line("EntityMap(List[] 网格): 无  （这一档用 World 上的单一 loadedEntityList，没有空间分格）");
    }

    private static Field[] safeFields(Class<?> c) {
        try {
            return c.getDeclaredFields();
        } catch (Throwable ignored) {
            return new Field[0];
        }
    }

    private void findSpawnable() {
        if (world == null || entityBase == null) {
            return;
        }
        int with = 0;
        int without = 0;
        List<String> missing = new ArrayList<String>();
        for (Class<?> c : classes) {
            if (c == entityBase || c.isInterface() || Modifier.isAbstract(c.getModifiers())
                    || !entityBase.isAssignableFrom(c)) {
                continue;
            }
            boolean ok = false;
            try {
                Constructor<?> ctor = c.getDeclaredConstructor(world);
                ok = ctor != null;
            } catch (Throwable ignored) {
            }
            if (ok) {
                with++;
            } else {
                without++;
                missing.add(c.getName());
            }
        }
        line("(World) 构造器可反射直连的具体实体类: " + with + " 个；不可直连: " + without + " 个");
        if (without > 0) {
            Collections.sort(missing);
            line("  剥除案（配方 B/C 绕行）: " + missing);
        }
    }

    private void compareExpectations() {
        String key = jar.getName().replace(".jar", "");
        String[] exp = null;
        for (int i = 0; i < EXPECT.length; i++) {
            if (EXPECT[i][0].equals(key)) {
                exp = EXPECT[i];
                break;
            }
        }
        if (exp == null) {
            line("对照文档矩阵: 该 jar 不在已核验清单里（结果仅作参考）");
            return;
        }
        line("对照分析文档 §1 矩阵:");
        check("World", exp[1], world);
        check("Entity 基类", exp[2], entityBase);
        check("EntityList", exp[3], entityList);
        check("EntityMap", exp[4], entityMap);
    }

    private void check(String what, String expected, Class<?> actual) {
        String got = actual == null ? null : actual.getName();
        if (expected == null && actual == null) {
            line("  [OK]   " + what + ": 文档也没找到，一致（都不存在）");
        } else if (expected == null) {
            line("  [INFO] " + what + ": 文档未列出，探针找到 " + got);
        } else if (expected.equals(got)) {
            line("  [OK]   " + what + ": " + got + " （与文档 javap 实测一致）");
        } else {
            line("  [DIFF] " + what + ": 文档=" + expected + " 探针=" + got);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void line(String s) {
        report.append(s).append('\n');
    }

    private static String name(Class<?> c) {
        return c == null ? "无" : c.getName();
    }

    private static String fieldName(Field f) {
        return f == null ? "无" : f.getName() + " : " + f.getType().getSimpleName();
    }

    private static String fieldNames(Field[] fs) {
        if (fs == null) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fs.length; i++) {
            sb.append(i > 0 ? "," : "").append(fs[i].getName());
        }
        return sb.toString();
    }

    private static String fieldsToString(Field[] fs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fs.length; i++) {
            sb.append(i > 0 ? "," : "").append(fs[i].getName());
        }
        return sb.length() == 0 ? "无" : sb.toString();
    }

    private static String methodSig(Method m) {
        return m == null ? "无" : m.getName() + m.toString().substring(m.toString().indexOf('('));
    }

    private static String noArgMethods(Class<?> c) {
        TreeSet<String> out = new TreeSet<String>();
        try {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterTypes().length == 0 && m.getReturnType() == void.class) {
                    out.add(m.getName() + "()");
                }
            }
        } catch (Throwable ignored) {
        }
        return out.toString();
    }

    private static Field[] firstTriple(Class<?> c) {
        List<Field[]> runs = GameShape.numericRuns(c);
        if (runs.isEmpty()) {
            return null;
        }
        Field[] longest = runs.get(0);
        for (Field[] r : runs) {
            if (r.length > longest.length) {
                longest = r;
            }
        }
        return new Field[] { longest[0], longest[1], longest[2] };
    }

    private static Field firstFieldOfType(Class<?> owner, Class<?> type) {
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                for (Field f : c.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers()) && !f.getType().isPrimitive()
                            && type.isAssignableFrom(f.getType())) {
                        return f;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 批量探针：给目录或 jar 列表用。 */
    public static String probeAll(List<File> jars) {
        StringBuilder sb = new StringBuilder();
        for (File f : jars) {
            sb.append("================================================================\n");
            try {
                sb.append(new JarProbe(f).run());
            } catch (Throwable t) {
                sb.append("probe 失败: ").append(t).append('\n');
            }
        }
        return sb.toString();
    }
}
