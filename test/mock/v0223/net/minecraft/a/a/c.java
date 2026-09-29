package mock.v0223.net.minecraft.a.a;

import mock.v0223.net.minecraft.a.c.b;

/** 仿 0223 挂在 {@code worldAccesses} 上的部件（渲染引擎/单人 in-world manager 那一类）。 */
public final class c implements d {
    public int obtained;
    public int released;
    public int loads;

    public void a() {
        this.loads++;
    }

    public void h() {
    }

    public void a(b var1) {
        this.obtained++;
    }

    public void b(b var1) {
        this.released++;
    }

    public void a(int var1, int var2, int var3) {
    }

    public void a(String var1, float var2, float var3, float var4, float var5, float var6) {
    }
}
