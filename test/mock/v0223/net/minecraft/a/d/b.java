package mock.v0223.net.minecraft.a.d;

/** 仿 Indev 的 AxisAlignedBB：{@code net.minecraft.a.d.b}——0223 时代它是 <b>float</b> 六个。 */
public final class b {
    public float a;
    public float b;
    public float c;
    public float d;
    public float e;
    public float f;

    public b(float var1, float var2, float var3, float var4, float var5, float var6) {
        this.a = var1;
        this.b = var2;
        this.c = var3;
        this.d = var4;
        this.e = var5;
        this.f = var6;
    }

    public final b a(float var1, float var2, float var3) {
        return new b(this.a + var1, this.b + var2, this.c + var3, this.d + var1, this.e + var2, this.f + var3);
    }

    public final double b() {
        return (double) ((this.d - this.a) * (this.f - this.c));
    }
}
