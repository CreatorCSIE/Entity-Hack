package mock.v0413.net.minecraft.a.c;

import mock.v0413.net.minecraft.a.a.g;

/**
 * 仿 inf-20100413 的 Entity 基类：{@code net.minecraft.a.c.b}。
 * 字段顺序完全照实物 javap 转储（关键：prevPos 在前、pos 在中、motion 在后，与可读源
 * {@code Entity.java} 一致），死亡标志是 {@code u}，世界引用是 {@code public g d}，
 * 碰撞箱是 {@code public mock.v0413.net.minecraft.a.d.b r}。
 * onUpdate 保留实物里"下划线式"的名字 {@code b_}。
 */
public abstract class b {
    private boolean a;
    public boolean c;
    public g d;
    public double e;
    public double f;
    public double g;
    public double h;
    public double i;
    public double j;
    public double k;
    public double l;
    public double m;
    public float n;
    public float o;
    public float p;
    public float q;
    public mock.v0413.net.minecraft.a.d.b r;
    public boolean s;
    public boolean t;
    private boolean aa;
    public boolean u;
    public float v;
    public float w = 0.6F;
    public float x = 1.8F;
    public float y;
    public float z;
    protected boolean A;
    private float ab;
    public double B;
    public double C;
    public double D;
    public float E;
    public boolean F;
    protected java.util.Random G = new java.util.Random(7L);
    public int H;
    public int I;
    public int J;
    protected int K;
    public int L;
    public int M;
    private boolean ac;
    private boolean ad;
    public String N = "";

    public b(g var1) {
        this.d = var1;
        this.r = new mock.v0413.net.minecraft.a.d.b(0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D);
    }

    public void b_() {
        this.e = this.h;
        this.f = this.i;
        this.g = this.j;
        this.h += this.k;
        this.i += this.l;
        this.j += this.m;
        this.B = this.h;
        this.C = this.i;
        this.D = this.j;
        this.r = new mock.v0413.net.minecraft.a.d.b(this.h - (double) this.w, this.i, this.j - (double) this.w,
                this.h + (double) this.w, this.i + (double) this.x, this.j + (double) this.w);
        this.H++;
    }

    public final double b(b var1) {
        double var2 = this.h - var1.h;
        double var4 = this.i - var1.i;
        double var6 = this.j - var1.j;
        return Math.sqrt(var2 * var2 + var4 * var4 + var6 * var6);
    }

    public final boolean j() {
        return !this.u;
    }

    public String o() {
        return this.N;
    }

    protected void a(float var1, float var2) {
        this.w = var1;
        this.x = var2;
    }
}
