package mock.v0413.net.minecraft.a.a;

/**
 * 仿 0413 里挂在 {@code worldAccesses} 上的部件（渲染引擎那类）。
 * 存在的意义只有一个：让世界在注入前就有一个非空的部件表，
 * 并且 {@link mock.v0413.net.minecraft.a.a.a}（IWorldAccess）的实现者真实存在。
 */
public final class b implements a {
    public int sounds;
    public int particles;
    public int updates;

    public void a(int var1, int var2, int var3) {
        this.updates++;
    }

    public void a(int var1, int var2, int var3, int var4, int var5, int var6) {
        this.updates++;
    }

    public void a(String var1, double var2, double var4, double var6, float var8, float var9) {
        this.sounds++;
    }

    public void a(String var1, double var2, double var4, double var6, double var8, double var10, double var12) {
        this.particles++;
    }

    /** = updateAllRenderers()：实物只在 skylightSubtracted 变化的那一 tick 才被调。 */
    public void d() {
        this.updates += 100;
    }
}
