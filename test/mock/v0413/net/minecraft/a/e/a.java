package mock.v0413.net.minecraft.a.e;

import mock.v0413.net.minecraft.a.a.g;

/**
 * 仿 inf-20100413 的 EntityInsentient（{@code net.minecraft.a.e.a}）：抽象中间类。
 * 字段一律用双字母，避免把 {@code a.c.b} 的 {@code d}（世界引用）挡在名字解析之外。
 */
public abstract class a extends mock.v0413.net.minecraft.a.c.a {
    public int ba;
    public int bb;
    protected double bc;
    private int bd;

    public a(g var1) {
        super(var1);
    }

    public void b_() {
        super.b_();
        this.ba++;
    }

    /** 让私有字段参与编译（防止"只写不读"被当成噪声） */
    public final int be() {
        return this.bd + this.bb + (int) this.bc + this.bi;
    }
}
