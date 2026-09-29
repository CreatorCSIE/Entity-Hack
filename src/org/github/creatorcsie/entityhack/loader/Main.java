package org.github.creatorcsie.entityhack.loader;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.swing.SwingUtilities;

import com.sun.tools.attach.VirtualMachine;

/**
 * 注入器入口（独立进程，不依赖 Minecraft）。
 * 用法：
 *   java -cp "entity-hack.jar;lib\tools.jar" ...loader.Main                       启动 GUI
 *   java -cp "entity-hack.jar;lib\tools.jar" ...loader.Main --attach &lt;pid&gt;       直接注入
 *   java -cp "entity-hack.jar;lib\tools.jar" ...loader.Main probe &lt;jar|版本名&gt;    离线结构探针
 */
public final class Main {

    private Main() {
    }

    /** 日志回调。 */
    public interface LogSink {
        void log(String line);
    }

    public static void main(String[] args) {
        if (args.length >= 2 && "--attach".equals(args[0])) {
            final String pid = args[1];
            try {
                attachTo(pid, stdout());
            } catch (Exception e) {
                System.err.println("注入失败：" + e);
                e.printStackTrace();
                System.exit(1);
            }
            return;
        }
        if (args.length >= 1 && "probe".equals(args[0])) {
            List<File> jars = resolveTargets(args);
            if (jars.isEmpty()) {
                System.err.println("没找到目标 jar。用法：run.bat probe inf-20100415 或 probe <完整路径.jar>，"
                        + "或 probe all（跑文档矩阵里那 7 个版本）。");
                System.exit(2);
            }
            System.out.println(JarProbe.probeAll(jars));
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                new InjectorGui();
            }
        });
    }

    private static LogSink stdout() {
        return new LogSink() {
            public void log(String line) {
                System.out.println(line);
            }
        };
    }

    /** {@code probe all} = 分析文档矩阵里的 7 个实物 jar；否则按版本名/路径解析。 */
    private static List<File> resolveTargets(String[] args) {
        List<File> out = new ArrayList<File>();
        if (args.length >= 2 && "all".equalsIgnoreCase(args[1])) {
            String[] versions = { "in-20100223", "inf-20100227", "inf-20100313", "inf-20100316", "inf-20100413",
                    "inf-20100414", "inf-20100415" };
            for (int i = 0; i < versions.length; i++) {
                File f = resolve(versions[i]);
                if (f != null) {
                    out.add(f);
                }
            }
            return out;
        }
        for (int i = 1; i < args.length; i++) {
            File f = resolve(args[i]);
            if (f != null) {
                out.add(f);
            } else {
                System.err.println("跳过找不到的目标：" + args[i]);
            }
        }
        return out;
    }

    /** 版本名（inf-20100415）或 jar 路径都能接；优先 %APPDATA%\.betacraft\versions。 */
    private static File resolve(String spec) {
        File direct = new File(spec);
        if (direct.isFile()) {
            return direct;
        }
        String name = spec.endsWith(".jar") ? spec : spec + ".jar";
        File betacraft = new File(System.getenv("APPDATA"), ".betacraft" + File.separator + "versions"
                + File.separator + name);
        if (betacraft.isFile()) {
            return betacraft;
        }
        File inDir = new File(name);
        return inDir.isFile() ? inDir : null;
    }

    /** 把当前 jar 作为 agent 注入目标 JVM；agentmain 会在 Minecraft 进程里弹出实体窗口。 */
    public static void attachTo(String pid, LogSink sink) throws Exception {
        VirtualMachine vm = VirtualMachine.attach(pid);
        try {
            String jarPath = currentJarPath();
            sink.log("Injecting " + jarPath + " -> PID " + pid + " ...");
            vm.loadAgent(jarPath);
            sink.log("Injection succeeded! 实体窗口应当已在 Minecraft 进程内弹出（若游戏还没进世界，agent 会轮询等待）。");
        } finally {
            vm.detach();
        }
    }

    public static String currentJarPath() throws Exception {
        File location = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (location.isDirectory()) {
            throw new IllegalStateException("当前从 class 目录运行，无法注入。请先运行 compile.bat 生成 entity-hack.jar，再从 jar 启动。");
        }
        return location.getAbsolutePath();
    }
}
