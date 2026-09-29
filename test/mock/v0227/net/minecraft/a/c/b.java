package mock.v0227.net.minecraft.a.c;

import mock.v0227.net.minecraft.a.a.e;

/**
 * 仿 indev-20100227 的 Entity 基类：{@code net.minecraft.a.c.b}。
 * 这一档坐标已经改成 double，但 <b>没有任何增删 API</b>（死壳期），
 * 所以本类只保留 onUpdate 与死亡标志，供"直写格子"那条退化路径使用。
 */
public abstract class b {
    public boolean a;
    public e d;
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
    public mock.v0227.net.minecraft.a.d.b r;
    public boolean u;
    public float w = 0.6F;
    public float x = 1.8F;
    public int B;

    public b(e var1) {
        this.d = var1;
        this.r = new mock.v0227.net.minecraft.a.d.b(0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D);
    }

    public void a_() {
        this.e = this.h;
        this.f = this.i;
        this.g = this.j;
        this.h += this.k;
        this.i += this.l;
        this.j += this.m;
        this.B++;
    }

    public String o() {
        return "";
    }
}
