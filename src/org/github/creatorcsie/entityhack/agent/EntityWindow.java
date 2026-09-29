package org.github.creatorcsie.entityhack.agent;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.lang.reflect.Field;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableModel;

/**
 * 实体操控窗口（运行在 Minecraft 进程内的 EDT 上）。
 * 三个页签：
 *  - 实体：在场实体表（id / 类名 / 坐标 / 死亡位）+ 生成、移除、改坐标/动量 + 字段编辑器；
 *  - 类型注册表：EntityList 的 id -> class 映射（新增/劫持/解除），即那个年代的"实体 id 空间"；
 *  - 状态：本次定界得到的句柄与线程通道情况，便于排错。
 */
public final class EntityWindow {

    private static final NumberFormat FMT = NumberFormat.getNumberInstance(Locale.US);

    static {
        FMT.setMaximumFractionDigits(2);
        FMT.setGroupingUsed(false);
    }

    private final EntityOps ops;
    private final Handles handles;
    private final TickPump pump;
    /** 换关卡监视器：{@code handles.world} 是被绑定的那个实例，换世界后必须有人搬过去。 */
    private final WorldWatch worldWatch;

    private JFrame frame;
    private final List<Object> rows = new ArrayList<Object>();
    private EntityTableModel entityModel;
    private DefaultTableModel fieldModel;
    private JTable entityTable;
    private JTable fieldTable;
    private JComboBox typeCombo;
    private JTextField xField;
    private JTextField yField;
    private JTextField zField;
    private JTextField countField;
    private JTextField mxField;
    private JTextField myField;
    private JTextField mzField;
    private JTextArea statusArea;
    private DefaultTableModel registryModel;
    private JLabel registryNote;
    /** 刷新循环正在动表模型：这段时间里的选中变化是它自己造成的，不该再去重建字段表。 */
    private boolean refreshing;
    /** 字段表现在展示的是哪个实体：选中行被刷新冲掉时，靠它认人（也靠它决定要不要重建）。 */
    private Object fieldTarget;
    /** 已经显示过的最近一次操作结论，避免每 1.2 秒重复刷屏。 */
    private String shownNote;
    /** 同上，但显示的是"换关卡"结论（一次注入里可能换好几次，每次都该说一声）。 */
    private String shownWorldNote;
    /** 上一次写进日志的清单条数；变化 = 游戏又加载了一批怪类（类池每次刷新现捞）。 */
    private int loggedTypeCount = -1;
    /** 已经报过的显示步长（状态页要跟着真值走，不能停在建框那一刻的快照上）。 */
    private int shownStep;
    /** 已经显示过的注册表说明（内容只在绑定时确定一次）。 */
    private String shownRegistryNote;
    /**
     * 实际执行"建框 + setVisible"的线程名。真机排错专用：窗口一出来就整个进程无响应，
     * 十有八九是 EDT 抱着 AWTTreeLock 进原生 pShow、而游戏主循环正要拿这把锁（见 {@link #buildUi()}）。
     */
    private static volatile String lastShowThread;

    /** 窗口已被用户关掉：刷新线程据此退出。 */
    private volatile boolean closed;

    /**
     * 显示流程走到第几步：0 没开始 / 1 建框 / 2 正在 setVisible（原生 pShow，最容易和游戏主线程成环）
     * / 3 setVisible 回来了 / 4 首轮数据填完。看门狗和状态页都读它。
     */
    private static volatile int showStep;
    private static volatile long showStartMs;

    public static String showThread() {
        return lastShowThread;
    }

    public static int showStep() {
        return showStep;
    }

