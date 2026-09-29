package mock.v0223.net.minecraft.client;

import mock.v0223.net.minecraft.a.a.g;

/** 仿 Indev 的 LocalPlayer：{@code client.f}。 */
public class f extends mock.v0223.net.minecraft.a.c.a {
    public int Za;

    public f(g var1) {
        super(var1);
        this.N = "LocalPlayer";
        this.bg = "/char/steve.png";
        // 实物 {@code EntityPlayer.java:38} 是在自己的构造器里经
        // {@code setPositionAndRotation(world.xSpawn, ...)} 放位置的——坐标、碰撞盒、prevPos 一起对齐。
        // 所以真机里玩家绝不会是"读数 64、盒子还在原点"的状态（那正是裸写 h/i/j 的外挂会造成的）。
        this.a(64.0F, 64.0F, 64.0F, 0.0F, 0.0F);
    }

    public void a_() {
        super.a_();
        this.Za++;
    }

    public final String o() {
        return "LocalPlayer";
    }
}
