package mock.v0413.net.minecraft.a.e;

import mock.v0413.net.minecraft.a.a.g;

/** 仿 EntityCreeper：{@code a.e.c}，注册 id = "Creeper"（mock 里唯一"能生成又认得 id"的 mob）。 */
public final class c extends a {
    public int ca;
    private int cb = 30;

    public c(g var1) {
        super(var1);
        this.bg = "/mob/creeper.png";
        this.w = 0.6F;
        this.x = 1.7F;
    }

    public void b_() {
        super.b_();
        if (--this.cb <= 0) {
            this.cb = 30;
            this.ca = this.d.g.nextInt(2);
        }
    }
}
