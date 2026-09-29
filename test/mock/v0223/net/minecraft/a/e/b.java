package mock.v0223.net.minecraft.a.e;

import mock.v0223.net.minecraft.a.a.g;

/** 仿 Indev 的怪物叶类：{@code a.e.b}。这个年代还没有 EntityList 注册表（id 走 LevelLoader 的硬编码链）。 */
public final class b extends mock.v0223.net.minecraft.a.c.a {
    public int za;
    private int zb = 20;

    public b(g var1) {
        super(var1);
        this.bg = "/mob/zombie.png";
        this.N = "Zombie";
    }

    public void a_() {
        super.a_();
        if (--this.zb <= 0) {
            this.zb = 20;
            this.za = this.d.g.nextInt(3);
        }
    }
}
