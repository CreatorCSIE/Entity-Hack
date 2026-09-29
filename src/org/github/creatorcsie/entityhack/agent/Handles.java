package org.github.creatorcsie.entityhack.agent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * 定位结果：操控一个具体版本所需的全部反射句柄 + 该版本的能力清单。
 * 字母全部由 {@link GameBinder} 现场定界（不写死混淆名），因此同一个 jar 换版只换字母、不改代码。
 */
public final class Handles {

    public Object minecraft;
    public Class<?> minecraftClass;
    /**
     * 当前世界实例。<b>volatile</b>：换关卡时 {@link WorldWatch} 会在刷新线程上把它换成新 World，
     * 而界面线程每轮都读它去扫登记表——不 volatile 的话 EDT 可能永远看不见新值。
     */
    public volatile Object world;
    public Class<?> worldClass;
    public Class<?> entityBase;

    /** World 上类型为实体基类的字段（playerEntity；0413 实测 {@code public a.c.b d}）。 */
    public Field playerField;
    /** 实体上类型为 World 的字段（0223/0227/0413 实测俱在；这是"从任意实体反抓 World"的通路）。 */
    public Field worldField;

    /** 生成口：World 上"调用后实体会出现在登记表里"的那个单实体参数方法。 */
    public Method spawnMethod;
    /** 立即摘除口：World 上"调用后实体马上从登记表里消失"的方法（0223 的 releaseEntitySkin）。 */
    public Method hardRemoveMethod;
    /** 置死口：World 上"调用后实体某个 boolean 字段翻成 true"的方法（0316/0413 的 static setEntityDead）。 */
    public Method deadMarkMethod;
    /** 死亡标志字段（由置死口的效果反推出来，不需要知道它叫 u 还是 isDead）。 */
    public Field deadFlag;

    public Class<?> accessInterface;
    public Method addAccessMethod;
    /** true = 拿到了游戏线程（TickPump 的代理确实被 World 每 tick 回调）。 */
    public volatile boolean pumpRunning;

    /** 实体基类上的数值游程：[0]=pos/prev 之一，[1]=……，按声明顺序；写入位置时会把 pos 之前的游程一起写。 */
    public Field[] posTriple;
    public Field[] prevTriple;
    public Field[] motionTriple;
    public Field aabbField;

    public Class<?> entityListClass;
    public Field stringToClassMap;
    public Field classToStringMap;
    public Method addMappingMethod;
    public Method createFromNbtMethod;
    public Method getEntityStringMethod;

