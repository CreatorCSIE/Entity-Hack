package org.github.creatorcsie.entityhack.agent;

import java.io.FileWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JOptionPane;

/**
 * Java Agent 入口：注入器 {@code VirtualMachine.loadAgent} 之后，JVM 在独立线程里调用 agentmain，
 * 于是本类的代码运行在 Minecraft 进程内，可以随意反射它的内部对象——不需要改 jar、不需要 -javaagent、
 * 不需要安装 RetroWrapper。
 *
 * 与文档里"retransform 埋点捕获 World"的方案不同，这里全程只用反射 + 动态代理：
 *   1. GameFinder 捞候选根（MinecraftApplet / 游戏线程 target）；
 *   2. GameBinder 用形状判据绑定 World、实体基类、登记表、IWorldAccess；
 *   3. TickPump 把登记表 List 换成透明代理（主通道）/ 混进 IWorldAccess 部件表（补充通道），借到游戏线程；
 *   4. verifyCapabilities 用"探针实验"确定生成口/置死口/死亡标志；
 *   5. EntityWindow 弹出实体操控面板。
 */
public final class AgentMain {

    private AgentMain() {
    }

    public static void agentmain(String args, Instrumentation inst) {
        log("=== EntityHack agent start, java=" + System.getProperty("java.version")
                + " os=" + System.getProperty("os.name"));
        final Instrumentation instrumentation = inst;
        Thread worker = new Thread(new Runnable() {
            public void run() {
                try {
                    Handles handles = null;
                    List<Class<?>> gameClasses = null;
                    long deadline = System.currentTimeMillis() + 90000L;
                    int attempt = 0;
                    while (handles == null && System.currentTimeMillis() < deadline) {
                        gameClasses = loadedGameClasses(instrumentation);
                        for (Object root : GameFinder.collectRootCandidates()) {
                            try {
                                handles = GameBinder.bind(root, gameClasses, SINK);
                                log("located via root: " + root.getClass().getName());
                                break;
                            } catch (Throwable t) {
                                log("candidate " + root.getClass().getName() + " failed: " + oneLine(t));
                            }
                        }
                        if (handles == null) {
                            attempt++;
                            if (attempt % 10 == 0) {
                                log("waiting for world... poll " + attempt + ", loaded game classes="
                                        + (gameClasses == null ? 0 : gameClasses.size()));
                            }
                            Thread.sleep(1000L);
                        }
                    }
                    if (handles == null) {
                        throw new IllegalStateException("90 秒内没找到可绑定的 Indev/Infdev 世界。"
                                + "请确认游戏已进入世界，且版本在 in-20100223 ~ inf-20100415 之间。");
                    }

                    TickPump pump = new TickPump(handles);
                    pump.install(SINK);
                    logRegistries(handles);
                    final Handles bound = handles;
                    // 类池每次现捞。这不是 0227 那个"只剩一个类型"的解药（真机日志证明预加载与现捞
                    // 都改变不了候选数，见 GameBinder.whyNotSpawnable 的说明），它管的是另一件事：
                    // 绑定时如果世界还没加载完，类池本身是空的，冻在快照上清单就永远出不来。
                    final EntityOps.ClassSource pool = new EntityOps.ClassSource() {
                        public List<Class<?>> classes() {
                            return loadedGameClasses(instrumentation);
                        }
                    };
                    final EntityOps ops = new EntityOps(bound, pump, pool);
                    // 探针实验要动登记表，排在游戏线程队列里跑（没有通道时就地跑）
                    pump.submit(new Runnable() {
                        public void run() {
                            GameBinder.verifyCapabilities(bound, pool.classes(), SINK);
                        }
                    });
                    new EntityWindow(ops, bound, pump);
                    log("entity window created");
                    System.out.println("[EntityHack] 注入成功，实体操控窗口已打开");
                } catch (Throwable t) {
                    log("FAILED: " + stackTraceOf(t));
                    showError(t);
                }
            }
        }, "entity-hack-setup");
        worker.setDaemon(true);
        worker.start();
    }

    private static final GameBinder.Log SINK = new GameBinder.Log() {
        public void log(String line) {
            AgentMain.log(line);
        }
    };

    /** 把"窗口那张表到底能扫到多少个实体"记进日志（真机排查全靠这一行）。 */
    private static void logRegistries(Handles h) {
        try {
            List<Object> regs = h.registries();
            StringBuilder sb = new StringBuilder();
            int items = 0;
            for (int i = 0; i < regs.size(); i++) {
                if (!(regs.get(i) instanceof List)) {
                    continue;
                }
                int n = ((List<?>) regs.get(i)).size();
                items += n;
                if (n > 0 && sb.length() < 300) {
                    sb.append(n).append(' ');
                }
            }
            log("登记表 " + regs.size() + " 张 / 共 " + items + " 项 [" + sb + "] -> 实体 "
                    + h.allEntities().size() + " 个");
        } catch (Throwable t) {
            log("登记表统计失败: " + t);
        }
    }

    /** 进程内已加载的"游戏侧"类（用来找实体子类与 EntityList）；排除 JDK 与本工具自身。 */
    private static List<Class<?>> loadedGameClasses(Instrumentation inst) {
        List<Class<?>> out = new ArrayList<Class<?>>();
        Class<?>[] all = inst.getAllLoadedClasses();
        for (int i = 0; i < all.length; i++) {
            Class<?> c = all[i];
            if (GameShape.isJdkClass(c)) {
                continue;
            }
            String n = c.getName();
            if (n.startsWith("org.lwjgl.") || n.startsWith("org.newt.") || n.startsWith("com.google.")
                    || n.startsWith("org.github.creatorcsie.")) {
                continue;
            }
            out.add(c);
        }
        return out;
    }

    private static String oneLine(Throwable t) {
        String msg = t.getClass().getSimpleName() + ": " + t.getMessage();
        int br = msg.indexOf('\n');
        return br > 0 ? msg.substring(0, br) : msg;
    }

    private static void showError(Throwable t) {
        try {
            JOptionPane.showMessageDialog(null, "Entity Hack 注入失败：\n" + t.getMessage() + "\n\n" + stackTraceOf(t),
                    "Entity Hack", JOptionPane.ERROR_MESSAGE);
        } catch (Throwable ignored) {
            System.err.println("[EntityHack] 注入失败：\n" + stackTraceOf(t));
        }
    }

    private static String stackTraceOf(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    /** 诊断日志：{@code <user.home>/entity-hack-agent.log}。 */
    public static synchronized void log(String line) {
        try {
            FileWriter fw = new FileWriter(System.getProperty("user.home") + "/entity-hack-agent.log", true);
            PrintWriter pw = new PrintWriter(fw);
            pw.println("[EntityHack] " + line);
            pw.close();
        } catch (Exception e) {
            e.printStackTrace(new PrintStream(System.err));
        }
    }
}
