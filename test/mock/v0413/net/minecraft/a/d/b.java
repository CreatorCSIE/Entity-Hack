package mock.v0413.net.minecraft.a.d;

/** 仿 AxisAlignedBB：{@code net.minecraft.a.d.b}，6 个连续 double（AABB 形状判据的来源）。 */
public final class b {
    public double a;
    public double b;
    public double c;
    public double d;
    public double e;
    public double f;

    public b(double var1, double var3, double var5, double var7, double var9, double var11) {
        this.a = var1;
        this.b = var3;
        this.c = var5;
        this.d = var7;
        this.e = var9;
        this.f = var11;
    }

    public final b a(double var1, double var3, double var5) {
        return new b(this.a + var1, this.b + var3, this.c + var5, this.d + var1, this.e + var3, this.f + var5);
    }
}
