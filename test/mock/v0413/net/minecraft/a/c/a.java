package mock.v0413.net.minecraft.a.c;

import mock.v0413.net.minecraft.a.a.g;

/**
 * 仿 inf-20100413 的 EntityLiving：{@code net.minecraft.a.c.a}。
 *
 * <p>关键点（踩过的坑）：混淆名是按"继承链继续往后排"的方式发号的，父类 {@code a.c.b} 已经用掉了
 * {@code a..N} 这些单字母，子类必须改用双字母（{@code ba/bb/...}）。否则子类的 {@code int d}
 * 会把父类的 {@code protected g d}（世界引用）挡在名字解析之外，跨包子类的 {@code this.d} 直接编译失败。
 *
 * <p>形状判据依赖这一点：本类<b>没有</b> ≥3 连续同型 float/double，所以不会被误认成实体基类。
 */
public abstract class a extends b {
    public int ba;
    public int bb;
    public float bc;
    public float bd;
    public int be;
    public int bf;
    /** = texture（实体纹理路径） */
    public String bg = "";
    public String bh = "";
    protected int bi;
    protected int bj;
    private int bk;
    private int bl;

    public a(g var1) {
        super(var1);
        this.ba = 10;
        this.bb = 10;
        this.bc = 0.5F;
    }

    public void b_() {
        super.b_();
        this.bi++;
    }

    public final void b(int var1) {
        this.ba -= var1;
        if (this.ba <= 0) {
            this.u = true;
        }
    }

    /** = getHealth() */
    public final float c() {
        return (float) this.ba;
    }
}
