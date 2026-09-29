package mock.v0223.net.minecraft.client;

import mock.v0223.net.minecraft.a.a.c;
import mock.v0223.net.minecraft.a.a.g;

/**
 * 仿 in-20100223 的 Minecraft：{@code net.minecraft.client.d}（实测 {@code implements Runnable}，
 * {@code public a.a.g d} 就是 World）。
 */
public final class d implements Runnable {
    public g d;
    public f e;
    /** 仿 RenderEngine（{@code client.a.b}）：实测它同时躺在 Minecraft 上和 {@code worldAccesses} 里。 */
    public mock.v0223.net.minecraft.client.a.b z;
    public volatile boolean running;
    public int ticks;
    public long last = System.currentTimeMillis();

    public void a() {
        this.d = new g();
        this.d.a(new c());
        this.e = new f(this.d);
        this.d.y = this.e;
        this.z = new mock.v0223.net.minecraft.client.a.b(this.d);
        this.d.a(this.z);
        // 出生点是玩家自己构造器里经 setPositionAndRotation 放的（坐标+盒子+prevPos 一起对齐），
        // 这里只剩"给一点余速"——见 v0413 的同名说明（float 版）。
        this.e.k = 0.1F;
        this.e.m = -0.1F;
        this.d.a(this.e);
        this.running = true;
    }

    public void run() {
        while (this.running) {
            this.d.c();
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
        return "mock-minecraft-0223";
    }
}
