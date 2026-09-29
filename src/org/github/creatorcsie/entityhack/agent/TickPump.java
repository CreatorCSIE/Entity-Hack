package org.github.creatorcsie.entityhack.agent;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 不靠 ASM 的"借游戏线程"通道。两条通道，主用第一条：
 *
 * <p><b>通道 1：包装登记表 List。</b>{@code World.levelEntities()}（0413 的 {@code c()}、
 * 0223 的 {@code EntityMap.a()}）每 tick 都要对实体登记表调 {@code size()/get(i)/remove(i)}。
 * 把 World（或 EntityMap）上那个 {@code List} 字段换成一个 {@link Proxy} 实现的 {@code List}
 * ——所有调用原样转发给底层 ArrayList，转发<b>之后</b>顺手排空我们的待办队列——
 * 于是每个待办操作都真的跑在<b>游戏线程</b>上，且执行点必然落在实体循环附近。
 * 这条通道不需要知道任何混淆字母，只要登记表是 List（0223/0316/0413/0415 都是）。
 *
 * <p><b>通道 2：混进 IWorldAccess 部件表。</b>用 Proxy 实现 {@code IWorldAccess} 塞进
 * {@code worldAccesses/parts}。实测（本机 javap + 可读源双向核对）它在 0413 里对应
 * {@code updateAllRenderers()}，而 {@code World.restartTimeOfDay()} 只在
 * {@code skylightSubtracted} <b>变化</b>时才遍历部件表调它——也就是只在清晨/黄昏那种
 * 光照档位切换的瞬间。0223 的 {@code loadRenderers()} 更是只在载入世界时调一次。
 * 所以这条通道<b>不能</b>当主泵，只能当"偶尔也会来一下"的补充通道。
 *
 * <p>两条通道都没等到回调时（0227/0313 死壳期：既没有每 tick 的实体泵、也没有 addWorldAccess），
 * 退化为"就地执行"（和 Position-Hack 一样从 UI 线程直接写），并在窗口上标注风险。
 */
public final class TickPump implements InvocationHandler {

    private final Handles handles;
    private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<Runnable>();
    private final AtomicLong gameCallbacks = new AtomicLong();
    private final AtomicBoolean draining = new AtomicBoolean();
    /** 已经换成代理的登记表：{owner, field, 原始 List, 代理}，uninstall 时按它还原。 */
    private final List<Object[]> wrapped = new ArrayList<Object[]>();
    private volatile long installThreadId = -1;
    private volatile long gameThreadId = -1;
    private Object accessProxy;
    /** 真正在"泵"的通道（只由 {@link #notifyGameThread} 写：list / access / none）。 */
    private String installedVia = "none";
    /** access 代理是靠什么装进部件表的（{@code addAccess(...)} 还是直接 {@code list.add}）；与在泵哪条通道无关。 */
    private String accessVia;

    public TickPump(Handles handles) {
        this.handles = handles;
    }

    /** 挂通道；返回是否真的拿到了游戏线程回调。 */
    public boolean install(GameBinder.Log log) {
        installThreadId = Thread.currentThread().getId();
        gameThreadId = detectGameThread();
        return attach(log);
    }

