package mock.v0227.net.minecraft.a.d;

/** 仿 Infdev 20100227 的 AxisAlignedBB：{@code net.minecraft.a.d.b}，6 个 double。 */
public final class b {
    public double a;
    public double b;
    public double c;
    public double d;
    public double e;
    public double f;

    public b(double var1, double var2, double var3, double var4, double var5, double var6) {
        this.a = var1;
        this.b = var2;
        this.c = var3;
        this.d = var4;
        this.e = var5;
        this.f = var6;
    }

    public final b a(double var1, double var2, double var3) {
        return new b(this.a + var1, this.b + var2, this.c + var3, this.d + var1, this.e + var2, this.f + var3);
    }
}