    /** 每 tick 泵（World 上遍历实体登记表、调 onUpdate、摘除死亡实体的方法）是否存在的判定结果。 */
    public boolean pumpVerified;

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("World=").append(worldClass == null ? "?" : worldClass.getName()).append('\n');
        sb.append("Entity=").append(entityBase == null ? "?" : entityBase.getName()).append('\n');
        sb.append("spawn=").append(desc(spawnMethod)).append("  hardRemove=").append(desc(hardRemoveMethod)).append('\n');
        sb.append("deadMark=").append(desc(deadMarkMethod)).append("  deadFlag=").append(desc(deadFlag)).append('\n');
        sb.append("pos=").append(fieldsToString(posTriple)).append(" prev=").append(fieldsToString(prevTriple))
                .append(" motion=").append(fieldsToString(motionTriple)).append('\n');
        sb.append("access=").append(accessInterface == null ? "none" : accessInterface.getName())
                .append("  pump=").append(pumpRunning ? "game-thread" : "off-thread").append('\n');
        sb.append("EntityList=").append(entityListClass == null ? "none" : entityListClass.getName())
                .append("  addMapping=").append(desc(addMappingMethod))
                .append("  getEntityString=").append(desc(getEntityStringMethod)).append('\n');
        return sb.toString();
    }

    private static String desc(Object o) {
        return o == null ? "none" : String.valueOf(o);
    }

    private static String fieldsToString(Field[] fs) {
        if (fs == null) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fs.length; i++) {
            sb.append(i > 0 ? "," : "").append(fs[i].getName()).append(':').append(fs[i].getType().getSimpleName());
        }
        return sb.toString();
    }

    /** 当前世界的实体登记表（List 字段 + EntityMap 的网格与主列表）；每次重新取，避免缓存过期。 */
    public List<Object> registries() {
        List<Object> out = new ArrayList<Object>();
        if (world != null) {
            GameShape.collectRegistries(world, out);
        }
        return out;
    }

    public boolean contains(Object entity) {
        List<Object> regs = registries();
        for (int i = 0; i < regs.size(); i++) {
            Object r = regs.get(i);
            if (r instanceof List && ((List<?>) r).contains(entity)) {
                return true;
            }
        }
        return false;
    }

    /** 登记表里的全部实体（按引用去重、保持插入顺序）。 */
    public List<Object> allEntities() {
        List<Object> out = new ArrayList<Object>();
        List<Object> regs = registries();
        for (int i = 0; i < regs.size(); i++) {
            Object r = regs.get(i);
            if (!(r instanceof List)) {
                continue;
            }
            List<?> list = (List<?>) r;
            try {
                for (int j = 0; j < list.size(); j++) {
                    Object e = list.get(j);
                    if (e != null && GameShape.isEntityLike(e.getClass()) && notIdentity(out, e)) {
                        out.add(e);
                    }
                }
            } catch (Throwable concurrentWrite) {
                // 游戏线程正在往这张表里加/删实体（下标读法撞上收缩会抛越界）：
                // 这一张读到哪算哪，下一轮刷新（1.2 秒后）再来一遍，不能让窗口整个刷不出来
            }
        }
        return out;
    }

    /** 从所有登记表里摘掉某个实体（0227/0313 死壳期、以及探针善后用）。 */
    public int removeFromRegistries(Object entity) {
        int n = 0;
        List<Object> regs = registries();
        for (int i = 0; i < regs.size(); i++) {
            Object r = regs.get(i);
            if (r instanceof List && ((List<?>) r).remove(entity)) {
                n++;
            }
        }
        return n;
    }

    /**
     * 死壳期（0227/0313）的"添加"：没有任何生成 API，只能按 EntityMap 的索引公式直接写格子
     * （可读源 EntityMap.java：索引 = {@code (z*depth + y)*width + x}，尺寸被构造器硬写成 32）。
     * 有格子就写格子；普通 List（0223 主列表形态）就 add 进去。
     */
    public int addToRegistries(Object entity) {
        double[] pos = new double[] { 0, 0, 0 };
        if (posTriple != null) {
            pos[0] = GameBinder.readNumber(posTriple[0], entity);
            pos[1] = GameBinder.readNumber(posTriple[1], entity);
            pos[2] = GameBinder.readNumber(posTriple[2], entity);
        }
        return addInto(entity, pos, world);
    }

    @SuppressWarnings("unchecked")
    private int addInto(Object entity, double[] pos, Object owner) {
        int n = 0;
        for (Field f : GameShape.allFields(owner.getClass())) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            Object v = GameShape.read(f, owner);
            if (v == null || v == entity) {
                continue;
            }
            if (v instanceof List[]) {
                n += writeGrid((List[]) v, entity, pos, owner);
            } else if (v instanceof List) {
                List list = (List) v;
                if (looksLikeEntityList(list)) {
                    n += list.add(entity) ? 1 : 0;
                }
            } else if (!v.getClass().isPrimitive() && GameShape.declaresListArray(v.getClass())) {
                n += addInto(entity, pos, v); // EntityMap：钻进去
            }
        }
        return n;
    }

    private boolean looksLikeEntityList(List<?> list) {
        if (entityBase == null) {
            return false;
        }
        for (int i = 0; i < list.size(); i++) {
            Object o = list.get(i);
            if (o != null) {
                return entityBase.isAssignableFrom(o.getClass());
            }
        }
        return true; // 空表：认作实体表（0227 的格子里就有空 List）
    }

    /**
     * 写坐标的<b>完整</b>动作。真机 0223 上"改完坐标会反弹"的根因不在格子，在碰撞盒：
     * 可读源 {@code Entity.moveEntity()} 每 tick 末尾都把坐标从 {@code boundingBox} 反推出来
     * （{@code Entity.java:304-306}：{@code this.posX = (this.boundingBox.minX + this.boundingBox.maxX) / 2.0F}），
     * 所以只写 {@code posX/posY/posZ} 写进去的是一个"下一 tick 就被盒子覆盖掉"的临时值——
     * 界面回读时看到的就是旧坐标，用户眼里就是"弹回去了"。
     *
     * <p>这里做三件事：① pos/prevPos 写新值；② 碰撞盒按同样的增量平移过去（优先走游戏自己的
     * {@code offset}，认不出来就直接写六元组）；③ 已经入场的实体再搬一次 EntityMap 格子
     * ——{@code RenderGlobal.renderEntities} 只遍历格子，不搬格子就等于传送后画不出来。
     *
     * @param moveGrid 实体已在登记表里才搬格子；生成路径传 false（{@code EntityMap.insert} 会按当前坐标入格，
     *                 我们先搬就会在格子里留下重复项）
     * @return 人类可读的落地说明（状态区和日志用）
     */
    public String applyPosition(Object entity, double x, double y, double z, boolean moveGrid) {
        double[] from = GameBinder.readTriple(posTriple, entity);
        GameBinder.writeTriple(posTriple, entity, x, y, z);
        GameBinder.writeTriple(prevTriple, entity, x, y, z);
        String how = "坐标 " + fieldList(posTriple) + "/" + fieldList(prevTriple) + " 已写，"
                + shiftAabb(entity, x - from[0], y - from[1], z - from[2]);
        if (!moveGrid) {
            return how + "（还没入场，格子交给 insert）";
        }
        Object map = world == null ? null : entityMapHolder();
        if (map == null) {
            // 0413 之后实体是一张平表，压根没有格子；这里要说"没有"，别报成"搬了 0 处（? -> ?）"
            return how + "；本版本没有 EntityMap 格子，不用搬";
        }
        int cells;
        try {
            cells = moveCells(entity, from, new double[] { x, y, z }, world);
        } catch (Throwable t) {
            return how + "；搬格子失败 " + t;
        }
        return how + "；搬格子 " + cells + " 处（" + cellOf(map, from) + " -> "
                + cellOf(map, new double[] { x, y, z }) + "）";
    }

    /** 就地平移实体碰撞箱；返回说明文字（找不到就照实说，别假装写成功了）。 */
    private String shiftAabb(Object entity, double dx, double dy, double dz) {
        if (aabbField == null) {
            return "碰撞盒=没有这个字段";
        }
        if (dx == 0.0D && dy == 0.0D && dz == 0.0D) {
            return "碰撞盒=增量 0";
        }
        Object box = GameShape.read(aabbField, entity);
        if (box == null) {
            return "碰撞盒=当前是 null";
        }
        Method offset = GameShape.findAabbOffset(box.getClass());
        if (offset != null) {
            try {
                offset.setAccessible(true);
                Class<?> p = offset.getParameterTypes()[0];
                offset.invoke(box, new Object[] { boxNumber(p, dx), boxNumber(p, dy), boxNumber(p, dz) });
                return "碰撞盒=走 " + box.getClass().getSimpleName() + "." + offset.getName() + "()";
            } catch (Throwable t) {
                AgentMain.log("aabb offset() failed, fall back to fields: " + t);
            }
        }
        Field[] corners = GameShape.aabbCorners(box.getClass());
        if (corners.length != 6) {
            return "碰撞盒=认不出六元组（可能仍会被拽回原位）";
        }
        int n = 0;
        for (int i = 0; i < 6; i++) {
            double delta = i % 3 == 0 ? dx : (i % 3 == 1 ? dy : dz);
            if (addNumber(corners[i], box, delta)) {
                n++;
            }
        }
        return n == 6 ? "碰撞盒=直写六元组 " + fieldList(corners) : "碰撞盒=只写进 " + n + "/6 个角（可能被拽回）";
    }

    private static Object boxNumber(Class<?> t, double v) {
        if (t == float.class) {
            return Float.valueOf((float) v);
        }
        if (t == double.class) {
            return Double.valueOf(v);
        }
        if (t == int.class) {
            return Integer.valueOf((int) v);
        }
        throw new IllegalArgumentException("not a numeric box axis type: " + t.getName());
    }

    private static boolean addNumber(Field f, Object owner, double delta) {
        try {
            f.setAccessible(true);
            Class<?> t = f.getType();
            if (t == float.class) {
                f.setFloat(owner, f.getFloat(owner) + (float) delta);
                return true;
            }
            if (t == double.class) {
                f.setDouble(owner, f.getDouble(owner) + delta);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 沿 World -> EntityMap 钻一遍，把实体从旧坐标的格子搬到新坐标的格子。 */
    private int moveCells(Object entity, double[] from, double[] to, Object owner) {
        int n = 0;
        for (Field f : GameShape.allFields(owner.getClass())) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            Object v = GameShape.read(f, owner);
            if (v == null || v == entity || v == owner) {
                continue;
            }
            if (v instanceof List[]) {
                n += shiftCells((List[]) v, entity, from, to, owner);
            } else if (!v.getClass().isPrimitive() && GameShape.declaresListArray(v.getClass())) {
                n += moveCells(entity, from, to, v); // EntityMap：钻进去
            }
        }
        return n;
    }

    /** 同一个实体在一张 {@code List[]} 网格里搬家：旧格子摘掉、新格子塞进去。 */
    @SuppressWarnings("unchecked")
    private int shiftCells(List[] grid, Object entity, double[] from, double[] to, Object gridOwner) {
        int[] dims = gridDims(gridOwner);
        int div = cellDivisor(to, dims);
        int fromIdx = cellIndex(from, dims, div, grid.length);
        int toIdx = cellIndex(to, dims, div, grid.length);
        if (toIdx < 0) {
            return 0;
        }
        int n = 0;
        if (fromIdx >= 0 && fromIdx != toIdx && grid[fromIdx] != null && grid[fromIdx].remove(entity)) {
            n++;
        }
        List cell = grid[toIdx];
        if (cell != null && !cell.contains(entity) && cell.add(entity)) {
            n++;
        }
        return n;
    }

    /** 说明文字用的格子坐标。 */
    private String cellOf(Object map, double[] pos) {
        if (map == null || pos == null) {
            return "?";
        }
        int[] dims = gridDims(map);
        int div = cellDivisor(pos, dims);
        return ((int) (pos[0] / div)) + "," + ((int) (pos[1] / div)) + "," + ((int) (pos[2] / div));
    }

    /** 装着 {@code List[]} 格子的那个对象（实物是 World.entityMap；认不出来就没有格子可搬）。 */
    private Object entityMapHolder() {
        for (Field f : GameShape.allFields(world.getClass())) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            Object v = GameShape.read(f, world);
            if (v != null && v != world && !v.getClass().isPrimitive() && GameShape.declaresListArray(v.getClass())) {
                return v;
            }
        }
        return null;
    }

    private static String fieldList(Field[] fs) {
        if (fs == null || fs.length == 0) {
            return "?";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fs.length; i++) {
            sb.append(i > 0 ? "/" : "").append(fs[i].getName());
        }
        return sb.toString();
    }

    /**
     * 按 {@code (z*depth + y)*width + x} 写格子（0223/0227 可读源逐行核对过的公式：
     * {@code entityGrid[(height * this.depth + depth) * this.width + width]}，
     * 其中格子坐标是 {@code (int)(pos / 8.0F)}）。
     * 尺寸取 owner 上声明顺序最靠前的三个正 int 字段（实物即 width/depth/height），
     * 格子边长（8）不写死：取"能让三个格子坐标都落进 [0,尺寸) 的最小档"。
     */
    @SuppressWarnings("unchecked")
    private int writeGrid(List[] grid, Object entity, double[] pos, Object gridOwner) {
        int[] dims = gridDims(gridOwner);
        int index = cellIndex(pos, dims, cellDivisor(pos, dims), grid.length);
        if (index < 0) {
            return 0;
        }
        List cell = grid[index];
        if (cell == null) {
            cell = new ArrayList();
            grid[index] = cell;
        }
        return cell.contains(entity) ? 0 : (cell.add(entity) ? 1 : 0);
    }

    /**
     * 格子索引。照可读源 {@code EntityMapSlot.init(pos)}：先 {@code (int)(pos / 8.0F)}，
     * 再<b>夹紧</b>到 {@code [0, dim-1]}（越界不是丢弃，而是落到最边上那一格——
     * {@code EntityMapSlot.java:19-41} 就是这么写的），最后按 {@code (z*depth + y)*width + x} 取号。
     *
     * @return 越出数组本身才是真失败（返回 -1）
     */
    private int cellIndex(double[] pos, int[] dims, int div, int gridLength) {
        int x = clampCell((int) (pos[0] / div), dims[0]);
        int y = clampCell((int) (pos[1] / div), dims[1]);
        int z = clampCell((int) (pos[2] / div), dims[2]);
        int index = (z * dims[1] + y) * dims[0] + x;
        return index >= 0 && index < gridLength ? index : -1;
    }

    private static int clampCell(int cell, int dim) {
        if (cell < 0) {
            return 0;
        }
        return cell >= dim ? dim - 1 : cell;
    }

    /** 格子边长推断：老 Indev/Infdev 的实体网格是"世界尺寸 / 8"，实测 8；这里按坐标反推。 */
    private int cellDivisor(double[] pos, int[] dims) {
        int[] options = new int[] { 8, 1, 2, 4, 16, 32 };
        for (int i = 0; i < options.length; i++) {
            int d = options[i];
            if (fits(pos[0] / d, dims[0]) && fits(pos[1] / d, dims[1]) && fits(pos[2] / d, dims[2])) {
                return d;
            }
        }
        return 8;
    }

    private static boolean fits(double cell, int dim) {
        int c = (int) cell;
        return c >= 0 && c < dim;
    }

    /** 尺寸 = owner 上声明顺序最靠前的三个正 int 字段（实物 EntityMap: width, depth, height）。 */
    private int[] gridDims(Object owner) {
        int[] out = new int[] { 32, 32, 32 };
        int i = 0;
        for (Field f : GameShape.allFields(owner.getClass())) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) {
                continue;
            }
            int v = (int) GameBinder.readNumber(f, owner);
            if (v > 0 && v <= 4096) {
                out[Math.min(i, 2)] = v;
                i++;
                if (i >= 3) {
                    break;
                }
            }
        }
        return out;
    }

    private static boolean notIdentity(List<Object> list, Object o) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == o) {
                return false;
            }
        }
        return true;
    }
}
