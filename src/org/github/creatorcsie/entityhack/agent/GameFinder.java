package org.github.creatorcsie.entityhack.agent;

import java.applet.Applet;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 游戏入口收集器（与 Position-Hack 同一套路）：
 * 老 Minecraft 没有 {@code theMinecraft} 静态单例（那是 Beta 才有的），
 * 所以 agent 起来以后必须自己"捞"出游戏实例：
 * <ol>
 *   <li>AWT 组件树上的全部 Applet（{@code net.minecraft.client.MinecraftApplet} 在各版实物 jar 里都保留了真名）；</li>
 *   <li>线程 target 对象（{@code java -jar} 直启时 {@code net.minecraft.client.d} 实现 Runnable，游戏线程的 target 就是它）。</li>
 * </ol>
 * 两类候选都收集，按可信度排序后交给 GameBinder 逐个尝试。
 */
public final class GameFinder {

    private GameFinder() {
    }

    public static List<Object> collectRootCandidates() {
        List<Object> out = new ArrayList<Object>(collectApplets());
        out.addAll(collectThreadTargets());
        return out;
    }

    private static List<Applet> collectApplets() {
        List<Applet> out = new ArrayList<Applet>();
        try {
            Frame[] frames = Frame.getFrames();
            if (frames != null) {
                for (Frame frame : frames) {
                    collect(frame, out);
                }
            }
        } catch (Throwable ignored) {
            // AWT 尚未初始化：由调用方轮询重试
        }
        Collections.sort(out, new Comparator<Applet>() {
            public int compare(Applet a, Applet b) {
                return priority(a.getClass().getName()) - priority(b.getClass().getName());
            }
        });
        return out;
    }

    private static void collect(Container container, List<Applet> out) {
        Component[] components = container.getComponents();
        if (components == null) {
            return;
        }
        for (Component component : components) {
            if (component instanceof Applet && !contains(out, component)) {
                out.add((Applet) component);
            }
            if (component instanceof Container) {
                collect((Container) component, out);
            }
        }
    }

    private static int priority(String className) {
        String lower = className.toLowerCase();
        String simple = lower.substring(lower.lastIndexOf('.') + 1);
        if (simple.equals("minecraftapplet")) {
            return 0;
        }
        if (lower.contains("mojang")) {
            return 1;
        }
        if (lower.contains("launchwrapper")) {
            return 3;
        }
        if (lower.contains("minecraft")) {
            return 2;
        }
        return 4;
    }

    /** {@code java -jar inf-20100415.jar} 直启时的主路径：游戏主类实例挂在游戏线程的 target 上。 */
    private static List<Object> collectThreadTargets() {
        List<Object> out = new ArrayList<Object>();
        try {
            Field targetField = Thread.class.getDeclaredField("target");
            targetField.setAccessible(true);
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                Object target;
                try {
                    target = targetField.get(t);
                } catch (Throwable ignored) {
                    continue;
                }
                if (target == null || target instanceof Thread || GameShape.isJdkClass(target.getClass())) {
                    continue;
                }
                if (!contains(out, target)) {
                    out.add(target);
                }
            }
        } catch (Throwable ignored) {
            // JDK9+ 可能拒绝反射 Thread.target；这个年代的 MC 只会跑在 JDK8 上
        }
        return out;
    }

    private static boolean contains(List<?> list, Object o) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == o) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从 root 出发做两层 BFS，收集"游戏侧"对象（排除 JDK/AWT，排除实体实例——
     * 实体内部也引用 World/其它实体，作为容器候选会干扰判定，但作为"实体来源"是我们要的，
     * 所以这里不过滤实体，只过滤 JDK/AWT/自身工具类）。
     */
    public static List<Object> reachable(Object root, int maxDepth) {
        List<Object> all = new ArrayList<Object>();
        List<Object> frontier = new ArrayList<Object>();
        add(all, frontier, root);
        for (int depth = 0; depth < maxDepth && !frontier.isEmpty(); depth++) {
            List<Object> next = new ArrayList<Object>();
            for (Object o : frontier) {
                for (Field f : GameShape.allFields(o.getClass())) {
                    if (f.getType().isPrimitive() || f.getType().isArray()
                            || java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    Object v = GameShape.read(f, o);
                    if (v != null && v != o) {
                        add(all, next, v);
                    }
                }
                if (o instanceof Container) {
                    Component[] children;
                    try {
                        children = ((Container) o).getComponents();
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (children != null) {
                        for (Component ch : children) {
                            if (ch != null && ch != o) {
                                add(all, next, ch);
                            }
                        }
                    }
                }
            }
            frontier = next;
        }
        return all;
    }

    private static void add(List<Object> all, List<Object> frontier, Object v) {
        Class<?> cls = v.getClass();
        if (GameShape.isJdkClass(cls) || contains(all, v)) {
            return;
        }
        String lower = cls.getName().toLowerCase();
        if (lower.contains("creatorcsie.entityhack") && !(v instanceof java.lang.reflect.Proxy)) {
            return; // 本工具自己的对象不作为候选（TickPump 的 Proxy 反而是"游戏侧"的，允许）
        }
        all.add(v);
        frontier.add(v);
    }
}
