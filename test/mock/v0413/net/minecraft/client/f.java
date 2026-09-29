package mock.v0413.net.minecraft.client;

import mock.v0413.net.minecraft.a.a.g;

/** 仿 LocalPlayer：{@code client.f}（继承链上第一个"有 (World) 构造器且在 EntityList 里注册"的玩家类）。 */
public class f extends mock.v0413.net.minecraft.a.c.a {
    public int Za;
    private float na = 0.1F;

    public f(g var1) {
        super(var1);
        this.N = "LocalPlayer";
        this.bg = "/char/steve.png";
    }

    public void b_() {
        super.b_();
        this.Za++;
    }

    public final String o() {
        return "LocalPlayer";
    }

    public final float pb() {
        return this.na;
    }
}