    /**
     * 只挂通道、不重新认游戏线程（游戏线程是 {@code Minecraft} 那个实例的活循环，换关卡不会变）。
     * 世界切换后由 {@link WorldWatch} 走这条路。
     */
    public boolean attach(GameBinder.Log log) {
        gameCallbacks.set(0L); // "本次通道的回调数"从 0 起算，否则等第一次回调的循环会直接穿过去
        wrapRegistries(log);
        tryAccessProxy(log);
        if (wrapped.isEmpty() && accessProxy == null) {
            log.log("TickPump: 没有可包装的登记表、也没有 IWorldAccess 通路 -> 就地执行（有竞态风险）");
            handles.pumpRunning = false;
            installedVia = "none";
            return false;
        }
        long deadline = System.currentTimeMillis() + 6000L;
        while (gameCallbacks.get() == 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                break;
            }
        }
        boolean ok = gameCallbacks.get() > 0;
        handles.pumpRunning = ok;
        log.log("TickPump: wrapped=" + wrapped.size() + " lists, accessProxy=" + (accessProxy != null)
                + ", via=" + installedVia + ", gameThread=" + (gameThreadId < 0 ? "unknown" : String.valueOf(gameThreadId))
                + ", callbacks=" + gameCallbacks.get()
                + (ok ? " -> 游戏线程通道可用" : " -> 没等到游戏线程回调，退化为就地执行"));
        return ok;
    }

    // ---------------------------------------------------------------- 通道 1

    /** 把 World / EntityMap 上"看起来是实体登记表"的 List 字段换成透明代理。 */
    private void wrapRegistries(GameBinder.Log log) {
        List<Object> owners = new ArrayList<Object>();
        owners.add(handles.world);
        collectOwnerCandidates(handles.world, owners);
        for (int i = 0; i < owners.size(); i++) {
            Object owner = owners.get(i);
            for (Field f : GameShape.allFields(owner.getClass())) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType() != List.class) {
                    continue;
                }
                Object v = GameShape.read(f, owner);
                if (!(v instanceof List) || Proxy.isProxyClass(v.getClass())) {
                    continue;
                }
                if (!looksLikeEntityRegistry((List<?>) v)) {
                    continue;
                }
                if (replace(f, owner, v)) {
                    log.log("TickPump: 包装 " + owner.getClass().getName() + "." + f.getName() + " ("
                            + ((List<?>) v).size() + " 项)");
                }
            }
        }
    }

    /** World 里那些"内含 List 字段"的辅助对象（EntityMap 等）也值得一试。 */
    private void collectOwnerCandidates(Object world, List<Object> out) {
        try {
            for (Field f : GameShape.allFields(world.getClass())) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType().isArray()) {
                    continue;
                }
                Object v = GameShape.read(f, world);
                if (v == null || v == world || GameShape.isJdkClass(v.getClass())) {
                    continue;
                }
                if (GameShape.declaresListArray(v.getClass()) || hasListField(v.getClass())) {
                    if (out.size() < 16 && !containsSame(out, v)) {
                        out.add(v);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean containsSame(List<Object> list, Object o) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == o) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasListField(Class<?> c) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                for (Field f : k.getDeclaredFields()) {
                    if (f.getType() == List.class) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    /**
     * 只包装"当前有内容、且第一个非空元素是实体"的 List。
     * 空表一律不碰：{@code unloadedEntityList}（装 NextTickListEntry）和 {@code worldAccesses}
     * （装 IWorldAccess）在刚进世界时都可能是空的，误包装会白白给游戏循环加一层反射。
     */
    private boolean looksLikeEntityRegistry(List<?> list) {
        if (handles.entityBase == null) {
            return false;
        }
        for (int i = 0; i < list.size(); i++) {
            Object o = list.get(i);
            if (o != null) {
                return GameShape.isEntityLike(o.getClass());
            }
        }
        return false;
    }

    private boolean replace(Field f, Object owner, Object originalList) {
        Object proxy;
        try {
            proxy = Proxy.newProxyInstance(List.class.getClassLoader(), new Class<?>[] { List.class }, this);
        } catch (Throwable t) {
            return false;
        }
        Object[] entry = new Object[] { owner, f, originalList, proxy };
        wrapped.add(entry); // 先登记，后面"自我验证"时才能顺着代理找到原表
        try {
            f.setAccessible(true);
            f.set(owner, proxy);
            if (f.get(owner) != proxy) {
                throw new IllegalStateException("字段没有被换成代理");
            }
            return true;
        } catch (Throwable t) {
            wrapped.remove(entry);
            try {
                f.setAccessible(true);
                f.set(owner, originalList);
            } catch (Throwable ignored) {
            }
            return false;
        }
    }

    // ---------------------------------------------------------------- 通道 2

    /** 用 Proxy 实现 IWorldAccess，塞进 World 的部件表（addAccess / 直接 list.add）。 */
    @SuppressWarnings("unchecked")
    private void tryAccessProxy(GameBinder.Log log) {
        if (handles.accessInterface == null) {
            return;
        }
        try {
            accessProxy = Proxy.newProxyInstance(handles.accessInterface.getClassLoader(),
                    new Class<?>[] { handles.accessInterface }, this);
        } catch (Throwable t) {
            log.log("TickPump: 造 IWorldAccess 代理失败: " + t);
            return;
        }
        // addAccess 与 removeAccess 都是"单参数 = IWorldAccess 的 void 方法"，字母顺序不可靠，
        // 不猜名字：调完就看"代理有没有真的出现在 World 的某个 List 字段里"。
        for (Method m : GameShape.entityParamVoidMethods(handles.worldClass, handles.accessInterface)) {
            try {
                m.setAccessible(true);
                m.invoke(handles.world, accessProxy);
            } catch (Throwable t) {
                continue;
            }
            if (isPlaced()) {
                accessVia = "addAccess:" + m.getName() + "()";
                return;
            }
        }
        for (Object o : GameShape.findAccessLists(handles.world, handles.accessInterface)) {
            try {
                ((List<Object>) o).add(accessProxy);
            } catch (Throwable ignored) {
                continue;
            }
            if (isPlaced()) {
                accessVia = "list.add";
                return;
            }
        }
        accessProxy = null;
    }

    /**
     * 扫描 World 上的 List 字段，确认代理已经挂在某个部件表里。
     *
     * <p>不能用 {@code List.contains(accessProxy)}：{@code ArrayList.contains(o)} 是拿 {@code o} 去调
     * {@code o.equals(元素)}，而 {@code o} 就是我们的代理——{@code equals} 会进到 {@link #invoke} 里、
     * 因为没有 delegate 而被当成 {@code IWorldAccess} 的空回调，永远返回 false（真机 0223 实测：
     * 代理明明每 tick 都被回调，这里却报"没装上"，卸载时也就漏掉了它）。所以按引用逐项比对。
     */
    private boolean isPlaced() {
        for (Field f : GameShape.allFields(handles.world.getClass())) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType() != List.class) {
                continue;
            }
            Object v = GameShape.read(f, handles.world);
            if (!(v instanceof List)) {
                continue;
            }
            List<?> list = (List<?>) v;
            for (int i = 0; i < list.size(); i++) {
                Object e;
                try {
                    e = list.get(i);
                } catch (Throwable ignored) {
                    break;
                }
                if (e == accessProxy) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 转发与排空

    public Object invoke(Object p, Method method, Object[] args) throws Throwable {
        Object delegate = delegateFor(p);
        Object result = null;
        Throwable error = null;
        if (delegate != null) {
            try {
                result = method.invoke(delegate, args);
            } catch (Throwable t) {
                error = t.getCause() == null ? t : t.getCause();
            }
        } else {
            result = defaultValue(method.getReturnType()); // IWorldAccess 的回调：什么都不做
        }
        notifyGameThread(delegate != null);
        if (error != null) {
            throw error;
        }
        return result;
    }

    /**
     * 只有真·游戏线程的调用才算数（安装线程/界面线程自己读表时不能顺手排空队列）。
     *
     * @param viaList true = 来自被包装的登记表 List，false = 来自 IWorldAccess 部件回调
     */
    private void notifyGameThread(boolean viaList) {
        long id = Thread.currentThread().getId();
        if (id == installThreadId || isEventThread()) {
            return;
        }
        if (gameThreadId > 0 && id != gameThreadId) {
            return;
        }
        gameCallbacks.incrementAndGet();
        installedVia = viaList ? "list" : "access";
        drain();
    }

    private static boolean isEventThread() {
        String n = Thread.currentThread().getName();
        return n.startsWith("AWT-EventQueue") || n.startsWith("JavaFX Application Thread");
    }

    private static Object defaultValue(Class<?> rt) {
        if (rt == boolean.class) {
            return Boolean.FALSE;
        }
        if (rt == int.class) {
            return Integer.valueOf(0);
        }
        if (rt == long.class) {
            return Long.valueOf(0L);
        }
        if (rt == float.class) {
            return Float.valueOf(0F);
        }
        if (rt == double.class) {
            return Double.valueOf(0D);
        }
        if (rt == short.class) {
            return Short.valueOf((short) 0);
        }
        if (rt == byte.class) {
            return Byte.valueOf((byte) 0);
        }
        if (rt == char.class) {
            return Character.valueOf('\0');
        }
        return null;
    }

    /** 代理 -> 被包装的原始 List；返回 null 说明这是 IWorldAccess 那侧的回调。 */
    private Object delegateFor(Object proxy) {
        for (int i = 0; i < wrapped.size(); i++) {
            if (wrapped.get(i)[3] == proxy) {
                return wrapped.get(i)[2];
            }
        }
        return null;
    }

    /** 提交一个操作：有游戏线程通道就排队，否则就地跑（调用方线程）。 */
    public void submit(final Runnable op) {
        if (handles.pumpRunning) {
            queue.offer(new Runnable() {
                public void run() {
                    try {
                        op.run();
                    } catch (Throwable t) {
                        AgentMain.log("op failed: " + t);
                    }
                }
            });
        } else {
            try {
                op.run();
            } catch (Throwable t) {
                AgentMain.log("op failed (off-thread): " + t);
            }
        }
    }

    public void drain() {
        if (!draining.compareAndSet(false, true)) {
            return; // 防重入：操作里再碰登记表会再次进到 invoke 里
        }
        try {
            Runnable r;
            int guard = 0;
            while ((r = queue.poll()) != null && guard++ < 512) {
                try {
                    r.run();
                } catch (Throwable t) {
                    AgentMain.log("queued op failed: " + t);
                }
            }
        } finally {
            draining.set(false);
        }
    }

    /** 游戏线程回调计数（界面"状态"页显示用）。 */
    public long callbackCount() {
        return gameCallbacks.get();
    }

    public int wrappedLists() {
        return wrapped.size();
    }

    public String channel() {
        return installedVia;
    }

    /** 现在有没有"活的游戏线程通道"（登记表代理还挂在字段上，或部件表里还有我们的代理）。 */
    public boolean hasChannel() {
        return !wrapped.isEmpty() || accessProxy != null;
    }

    /**
     * 游戏线程 = 那个 {@code Runnable} 目标就是 Minecraft 实例的活线程
     * （0413 实测 {@code client.d implements Runnable}，{@code new Thread(this)} 起主循环）。
     */
    private long detectGameThread() {
        if (handles.minecraft == null) {
            return -1;
        }
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
                if (target == handles.minecraft || target == handles.world) {
                    return t.getId();
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /** 还原所有被包装的 List，并把自己从部件表里摘掉。 */
    public void uninstall() {
        queue.clear();
        for (int i = 0; i < wrapped.size(); i++) {
            Object[] w = wrapped.get(i);
            try {
                Field f = (Field) w[1];
                f.setAccessible(true);
                if (f.get(w[0]) instanceof Proxy) {
                    f.set(w[0], w[2]);
                }
            } catch (Throwable ignored) {
            }
        }
        wrapped.clear();
        try {
            for (Object o : GameShape.findAccessLists(handles.world, handles.accessInterface)) {
                List<?> list = (List<?>) o;
                // 同样不能用 list.remove(accessProxy)：那是拿代理去调 equals，永远匹配不上
                for (int i = list.size() - 1; i >= 0; i--) {
                    if (list.get(i) == accessProxy) {
                        ((List<Object>) list).remove(i);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        handles.pumpRunning = false;
    }
}
