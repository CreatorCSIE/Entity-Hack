package mock.v0413.net.minecraft.a.e;

import mock.v0413.net.minecraft.a.a.g;

/** 仿 EntityItem：{@code a.e.b}，在注册表里叫 "item"。 */
public final class b extends mock.v0413.net.minecraft.a.c.b {
    public int ba;
    private int bb;

    public b(g var1) {
        super(var1);
        this.w = 0.25F;
        this.x = 0.25F;
        this.ba = 0;
    }

    public void b_() {
        super.b_();
        this.ba++;
    }

    public final int bc() {
        return this.bb;
    }
}