    public EntityWindow(EntityOps ops, Handles handles, TickPump pump) {
        this.ops = ops;
        this.handles = handles;
        this.pump = pump;
        this.worldWatch = new WorldWatch(handles, pump, new GameBinder.Log() {
            public void log(String line) {
                AgentMain.log(line);
            }
        });
        buildUi();
        startTicker();
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        // 原生 show 只能在 EDT 上做：真机试过把 setVisible 排给游戏线程，结果是 EDT 被永远堵死
        // （窗口出来了，两张表 0 行）——AWT 的原生窗口流程要 EDT 配合，谁抱锁都不能绕开这条。
        // 于是死锁只能从另一头拆：让 pShow 不需要游戏主线程回话，见 buildAndShow()。
        startShowWatchdog();
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                buildAndShow();
            }
        });
    }

    private void buildAndShow() {
        lastShowThread = Thread.currentThread().getName();
        showStartMs = System.currentTimeMillis();
        showStep = 1;
        frame = new JFrame("Entity Hack - 实体操控器（" + worldTag() + "）");
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("实体", entitiesTab());
        tabs.addTab("类型注册表", registryTab());
        tabs.addTab("状态", statusTab());
        frame.add(tabs);
        frame.setPreferredSize(new Dimension(1000, 760));
        frame.setLocationRelativeTo(null);
        // 不用 pack()：pack 会先在锁内做一轮原生布局/尺寸设置，把抱 AWTTreeLock 的时间拉长。
        // 真机 0223 的 jstack 抓到过这个环：
        //   EDT   ：setVisible -> 原生 pShow，全程抱着 AWTTreeLock；pShow 里的 ActivateTopLevel
        //          要向"上一个活动顶层窗口"（游戏的 LWJGL 窗口，属于游戏主线程）发 WM_ACTIVATE，
        //          而跨线程 SendMessage 是要等对方线程回话的；
        //   游戏主线程：client.d.run -> client.m.b -> Component.getLocationOnScreen，等的正是 EDT 手里
        //          那把 AWTTreeLock -> 三方成环，整个进程"无响应"。
        // 标成不可激活（WS_EX_NOACTIVATE）再显示，这条激活广播就不发；显示完立刻把可聚焦性还回来，
        // 之后用户点窗口照样能正常键入。
        frame.setSize(1000, 760);
        frame.setFocusableWindowState(false);
        showStep = 2;
        AgentMain.log("show: 在 " + lastShowThread + " 上 setVisible（不可激活模式）");
        frame.setVisible(true);
        showStep = 3;
        AgentMain.log("show: setVisible 返回，用时 " + (System.currentTimeMillis() - showStartMs) + "ms");
        // 可聚焦性延时还回来：不接在 setVisible 后面立刻做，是给游戏主循环留出几帧，
        // 让 AWTTreeLock 先空出来，再去碰那一次原生窗口属性设置。
        restoreFocusLater();
        // 首轮数据也排到下一个 EDT 任务：显示任务越早结束，EDT 越早回到"处理事件"的状态。
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                refreshEntities();
                refreshTypes();
                refreshRegistry();
                showStep = 4;
            }
        });
    }

    /** 把窗口的可聚焦性还回来（只此一次，不做 toFront——那正是会向游戏窗口发激活消息的调用）。 */
    private void restoreFocusLater() {
        Timer timer = new Timer(400, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                ((Timer) e.getSource()).stop();
                if (frame == null || closed) {
                    return;
                }
                try {
                    long t0 = System.currentTimeMillis();
                    frame.setFocusableWindowState(true);
                    AgentMain.log("show: 已还回可聚焦性=" + frame.getFocusableWindowState() + "，用时 "
                            + (System.currentTimeMillis() - t0) + "ms");
                } catch (Throwable t) {
                    AgentMain.log("show: 还回可聚焦性失败: " + t);
                }
            }
        });
        timer.setRepeats(false);
        timer.start();
    }

    /**
     * 真机排错看门狗：原生 show 一旦和游戏主线程在 AWTTreeLock 上成环，进程表现就是「窗口一弹、
     * 整个无响应」，而日志里什么都不会留下。这里显示后盯 15 秒，没走完就把线程栈和死锁环写进日志，
     * 免得每轮都要人肉 jstack 比对。
     */
    private static void startShowWatchdog() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    for (int i = 0; i < 30; i++) {
                        Thread.sleep(500L);
                        if (showStep >= 4 || showStep == 0) {
                            return;
                        }
                    }
                    AgentMain.log("show: 卡在第一步 " + showStep + "，"
                            + (System.currentTimeMillis() - showStartMs) + "ms 没进展，转储线程栈："
                            + dumpThreads());
                } catch (Throwable ignored) {
                }
            }
        }, "entity-hack-show-watchdog");
        t.setDaemon(true);
        t.start();
    }

    /** 精简线程转储：只报死锁环上的线程 + AWT/游戏主线程的状态与栈顶几帧。 */
    private static String dumpThreads() {
        StringBuilder sb = new StringBuilder();
        try {
            java.lang.management.ThreadMXBean bean = java.lang.management.ManagementFactory.getThreadMXBean();
            long[] dead = bean.findDeadlockedThreads();
            sb.append("\n死锁环：").append(dead == null ? "JVM 没检出（可能是原生层阻塞）" : "");
            if (dead != null) {
                for (int i = 0; i < dead.length; i++) {
                    appendThread(sb, bean.getThreadInfo(dead[i], 12));
                }
            }
            java.lang.management.ThreadInfo[] all = bean.getThreadInfo(bean.getAllThreadIds());
            for (int i = 0; i < all.length; i++) {
                java.lang.management.ThreadInfo info = all[i];
                if (info == null) {
                    continue;
                }
                String name = info.getThreadName();
                if (name == null || !important(name)) {
                    continue;
                }
                if (dead != null && contains(dead, info.getThreadId())) {
                    continue;
                }
                appendThread(sb, bean.getThreadInfo(info.getThreadId(), 12));
            }
        } catch (Throwable t) {
            sb.append("转储失败: ").append(t);
        }
        return sb.toString();
    }

    private static boolean important(String name) {
        return name.startsWith("AWT") || name.contains("Minecraft") || name.equals("main")
                || name.startsWith("DestroyJavaVM");
    }

    private static boolean contains(long[] ids, long id) {
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] == id) {
                return true;
            }
        }
        return false;
    }

    private static void appendThread(StringBuilder sb, java.lang.management.ThreadInfo info) {
        if (info == null) {
            return;
        }
        sb.append("\n  ").append(info.getThreadName()).append(" [").append(info.getThreadState()).append(']');
        if (info.getLockName() != null) {
            sb.append(" 等=").append(info.getLockName());
        }
        if (info.getLockOwnerName() != null) {
            sb.append(" 持有人=").append(info.getLockOwnerName());
        }
        if (info.getLockedMonitors().length > 0) {
            sb.append(" 抱锁=").append(info.getLockedMonitors().length).append(" 把");
        }
        StackTraceElement[] frames = info.getStackTrace();
        for (int i = 0; i < frames.length && i < 8; i++) {
            sb.append("\n      ").append(frames[i]);
        }
    }

    private String worldTag() {
        return handles.worldClass == null ? "?" : handles.worldClass.getName();
    }

    private JPanel entitiesTab() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        entityModel = new EntityTableModel();
        entityTable = new JTable(entityModel);
        entityTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        entityTable.setFillsViewportHeight(true);
        JScrollPane entityScroll = new JScrollPane(entityTable);
        // 必须允许被压扁。之前实体表放在 BorderLayout.NORTH，NORTH 是按"首选尺寸"硬占的：
        // 18 行实体 = 460px，620px 的窗口剩下的 160px 塞不下"添加实体 + 修改 + 按钮 + 字段表"，
        // 于是输入框和生成按钮被整片挤成 0 高度 —— 真机上"看不见按钮、没有可输入的框"就是这么来的。
        entityScroll.setMinimumSize(new Dimension(80, 60));

        JPanel actions = new JPanel(new BorderLayout(4, 4));

        // 生成区（全部平铺进一个网格，别再套一层子面板——套进去只会被压成一个格子）
        JPanel spawn = new JPanel(new GridLayout(0, 6, 4, 4));
        spawn.setBorder(BorderFactory.createTitledBorder("添加实体"));
        typeCombo = new JComboBox(new DefaultComboBoxModel());
        typeCombo.setPreferredSize(new Dimension(220, 26));
        spawn.add(typeCombo);
        spawn.add(new JLabel("x"));
        xField = new JTextField();
        spawn.add(xField);
        spawn.add(new JLabel("y"));
        yField = new JTextField();
        spawn.add(yField);
        spawn.add(new JLabel("z"));
        zField = new JTextField();
        spawn.add(zField);
        spawn.add(new JLabel("数量"));
        countField = new JTextField("1");
        spawn.add(countField);
        JButton readPlayer = new JButton("读取玩家坐标");
        spawn.add(readPlayer);
        JButton spawnButton = new JButton("生成");
        spawn.add(spawnButton);
        actions.add(spawn, BorderLayout.NORTH);

        // 修改区
        JPanel modify = new JPanel(new GridLayout(0, 4, 4, 4));
        modify.setBorder(BorderFactory.createTitledBorder("修改选中的 1 个实体（坐标用上面那排 x/y/z）"));
        modify.add(new JLabel("motionX"));
        mxField = new JTextField();
        modify.add(mxField);
        modify.add(new JLabel("motionY"));
        myField = new JTextField();
        modify.add(myField);
        modify.add(new JLabel("motionZ"));
        mzField = new JTextField();
        modify.add(mzField);
        JButton applyPos = new JButton("把 x/y/z 写入选中实体");
        modify.add(applyPos);
        JButton applyMotion = new JButton("写入动量");
        modify.add(applyMotion);
        actions.add(modify, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        final JButton removeButton = new JButton("移除选中");
        final JButton removeAll = new JButton("移除全部（保留玩家）");
        final JButton refresh = new JButton("刷新");
        final JButton deadToggle = new JButton("切换死亡位");
        buttons.add(removeButton);
        buttons.add(removeAll);
        buttons.add(deadToggle);
        buttons.add(refresh);
        actions.add(buttons, BorderLayout.SOUTH);

        // 字段编辑器
        fieldModel = new DefaultTableModel(new Object[] { "字段", "类型", "值", "声明类" }, 0) {
            public boolean isCellEditable(int r, int c) {
                return c == 2 && r >= 0 && r < getFieldRowCountSafe();
            }
        };
        fieldTable = new JTable(fieldModel);
        JScrollPane fieldScroll = new JScrollPane(fieldTable);
        fieldScroll.setMinimumSize(new Dimension(80, 60));
        JPanel fields = new JPanel(new BorderLayout(4, 4));
        fields.setBorder(BorderFactory.createTitledBorder("字段编辑（选中实体后展开；直接改血量/纹理串等都靠它）"));
        JButton writeField = new JButton("写入选中字段");
        writeField.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                writeSelectedField();
            }
        });
        fields.add(fieldScroll, BorderLayout.CENTER);
        fields.add(writeField, BorderLayout.SOUTH);

        JPanel lower = new JPanel(new BorderLayout(4, 4));
        lower.add(actions, BorderLayout.NORTH);
        lower.add(fields, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, entityScroll, lower);
        split.setResizeWeight(0.35);
        split.setDividerLocation(190);
        panel.add(split, BorderLayout.CENTER);

        // 事件
        entityTable.getSelectionModel().addListSelectionListener(new javax.swing.event.ListSelectionListener() {
            public void valueChanged(javax.swing.event.ListSelectionEvent e) {
                if (refreshing || e.getValueIsAdjusting()) {
                    return; // 刷新循环自己清了/恢复了选中不算用户操作；拖动多选时也别每格重建一次
                }
                refreshFields();
            }
        });
        spawnButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                doSpawn();
            }
        });
        readPlayer.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                doReadPlayer();
            }
        });
        applyPos.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                doApplyPosition();
            }
        });
        applyMotion.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                doApplyMotion();
            }
        });
        removeButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                doRemove();
            }
        });
        removeAll.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (JOptionPane.showConfirmDialog(frame, "确认移除世界里除玩家外的全部实体？", "Entity Hack",
                        JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                    int n = ops.removeMatching(null);
                    status("已提交移除 " + n + " 个实体");
                }
            }
        });
        deadToggle.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                Object[] sel = selectedEntities();
                if (sel.length == 0) {
                    return;
                }
                if (handles.deadFlag == null) {
                    status("没有识别出死亡标志字段，无法切换");
                    return;
                }
                for (int i = 0; i < sel.length; i++) {
                    Object dead = GameShape.read(handles.deadFlag, sel[i]);
                    ops.setDead(sel[i], !Boolean.TRUE.equals(dead));
                }
            }
        });
        refresh.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                refreshEntities();
            }
        });
        return panel;
    }

    private int getFieldRowCountSafe() {
        return fieldModel == null ? 0 : fieldModel.getRowCount();
    }

    private JPanel registryTab() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        registryModel = new DefaultTableModel(new Object[] { "id", "class" }, 0) {
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        JTable table = new JTable(registryModel);
        table.setFillsViewportHeight(true);
        panel.add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel form = new JPanel(new GridLayout(0, 2, 4, 4));
        final JTextField idInput = new JTextField();
        form.add(new JLabel("id（如 Creeper / InjectZombie）"));
        form.add(idInput);
        JButton register = new JButton("注册/劫持：把该 id 指向选中类型");
        JButton unregister = new JButton("解除该 id");
        JButton reload = new JButton("刷新映射");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(register);
        buttons.add(unregister);
        buttons.add(reload);
        JPanel top = new JPanel(new BorderLayout(4, 4));
        registryNote = new JLabel(" ");
        registryNote.setVerticalTextPosition(SwingConstants.TOP);
        registryNote.setBorder(BorderFactory.createEmptyBorder(2, 2, 6, 2));
        top.add(registryNote, BorderLayout.NORTH);
        top.add(form, BorderLayout.CENTER);
        panel.add(top, BorderLayout.NORTH);
        panel.add(buttons, BorderLayout.SOUTH);

        register.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                EntityOps.TypeInfo t = selectedType();
                String id = idInput.getText().trim();
                if (t == null || id.length() == 0) {
                    status("请先在\"实体\"页签选类型，并填写 id");
                    return;
                }
                ops.registerId(t.type, id);
                status("已提交 addMapping(" + t.type.getName() + ", \"" + id + "\")");
            }
        });
        unregister.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                String id = idInput.getText().trim();
                if (id.length() == 0) {
                    return;
                }
                ops.unregisterId(id);
                status("已提交 unregister " + id);
            }
        });
        reload.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                refreshRegistry();
            }
        });
        return panel;
    }

    private JPanel statusTab() {
        JPanel panel = new JPanel(new BorderLayout());
        statusArea = new JTextArea();
        statusArea.setEditable(false);
        panel.add(new JScrollPane(statusArea));
        status(handles.describe() + "\nTickPump callbacks=" + pump.callbackCount()
                + (handles.pumpRunning ? "（游戏线程通道：已建立）" : "（无游戏线程通道：操作就地执行，存在与 tick 竞争的风险）")
                + "\n换关卡监视：" + (worldWatch.watching
                        ? "开启（每轮刷新复查游戏实例上那个世界字段有没有被换掉）"
                        : "认不出游戏实例上的世界字段，换世界后必须重新注入")
                + "\n窗口显示线程=" + lastShowThread + "（必须是 EDT；EDT 之外做原生 show 会把 EDT 堵死）"
                + "\n显示流程步长=" + showStep + "（4=已出窗口并填完首轮数据；卡在 2 就是原生 show 和游戏主线程成环）");
        return panel;
    }

    // ---------------------------------------------------------------- actions

    private void doSpawn() {
        EntityOps.TypeInfo t = selectedType();
        if (t == null) {
            status(typeCombo != null && typeCombo.getModel().getSize() == 0
                    ? "类型清单为空：该版本没有带 (World) 构造器的具体实体子类"
                    : "请先在类型下拉框里选一个类型");
            return;
        }
        double[] base = playerPos();
        double x = parse(xField, base[0]);
        double y = parse(yField, base[1]);
        double z = parse(zField, base[2]);
        int count = (int) Math.max(1, parse(countField, 1));
        ops.spawn(t.type, x, y, z, count);
        status("已提交生成 " + count + " 个 " + t.display());
    }

    private void doReadPlayer() {
        double[] p = playerPos();
        xField.setText(FMT.format(p[0]));
        yField.setText(FMT.format(p[1]));
        zField.setText(FMT.format(p[2]));
    }

    private void doApplyPosition() {
        Object[] sel = selectedEntities();
        if (sel.length == 0) {
            status("请先在上面的表里选中实体");
            return;
        }
        double[] base = playerPos();
        double x = parse(xField, base[0]);
        double y = parse(yField, base[1]);
        double z = parse(zField, base[2]);
        ops.moveTo(sel[0], x, y, z);
        status("已提交移动 " + ops.idOf(sel[0]) + " -> " + x + ", " + y + ", " + z);
    }

    private void doApplyMotion() {
        Object[] sel = selectedEntities();
        if (sel.length == 0) {
            return;
        }
        ops.setMotion(sel[0], parse(mxField, 0), parse(myField, 0), parse(mzField, 0));
        status("已提交动量写入 " + ops.idOf(sel[0]));
    }

    private void doRemove() {
        Object[] sel = selectedEntities();
        Object player = GameBinder.currentPlayer(handles);
        int n = 0;
        for (int i = 0; i < sel.length; i++) {
            if (sel[i] == player) {
                status("跳过玩家本身");
                continue;
            }
            ops.remove(sel[i]);
            n++;
        }
        status("已提交移除 " + n + " 个实体");
    }

    private void writeSelectedField() {
        commitFieldEdit(); // 点按钮时焦点离开单元格，先把他刚敲进去的值落实成模型内容，别按旧值写
        int r = fieldTable.getSelectedRow();
        Object[] sel = selectedEntities();
        Object ent = sel.length == 1 ? sel[0] : (sel.length == 0 ? fieldTarget : null);
        if (r < 0 || ent == null) {
            status(r < 0 ? "先在字段表里点一行（选中要改的字段）" : "先选实体，再选字段");
            return;
        }
        String name = String.valueOf(fieldModel.getValueAt(r, 0));
        String value = String.valueOf(fieldModel.getValueAt(r, 2));
        ops.setField(ent, name, value);
        status("已提交 " + name + " = " + value);
    }

    /** 把字段表里正在编辑的单元格落实成模型数据（不依赖 Swing 在焦点转移时是否自动提交）。 */
    private void commitFieldEdit() {
        try {
            if (fieldTable != null && fieldTable.isEditing()) {
                javax.swing.CellEditor editor = fieldTable.getCellEditor();
                if (editor != null) {
                    editor.stopCellEditing();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private EntityOps.TypeInfo selectedType() {
        Object o = typeCombo == null ? null : typeCombo.getSelectedItem();
        return o instanceof EntityOps.TypeInfo ? (EntityOps.TypeInfo) o : null;
    }

    private Object[] selectedEntities() {
        if (entityTable == null) {
            return new Object[0];
        }
        int[] idx = entityTable.getSelectedRows();
        List<Object> out = new ArrayList<Object>();
        if (idx != null) {
            for (int i = 0; i < idx.length; i++) {
                if (idx[i] < rows.size()) {
                    out.add(rows.get(idx[i]));
                }
            }
        }
        return out.toArray(new Object[out.size()]);
    }

    private double[] playerPos() {
        Object p = GameBinder.currentPlayer(handles);
        return p == null ? new double[] { 0, 64, 0 } : ops.position(p);
    }

    private static double parse(JTextField f, double fallback) {
        try {
            return Double.parseDouble(f.getText().trim());
        } catch (Throwable t) {
            return fallback;
        }
    }

    // ---------------------------------------------------------------- refresh

    private void refreshEntities() {
        List<Object> keep = selectedEntityObjects();
        List<Object> next = new ArrayList<Object>();
        try {
            List<Object> list = handles.allEntities();
            for (int i = 0; i < list.size(); i++) {
                next.add(list.get(i));
            }
        } catch (Throwable t) {
            status("列实体失败：" + t);
        }
        next = stableOrder(rows, next);
        boolean membersChanged = !sameIdentity(rows, next);
        rows.clear();
        rows.addAll(next);
        if (entityModel == null) {
            return;
        }
        refreshing = true;
        try {
            if (!membersChanged) {
                // 成员没变、只是坐标在动：只刷值。
                // 绝不能用 fireTableDataChanged()——它会清掉选中行、并把正在编辑的单元格连编辑器一起作废，
                // 表现就是"点一下展开了字段，一秒多以后整张表变白、什么都改不了"。
                if (!rows.isEmpty()) {
                    entityModel.fireTableRowsUpdated(0, rows.size() - 1);
                }
                return;
            }
            entityModel.fireTableDataChanged();
            reselect(keep);
        } finally {
            refreshing = false;
        }
        if (!sameIdentity(keep, selectedEntityObjects())) {
            refreshFields();
        }
    }

    /**
     * 按引用把行序钉住：上一轮显示过的实体留在原来那一行，新出现的追加到末尾。
     *
     * <p>真机 0223 的实体住在 EntityMap 的 8192 格 {@code List[]} 里，实体一走出格子范围，
     * 容器枚举出来的先后就变了。不钉顺序的话"成员没变"这个判断每轮都成立不了，
     * 整表重建照旧每 1.2 秒把选中行和正在敲的编辑器炸掉一次。
     */
    private static List<Object> stableOrder(List<Object> prev, List<Object> fresh) {
        java.util.IdentityHashMap<Object, Boolean> remaining = new java.util.IdentityHashMap<Object, Boolean>();
        for (int i = 0; i < fresh.size(); i++) {
            remaining.put(fresh.get(i), Boolean.TRUE);
        }
        List<Object> out = new ArrayList<Object>(fresh.size());
        for (int i = 0; i < prev.size(); i++) {
            Object o = prev.get(i);
            if (remaining.remove(o) != null) {
                out.add(o);
            }
        }
        for (int i = 0; i < fresh.size(); i++) {
            Object o = fresh.get(i);
            if (remaining.remove(o) != null) {
                out.add(o);
            }
        }
        return out;
    }

    /** 逐个按引用比较（实体没有可靠的 equals，也不能靠 equals——见 TickPump.isPlaced 的教训）。 */
    private static boolean sameIdentity(List<Object> a, List<Object> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) {
                return false;
            }
        }
        return true;
    }

    private List<Object> selectedEntityObjects() {
        List<Object> out = new ArrayList<Object>();
        if (entityTable == null) {
            return out;
        }
        int[] idx = entityTable.getSelectedRows();
        if (idx != null) {
            for (int i = 0; i < idx.length; i++) {
                if (idx[i] < rows.size()) {
                    out.add(rows.get(idx[i]));
                }
            }
        }
        return out;
    }

    /** 表整体重建之后，按"同一个实体对象"把选中行找回来。 */
    private void reselect(List<Object> keep) {
        if (entityTable == null || keep.isEmpty()) {
            return;
        }
        entityTable.clearSelection();
        for (int i = 0; i < rows.size(); i++) {
            for (int k = 0; k < keep.size(); k++) {
                if (rows.get(i) == keep.get(k)) {
                    try {
                        entityTable.addRowSelectionInterval(i, i);
                    } catch (Throwable ignored) {
                    }
                    break;
                }
            }
        }
    }

    private void refreshFields() {
        if (fieldModel == null) {
            return;
        }
        Object[] sel = selectedEntities();
        Object target = sel.length == 1 ? sel[0] : null;
        if (target == null) {
            // 选中丢了（多选、或表刚被重建过一行都没选）：保留用户正在看的这张表，别一上来就清空。
            // 真机 0223 上"字段表整个变白"就是这么来的：清空之后再也等不到一次带选中的调用。
            return;
        }
        boolean editing = fieldTable != null && fieldTable.isEditing();
        if (editing && target == fieldTarget && fieldModel.getRowCount() > 0) {
            return; // 用户正在敲值，不能把他眼前的表抽掉
        }
        if (editing) {
            // 编辑器开着、表却是空的：那是个卡死的编辑器，它会一直占着焦点，
            // 结果就是"整个窗口一个字都打不进去"。先把它取消掉再重建。
            AgentMain.log("字段表：发现卡死的单元格编辑器（表里 0 行），先取消编辑");
            try {
                javax.swing.CellEditor editor = fieldTable.getCellEditor();
                if (editor != null) {
                    editor.cancelCellEditing();
                }
                fieldTable.removeEditor();
            } catch (Throwable ignored) {
            }
        }
        List<EntityOps.FieldInfo> fs;
        try {
            fs = ops.fields(target);
        } catch (Throwable t) {
            AgentMain.log("列字段失败：" + t);
            status("列字段失败：" + t);
            return;
        }
        fieldModel.setRowCount(0);
        for (int i = 0; i < fs.size(); i++) {
            EntityOps.FieldInfo fi = fs.get(i);
            fieldModel.addRow(new Object[] { fi.name(), fi.type(), fi.valueText(), fi.declaring.getSimpleName() });
        }
        fieldTarget = target;
        AgentMain.log("字段表：" + target.getClass().getName() + " 列出 " + fs.size() + " 个字段");
    }

    /**
     * 只把字段表里"值"那一列重新读一遍：行序、选中行、编辑器全部原地保留。
     *
     * <p>用户展开要看的就是 {@code x/y/z}、age 这些每 tick 都在变的字段。整表重建
     * （{@code setRowCount(0)} + 重新 addRow）会把他的选中行和正在敲的字符一起抹掉，
     * 所以这里只 setValueAt，而且跳过他正在编辑的那一格。
     */
    private void refreshFieldValues() {
        if (fieldModel == null || fieldTable == null || fieldModel.getColumnCount() < 3) {
            return;
        }
        Object[] sel = selectedEntities();
        Object target = sel.length == 1 ? sel[0] : fieldTarget;
        if (target == null) {
            return;
        }
        List<EntityOps.FieldInfo> fs;
        try {
            fs = ops.fields(target);
        } catch (Throwable t) {
            return;
        }
        boolean editing = fieldTable.isEditing();
        int editingRow = fieldTable.getSelectedRow();
        int n = Math.min(fieldModel.getRowCount(), fs.size());
        for (int i = 0; i < n; i++) {
            EntityOps.FieldInfo fi = fs.get(i);
            if (editing && i == editingRow) {
                continue;
            }
            if (!fi.name().equals(String.valueOf(fieldModel.getValueAt(i, 0)))) {
                continue; // 字段顺序和表不一致（理论上不会发生），交给整表重建
            }
            String text = fi.valueText();
            if (!text.equals(String.valueOf(fieldModel.getValueAt(i, 2)))) {
                fieldModel.setValueAt(text, i, 2);
            }
        }
    }

    private void refreshTypes() {
        if (typeCombo == null) {
            return;
        }
        List<EntityOps.TypeInfo> ts;
        try {
            ts = ops.spawnableTypes();
        } catch (Throwable t) {
            status("列类型失败：" + t);
            return;
        }
        DefaultComboBoxModel model = (DefaultComboBoxModel) typeCombo.getModel();
        if (ts.size() != loggedTypeCount) {
            // 清单条数变化就记一行：真机 0227 靠这一行确认"清单到底是几个、分别是谁"
            //（类全名，混淆包末段全都重名，只报末段的日志没法查）
            loggedTypeCount = ts.size();
            StringBuilder sb = new StringBuilder("可生成清单 " + ts.size() + " 类：");
            for (int i = 0; i < ts.size(); i++) {
                sb.append(i > 0 ? "、" : "").append(ts.get(i).type.getName());
            }
            AgentMain.log(sb.toString());
        }
        if (sameTypes(model, ts)) {
            return; // 一模一样就别动模型：removeAllElements() 会把用户刚选中的类型抹成 null，生成按钮就永远"类型清单为空"
        }
        Object selected = typeCombo.getSelectedItem();
        Class<?> keepType = selected instanceof EntityOps.TypeInfo ? ((EntityOps.TypeInfo) selected).type : null;
        model.removeAllElements();
        for (int i = 0; i < ts.size(); i++) {
            model.addElement(ts.get(i));
        }
        if (keepType != null) {
            for (int i = 0; i < model.getSize(); i++) {
                Object o = model.getElementAt(i);
                if (o instanceof EntityOps.TypeInfo && ((EntityOps.TypeInfo) o).type == keepType) {
                    model.setSelectedItem(o);
                    break;
                }
            }
        }
    }

    private static boolean sameTypes(DefaultComboBoxModel model, List<EntityOps.TypeInfo> ts) {
        if (model.getSize() != ts.size()) {
            return false;
        }
        for (int i = 0; i < ts.size(); i++) {
            Object o = model.getElementAt(i);
            if (!(o instanceof EntityOps.TypeInfo)) {
                return false;
            }
            EntityOps.TypeInfo a = (EntityOps.TypeInfo) o;
            EntityOps.TypeInfo b = ts.get(i);
            if (a.type != b.type || !String.valueOf(a.name).equals(String.valueOf(b.name))) {
                return false;
            }
        }
        return true;
    }

    private void refreshRegistry() {
        if (registryModel == null) {
            return;
        }
        updateRegistryNote();
        List<String[]> ids = new ArrayList<String[]>();
        try {
            ids = ops.registryIds();
        } catch (Throwable t) {
            status("列注册表失败：" + t);
        }
        List<Object> flat = new ArrayList<Object>();
        for (int i = 0; i < ids.size(); i++) {
            flat.add(ids.get(i)[0]);
            flat.add(ids.get(i)[1]);
        }
        List<Object> current = new ArrayList<Object>();
        for (int r = 0; r < registryModel.getRowCount(); r++) {
            current.add(registryModel.getValueAt(r, 0));
            current.add(registryModel.getValueAt(r, 1));
        }
        if (sameIdentity(current, flat)) {
            return;
        }
        registryModel.setRowCount(0);
        for (int i = 0; i < ids.size(); i++) {
            registryModel.addRow(new Object[] { ids.get(i)[0], ids.get(i)[1] });
        }
    }

    /**
     * 类型注册表页签顶部的说明。0223/0227 定界不到 EntityList（Indev 的 LevelLoader 是硬编码 if 链），
     * 那张表本来就该是空的——不写清楚就会被当成"这个功能没实现"。
     */
    private void updateRegistryNote() {
        if (registryNote == null) {
            return;
        }
        String note;
        if (hasTypeRegistry()) {
            note = "本版本有类型注册表（id 走 "
                    + (handles.stringToClassMap == null ? "只有 addMapping"
                            : "字段 " + fieldName(handles.stringToClassMap) + " 与 " + fieldName(handles.classToStringMap))
                    + (handles.addMappingMethod == null ? "，没有 addMapping（直接写双 Map）"
                            : "，addMapping=" + handles.addMappingMethod.getName())
                    + "）。注册一个新 id 之后，存档里写这个 id 的实体会被反序列化成你选的类型。";
        } else {
            note = "本版本没有 EntityList 式的类型注册表（状态页签里 EntityList=none）。"
                    + "Indev 的 LevelLoader 是硬编码 if 链，改它需要 retransform 字节码，不在本工具范围内；"
                    + "生成实体请直接用\"实体\"页签的类型下拉框。";
        }
        if (ops != null && ops.lastFilterNote != null) {
            note = note + "\n\n生成清单按\"渲染器不会当场 NPE\"过滤，被踢掉的是：" + ops.lastFilterNote;
        }
        note = "<html><p width='820'>" + note + "</p></html>";
        if (!note.equals(shownRegistryNote)) {
            shownRegistryNote = note;
            registryNote.setText(note); // 每 1.2 秒 setText 会让这一页一直重绘，内容没变就别动
        }
    }

    private static String fieldName(Field f) {
        return f == null ? "?" : f.getName();
    }

    private boolean hasTypeRegistry() {
        return handles.addMappingMethod != null
                || (handles.stringToClassMap != null && handles.classToStringMap != null);
    }

    private void status(final String line) {
        if (statusArea == null) {
            AgentMain.log(line);
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                statusArea.append(line + "\n");
            }
        });
    }

    private void startTicker() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (!closed) {
                    try {
                        Thread.sleep(1200L);
                    } catch (Throwable ignored) {
                        return;
                    }
                    if (closed) {
                        return;
                    }
                    // 换关卡检查放在这里、也就是非 EDT 线程上：它可能要重挂游戏线程通道（等首次回调），
                    // 排在 invokeLater 里就是拿界面线程去赌一次卡顿。
                    worldWatch.tick();
                    // 表模型必须在 EDT 上改：这个线程读的是游戏对象，写的是 Swing 数据模型
                    SwingUtilities.invokeLater(new Runnable() {
                            public void run() {
                                if (frame == null) {
                                     return; // 还没建好（buildAndShow 排在同一个 EDT 队列里），本轮跳过
                                 }
                                 if (!frame.isDisplayable()) {
                                     closed = true; // 用户已关窗：别再刷一个已经 dispose 的模型
                                     return;
                                 }
                                try {
                                    refreshEntities();
                                    refreshTypes();
                                    refreshFieldValues();
                                    refreshRegistry();
                                } catch (Throwable ignored) {
                                }
                                String note = EntityOps.lastNote;
                                if (note != null && !note.equals(shownNote)) {
                                    shownNote = note;
                                    status(note);
                                }
                                String wnote = worldWatch.lastNote;
                                if (wnote != null && !wnote.equals(shownWorldNote)) {
                                    shownWorldNote = wnote;
                                    status(wnote);
                                }
                                if (showStep != shownStep) {
                                    shownStep = showStep; // 状态页那行是建框时写的快照，得跟着真值走
                                    status("显示流程步长=" + shownStep + "（4=窗口已显示且首轮数据已填完）");
                                }
                            }
                        });
                }
            }
        }, "entity-hack-ticker");
        t.setDaemon(true);
        t.start();
    }

    /** 在场实体表模型。 */
    private final class EntityTableModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        private final String[] cols = { "#", "类型 id", "实现类", "x", "y", "z", "dead" };

        /**
         * 一张都不给编辑。{@code AbstractTableModel} 的默认实现是"全部放开"，真机 0223 上就是这个坑：
         * 点实体行会就地弹出个单元格编辑器（用户看到的"更多信息"），1.2 秒后 {@code fireTableRowsUpdated}
         * 一到，Swing 又把编辑器关掉 —— 表现就是"很快变白然后消失，整个不可编辑"。
         * 改值一律走下面的字段表 + 「写入选中字段」。
         */
        public boolean isCellEditable(int r, int c) {
            return false;
        }

        public int getRowCount() {
            return rows.size();
        }

        public int getColumnCount() {
            return cols.length;
        }

        public String getColumnName(int i) {
            return cols[i];
        }

        public Object getValueAt(int r, int c) {
            Object e = rows.get(r);
            switch (c) {
            case 0:
                return Integer.valueOf(r);
            case 1:
                return ops.idOf(e);
            case 2:
                return e.getClass().getName();
            case 3:
            case 4:
            case 5: {
                double[] p = ops.position(e);
                return FMT.format(p[c - 3]);
            }
            case 6:
                Object dead = handles.deadFlag == null ? null : GameShape.read(handles.deadFlag, e);
                return Boolean.TRUE.equals(dead) ? "DEAD" : "";
            default:
                return "";
            }
        }
    }
}
