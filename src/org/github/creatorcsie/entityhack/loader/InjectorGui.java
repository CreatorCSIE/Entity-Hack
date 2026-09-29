package org.github.creatorcsie.entityhack.loader;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.table.AbstractTableModel;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

/**
 * 注入器 GUI：列出本机所有 JVM，选中正在跑 Indev/Infdev 的那个，一键注入。
 * 与 Position-Hack 同一个交互模型（列表 + 注入 + 日志），只是目标年代换成了 2010 上半年。
 */
public final class InjectorGui extends JFrame {

    private static final long serialVersionUID = 1L;

    private final VmTableModel tableModel = new VmTableModel();
    private final JTable table = new JTable(tableModel);
    private final JTextArea logArea = new JTextArea();
    private final JButton refreshButton = new JButton("刷新进程列表");
    private final JButton injectButton = new JButton("注入选中进程");
    private final JButton probeButton = new JButton("离线探针 inf-20100415");

    public InjectorGui() {
        super("Entity Hack - 注入器");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(6, 6));

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(70);
        table.getColumnModel().getColumn(1).setPreferredWidth(420);
        add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new BorderLayout(6, 6));
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttonPanel.add(refreshButton);
        buttonPanel.add(injectButton);
        buttonPanel.add(probeButton);
        bottomPanel.add(buttonPanel, BorderLayout.NORTH);

        logArea.setEditable(false);
        logArea.setBackground(new Color(240, 240, 240));
        logArea.setRows(10);
        bottomPanel.add(new JScrollPane(logArea), BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);

        refreshButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                refresh();
            }
        });
        injectButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                injectSelected();
            }
        });
        probeButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                runProbe();
            }
        });

        setSize(700, 560);
        setLocationRelativeTo(null);
        refresh();
        log("提示：注入前请先用 betacraft 等启动器进入 Indev/Infdev 世界（in-20100223 ~ inf-20100415）。");
        log("提示：不想开游戏时，可以先点\"离线探针\"验证定界逻辑认不认这些混淆 jar。");
        setVisible(true);
    }

    private void log(String line) {
        logArea.append(line + "\n");
    }

    private void refresh() {
        try {
            List<VirtualMachineDescriptor> vms = VirtualMachine.list();
            tableModel.setVms(vms);
            log("已找到 " + vms.size() + " 个 JVM 进程，请选择正在运行 Indev/Infdev 的那一个");
        } catch (Exception e) {
            log("列出 JVM 进程失败：" + e);
        }
    }

    private void injectSelected() {
        int row = table.getSelectedRow();
        if (row < 0) {
            log("请先在表格中选择一个进程");
            return;
        }
        final String pid = tableModel.getPid(row);
        injectButton.setEnabled(false);
        Thread worker = new Thread(new Runnable() {
            public void run() {
                try {
                    Main.attachTo(pid, new Main.LogSink() {
                        public void log(String line) {
                            InjectorGui.this.log(line);
                        }
                    });
                    log("注入完成。若窗口没出现，请检查 ~/entity-hack-agent.log（游戏多半还没进入世界）。");
                } catch (final Exception e) {
                    log("注入失败：" + e);
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    log(sw.toString());
                } finally {
                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            injectButton.setEnabled(true);
                        }
                    });
                }
            }
        }, "entity-hack-inject");
        worker.setDaemon(true);
        worker.start();
    }

    private void runProbe() {
        logArea.append("—— 离线探针 ——\n");
        try {
            java.io.File f = new java.io.File(System.getenv("APPDATA"),
                    ".betacraft/versions/inf-20100415.jar");
            if (!f.isFile()) {
                log("找不到 " + f + "；可以用命令行：run.bat probe <jar 路径>");
                return;
            }
            log(JarProbe.probeAll(java.util.Collections.singletonList(f)));
        } catch (Throwable t) {
            log("探针失败：" + t);
        }
    }

    private static final class VmTableModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        private static final String[] COLUMNS = { "PID", "进程描述" };
        private List<VirtualMachineDescriptor> vms = new ArrayList<VirtualMachineDescriptor>();

        void setVms(List<VirtualMachineDescriptor> vms) {
            this.vms = vms;
            fireTableDataChanged();
        }

        public int getRowCount() {
            return vms.size();
        }

        public int getColumnCount() {
            return COLUMNS.length;
        }

        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        public Object getValueAt(int rowIndex, int columnIndex) {
            VirtualMachineDescriptor vmd = vms.get(rowIndex);
            return columnIndex == 0 ? vmd.id() : vmd.displayName();
        }

        String getPid(int rowIndex) {
            return vms.get(rowIndex).id();
        }
    }
}
