package org.github.creatorcsie.entityhack.agent;

/**
 * 世界切换监视器：换关卡 / 回菜单再进世界之后，把工具挪到<b>新</b>世界上去。
 *
 * <p>为什么非要有这个（真机表现："换了世界，实体列表不再更新"）：老 Minecraft 换关卡不是清空复用，
 * 而是 {@code new World(...)} 之后把 Minecraft 实例上的世界字段<b>整个换掉</b>。旧 World 对象并不会被清空——
 * 里面的登记表、{@code EntityMap} 格子原样躺在内存里，只是游戏再也不去遍历它。我们绑定时抓住的是
 * "那一刻的那个实例"，于是窗口继续扫旧世界，列表永远停在换图前那一刻。
 *
 * <p>比列表过期更糟的是通道：{@link TickPump} 的代理是<b>替换字段</b>装进旧 World 的登记表上的，
 * 新世界的表没人回调 → {@code pump.submit} 排进去的操作（生成/移除/改坐标）永远不会被执行，
 * 看起来就是"按钮按了没反应"。所以切换时必须：拆旧代理 → 换 {@code h.world} → 在新世界上重挂。
 *
 * <p>{@link #tick()} 会等第一次游戏线程回调（最长 6 秒），因此<b>只能在非 EDT 线程调用</b>
 * ——现由 {@code EntityWindow} 的刷新线程每轮调一次。
 */
public final class WorldWatch {

    private final Handles handles;
    private final TickPump pump;
    private final GameBinder.Log log;

    /** 刚被换掉的旧 World：新实例还没出现之前，别把它自己认成"又一个新世界"（否则来回抖）。 */
    private Object discarded;
    /**
     * 这个世界到底能不能被监视：只有"游戏实例上直接攥着一个 World 类型的字段"才复查得动
     * （真机 0223/0413 都是 {@code Minecraft.currentLevel} 这种）。认不出来就别谎称"开启"。
     */
    public final boolean watching;
    /** 最近一次切换的结论；null = 这次注入以来还没换过世界。界面状态区据此提示。 */
    public volatile String lastNote;
    /** 下一次"补挂通道"的时间点与已试次数（见 {@link #retryAttach()}）。 */
    private long nextRetryAt;
    private int retries;
    /** 补挂通道的次数上限：登记表里已经有实体、却老是挂不上时，别把日志刷没完。 */
    private static final int MAX_RETRIES = 10;

    public WorldWatch(Handles handles, TickPump pump, GameBinder.Log log) {
        this.handles = handles;
        this.pump = pump;
        this.log = log;
        this.watching = GameBinder.canWatchWorld(handles);
        if (!watching) {
            log.log("世界切换监视：认不出 " + (handles.minecraft == null ? "游戏实例"
                    : handles.minecraft.getClass().getName()) + " 上指着 World 的字段，换世界后需要重新注入");
        }
    }

    /** 发现取代者就把句柄和游戏线程通道一起搬过去；返回 true = 这一轮真的换了世界。 */
    public boolean tick() {
        if (!watching) {
            return false;
        }
        Object fresh;
        try {
            fresh = GameBinder.replacementWorld(handles, discarded);
        } catch (Throwable t) {
            log.log("世界切换检查失败: " + t);
            return false;
        }
        if (fresh == null) {
            retryAttach();
            return false;
        }
        Object old = handles.world;
        log.log("世界切换：World 实例 " + id(old) + " -> " + id(fresh) + "，重挂游戏线程通道");
        try {
            pump.uninstall(); // 趁 handles.world 还是旧的，先把旧世界字段里的代理还原掉
            handles.world = fresh;
            boolean channel = pump.attach(log);
            discarded = old;
            retries = 0;
            nextRetryAt = 0L;
            lastNote = "已跟随新载入的世界（旧世界那批实体已作废；游戏线程通道"
                    + (channel ? "已重挂" : "没重挂上，操作将就地执行") + "）";
            log.log("世界切换完成：" + lastNote + "，在场实体 " + handles.allEntities().size() + " 个");
            return true;
        } catch (Throwable t) {
            lastNote = "世界切换后重挂通道失败：" + t + "（请在界面上按「重新注入」或重启游戏）";
            log.log("world switch failed: " + t);
            return false;
        }
    }

    /**
     * 补挂游戏线程通道。{@link TickPump} 只包装"元素是实体"的 {@code List}，
     * 刚换进新世界的那一瞬间登记表常常还是空的（真机 0227 的日志：
     * "世界切换完成…游戏线程通道没重挂上，在场实体 0 个"，随后连续 5 次补挂全落空就永久放弃了，
     * 等世界里有实体时已经没有人在试了）——所以判据改成<b>看实体在不在场</b>而不是数尝试次数：
     * 空表时安静等着，一旦有实体就每 5 秒试一次，累计试满 {@value #MAX_RETRIES} 次才认输
     * （免得真的挂不上的版本被无限刷屏）。
     */
    private void retryAttach() {
        if (pump.hasChannel() || retries >= MAX_RETRIES) {
            return;
        }
        int present;
        try {
            present = handles.allEntities().size();
        } catch (Throwable t) {
            return;
        }
        if (present == 0) {
            return; // 登记表还空着，挂不上也没什么可试的，安静等实体出现
        }
        long now = System.currentTimeMillis();
        if (now < nextRetryAt) {
            return;
        }
        retries++;
        nextRetryAt = now + 5000L;
        log.log("补挂游戏线程通道（第 " + retries + " 次：登记表里有 " + present
                + " 个实体，之前表是空的包装不上）");
        try {
            pump.attach(log);
        } catch (Throwable t) {
            log.log("补挂通道失败: " + t);
        }
    }

    private static String id(Object o) {
        return o == null ? "null" : o.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(o));
    }
}
