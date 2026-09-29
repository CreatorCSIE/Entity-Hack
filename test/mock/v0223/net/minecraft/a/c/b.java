package mock.v0223.net.minecraft.a.c;

import mock.v0223.net.minecraft.a.a.g;

/**
 * 仿 indev-20100223 的 Entity 基类：{@code net.minecraft.a.c.b}——Indev 这一档坐标是 <b>float</b>，
 * 所以三元组是 3 连 float（形状判据按"同型连续游程"走，天然覆盖 double/float 两种尺寸）。
 * onUpdate 在这个年代叫 {@code a_()}。
 */
public abstract class b {
    public boolean a;
    public boolean c;
    public g d;
    public float e;
    public float f;
    public float g;
    public float h;
    public float i;
    public float j;
    public float k;
    public float l;
    public float m;
    public float n;
    public float o;
    public mock.v0223.net.minecraft.a.d.b r;
    public boolean u;
    public float w = 0.6F;
    public float x = 1.8F;
    public float p;
    public float q;
    public String N = "";
    public int B;

    public b(g var1) {
        this.d = var1;
        // 实物 Entity(World) 第 70 行就是 {@code this.setPosition(0,0,0)}：盒子按 width/height 跟着坐标
        // 一起建出来，之后 moveEntity 只搬盒子、再反过来从盒子推坐标——所以盒子是<b>账本</b>，坐标是<b>读数</b>。
        this.a(0.0F, 0.0F, 0.0F);
    }

    /**
     * = setPosition（可读源 {@code Entity.java:100-107}）：写坐标<b>并且</b>按 width/height 重建碰撞盒。
     * 游戏里所有"放置实体"的路径（构造器、读档、出生点）都走这里，所以实物从来不会出现
     * "坐标在 64、盒子还在原点"这种状态——只有外挂直接写 h/i/j 才会。
     * （实物 {@code posY = boundingBox.minY + yOffset - ySize}，这里 yOffset/ySize 互相抵消，
     * 于是盒子的 minY 就直接是 posY，反推关系和 {@link #a_()} 保持一致。）
     */
    protected final void a(float var1, float var2, float var3) {
        this.h = var1;
        this.i = var2;
        this.j = var3;
        float var4 = this.w / 2.0F;
        this.r = new mock.v0223.net.minecraft.a.d.b(var1 - var4, var2, var3 - var4, var1 + var4,
                var2 + this.x, var3 + var4);
    }

    /**
     * = setPositionAndRotation（可读源 {@code Entity.java:423-430}）：读档／设出生点用的那一个，
     * 除了搬坐标加盒子，还把 prevPos 一起对齐（否则下一帧插值会从旧位置开始拉）。
     */
    public final void a(float var1, float var2, float var3, float var4, float var5) {
        this.e = this.h = var1;
        this.f = this.i = var2;
        this.g = this.j = var3;
        this.n = var4;
        this.o = var5;
        this.a(var1, var2, var3);
    }

    public void a_() {
        this.e = this.h;
        this.f = this.i;
        this.g = this.j;
        // 仿可读源 Entity.moveEntity()：动量先作用在碰撞盒上，然后<b>坐标从盒子里反推</b>
        // （Entity.java:304-306 {@code this.posX = (this.boundingBox.minX + this.boundingBox.maxX) / 2.0F}）。
        // 这一段就是真机 0223"改完坐标会反弹"的那条根：只往 h/i/j 里写、不搬 r，
        // 下一 tick 读数就被旧盒子覆盖回去了。
        this.r = this.r.a(this.k, this.l, this.m);
        this.h = (this.r.a + this.r.d) / 2.0F;
        this.i = this.r.b;
        this.j = (this.r.c + this.r.f) / 2.0F;
        this.B++;
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
