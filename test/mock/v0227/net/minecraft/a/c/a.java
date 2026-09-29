package mock.v0227.net.minecraft.a.c;

import mock.v0227.net.minecraft.a.a.e;

/**
 * 仿 indev-20100227 的 EntityLiving：{@code net.minecraft.a.c.a}。
 * 注意实物这一档开始流行 {@code (World)} 与 {@code (World,int)} 两个构造器并存——
 * 后者是 0413/0414"构造器剥除案"的伏笔（只有 (World) 构造器的类才走得了标准反射生成）。
 */
public abstract class a extends b {
    public int ba;
    public int bb;
    public float bc;
    public String bg = "";
    protected int bh;

    public a(e var1) {
        super(var1);
        this.ba = 10;
        this.bb = 10;
    }

    public a(e var1, int var2) {
        super(var1);
        this.ba = var2;
        this.bb = var2;
    }

    public void a_() {
        super.a_();
        this.bh++;
    }
}
