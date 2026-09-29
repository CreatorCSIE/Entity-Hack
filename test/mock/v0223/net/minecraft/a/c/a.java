package mock.v0223.net.minecraft.a.c;

import mock.v0223.net.minecraft.a.a.g;

/**
 * 仿 indev-20100223 的 EntityLiving：{@code net.minecraft.a.c.a}。
 * 子类字段一律双字母，不能把父类 {@code a.c.b} 的单字母（尤其 {@code d}=世界引用）挡掉。
 */
public abstract class a extends b {
    public int ba;
    public int bb;
    public float bc;
    private float bd;
    private int be;
    /** 实物 {@code EntityLiving.java:19} 是 {@code protected String texture = "/char.png";}：活物一构造出来就有纹理串。 */
    public String bg = "/char.png";
    protected int bh;

    public a(g var1) {
        super(var1);
        this.ba = 10;
        this.bb = 10;
        this.bc = 0.5F;
    }

    public void a_() {
        super.a_();
        this.bh++;
    }

    public final void b(int var1) {
        this.ba -= var1;
        if (this.ba <= 0) {
            this.u = true;
        }
    }

    private int bi() {
        return (int) this.bd + this.be;
    }
}
