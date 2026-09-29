package mock.v0227.net.minecraft.client;

import mock.v0227.net.minecraft.a.a.e;

/**
 * 仿 inf-20100227 的 Minecraft：{@code net.minecraft.client.d}（实测 {@code implements Runnable}、
 * {@code public a.a.e d} 是 World）。注意主循环里<b>没有任何</b>驱动实体的调用——这就是"死壳期"。
 */
public final class d implements Runnable {
    public e d;
    public f e;
    public volatile boolean running;
    public int ticks;

    public void a() {
        this.d = new e();
        this.e = new f(this.d);
        this.d.b = this.e;
        this.e.h = 40.0D;
        this.e.i = 64.0D;
        this.e.j = 40.0D;
        this.running = true;
    }

    public void run() {
        while (this.running) {
            this.d.a();
            this.ticks++;
            try {
                Thread.sleep(20L);
            } catch (InterruptedException var2) {
                return;
            }
        }
    }

    public void b() {
        this.running = false;
    }

    public final String c() {
        return "mock-minecraft-0227";
    }
}
