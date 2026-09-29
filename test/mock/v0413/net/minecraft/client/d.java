package mock.v0413.net.minecraft.client;

import mock.v0413.net.minecraft.a.a.g;

/**
 * 仿 0413 的 Minecraft：{@code net.minecraft.client.d}（实物 {@code implements Runnable}，
 * {@code public a.a.g d} 就是 World——这两条都是本机 javap 实测）。
 * 游戏线程 = {@code new Thread(minecraftInstance)}，所以 agent 既可以从
 * {@code Thread.target} 捞到根，也可以从根的两个字段里捞到玩家实例。
 */
public final class d implements Runnable {
    public g d;
    public f e;
    public volatile boolean running;
    public long fps;
    public int ticks;
    private long last = System.currentTimeMillis();

    /** = 载入世界（实物里是 {@code a(String)} 建 Level 那一串） */
    public void a() {
        this.d = new g();
        this.e = new f(this.d);
        this.d.d = this.e;
        // 出生点离原点远一点、并留一点余速：让"按幅度分辨三元组"那条路真的被走到（全 0 时只能靠声明顺序）
        this.e.h = 64.0D;
        this.e.i = 64.0D;
        this.e.j = 64.0D;
        this.e.k = 0.1D;
        this.e.m = -0.1D;
        this.d.a(this.e);
        this.running = true;
    }

    public void run() {
        while (this.running) {
            long var1 = System.currentTimeMillis();
            if (var1 - this.last >= 20L) {
                this.last = var1;
                this.d.c();
                this.d.i();
                this.ticks++;
            }
            int var3 = (int) this.d.a(g.class);
            this.fps = var3 + this.d.a.size();
            try {
                Thread.sleep(2L);
            } catch (InterruptedException var4) {
                return;
            }
        }
    }

    public void b() {
        this.running = false;
    }

    public final String c() {
        return "mock-minecraft-0413";
    }
}
