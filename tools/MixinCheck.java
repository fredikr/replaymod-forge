import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.io.InputStream;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Static check: for every injector in the mixin classes of a jar, verify that its target method and
 * injection point exist in the given (Forge patched) Minecraft jar.
 * Usage: java -cp asm.jar;asm-tree.jar MixinCheck.java <mod-jar> <minecraft-jar> [<more class jars>...]
 */
public class MixinCheck {
    static final Map<String, ClassNode> mc = new HashMap<>();

    public static void main(String[] args) throws Exception {
        for (int i = 1; i < args.length; i++) load(args[i], mc, null);
        Map<String, ClassNode> mod = new HashMap<>();
        load(args[0], mod, null);

        int checked = 0, problems = 0;
        List<String> names = new ArrayList<>(mod.keySet());
        Collections.sort(names);
        for (String name : names) {
            ClassNode cn = mod.get(name);
            AnnotationNode mixin = find(cn.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;");
            if (mixin == null) continue;
            List<String> targets = new ArrayList<>();
            Object value = get(mixin, "value");
            if (value instanceof List<?> l) for (Object o : l) targets.add(((Type) o).getInternalName());
            Object tgts = get(mixin, "targets");
            if (tgts instanceof List<?> l) for (Object o : l) targets.add(((String) o).replace('.', '/'));

            for (MethodNode m : cn.methods) {
                List<AnnotationNode> anns = new ArrayList<>();
                if (m.visibleAnnotations != null) anns.addAll(m.visibleAnnotations);
                if (m.invisibleAnnotations != null) anns.addAll(m.invisibleAnnotations);
                for (AnnotationNode a : anns) {
                    String kind = a.desc.substring(a.desc.lastIndexOf('/') + 1, a.desc.length() - 1);
                    if (!Set.of("Inject", "Redirect", "ModifyArg", "ModifyArgs", "ModifyVariable", "ModifyConstant",
                            "ModifyExpressionValue", "ModifyReturnValue", "WrapWithCondition", "WrapOperation",
                            "ModifyReceiver", "WrapMethod").contains(kind)) continue;
                    checked++;
                    Object req = get(a, "require");
                    String where = name.replace('/', '.') + "#" + m.name + " @" + kind
                            + (req != null ? " (require=" + req + ")" : "");
                    List<String> selectors = strings(get(a, "method"));
                    List<AnnotationNode> ats = new ArrayList<>();
                    Object at = get(a, "at");
                    if (at instanceof AnnotationNode an) ats.add(an);
                    else if (at instanceof List<?> l) for (Object o : l) ats.add((AnnotationNode) o);

                    String result = check(targets, selectors, ats, kind);
                    if (result != null) {
                        problems++;
                        System.out.println("MISS " + where + "\n     " + result);
                    }
                }
            }
        }
        System.out.println("checked " + checked + " injectors, " + problems + " problems");
    }

    static String check(List<String> targets, List<String> selectors, List<AnnotationNode> ats, String kind) {
        int methodsFound = 0, points = 0;
        StringBuilder detail = new StringBuilder();
        for (String t : targets) {
            ClassNode target = mc.get(t);
            if (target == null) return "target class not in game jar: " + t;
            for (String sel : selectors) {
                String s = sel;
                if (s.startsWith("L") && s.contains(";")) s = s.substring(s.indexOf(';') + 1);
                String mname = s, mdesc = null;
                if (s.contains("(")) { mname = s.substring(0, s.indexOf('(')); mdesc = s.substring(s.indexOf('(')); }
                for (MethodNode tm : target.methods) {
                    if (!(tm.name.equals(mname) || mname.equals("*"))) continue;
                    if (mdesc != null && !tm.desc.equals(mdesc)) continue;
                    methodsFound++;
                    if (ats.isEmpty() || kind.equals("WrapMethod")) { points++; continue; }
                    for (AnnotationNode at : ats) {
                        int n = countPoints(tm, at);
                        if (n != 0) points += Math.max(n, 0);
                        else detail.append("no ").append(get(at, "value")).append(' ')
                                .append(Objects.toString(get(at, "target"), "")).append(" in ")
                                .append(t).append('.').append(tm.name).append(tm.desc).append("; ");
                    }
                }
            }
        }
        if (methodsFound == 0) return "no target method " + selectors + " in " + targets;
        if (points == 0) return detail.toString();
        return null;
    }

    /** Number of matching injection points, or -1 if the point type is not checked. */
    static int countPoints(MethodNode m, AnnotationNode at) {
        String value = (String) get(at, "value");
        String target = (String) get(at, "target");
        Integer ordinal = (Integer) get(at, "ordinal");
        int n = 0;
        switch (value) {
            case "HEAD", "RETURN", "TAIL": return 1;
            case "INVOKE", "INVOKE_ASSIGN", "INVOKE_STRING": {
                for (AbstractInsnNode insn : m.instructions) {
                    if (insn instanceof MethodInsnNode mi && matches(target, mi.owner, mi.name, mi.desc, "")) n++;
                }
                break;
            }
            case "FIELD": {
                for (AbstractInsnNode insn : m.instructions) {
                    if (insn instanceof FieldInsnNode fi && matches(target, fi.owner, fi.name, fi.desc, ":")) n++;
                }
                break;
            }
            case "NEW": {
                for (AbstractInsnNode insn : m.instructions) {
                    if (insn instanceof TypeInsnNode ti && ti.getOpcode() == 187) {
                        if (target == null || target.equals(ti.desc) || target.equals("L" + ti.desc + ";")
                                || target.startsWith("L" + ti.desc + ";")) n++;
                    }
                }
                break;
            }
            default: return -1;
        }
        if (ordinal != null && ordinal >= 0) return n > ordinal ? 1 : 0;
        return n;
    }

    static boolean matches(String target, String owner, String name, String desc, String sep) {
        if (target == null) return true;
        String t = target;
        String towner = null;
        if (t.startsWith("L") && t.contains(";")) {
            towner = t.substring(1, t.indexOf(';'));
            t = t.substring(t.indexOf(';') + 1);
        }
        String tname = t, tdesc = null;
        int i = sep.equals(":") ? t.indexOf(':') : t.indexOf('(');
        if (i >= 0) { tname = t.substring(0, i); tdesc = t.substring(sep.equals(":") ? i + 1 : i); }
        if (towner != null && !towner.equals(owner)) return false;
        if (!tname.equals(name)) return false;
        return tdesc == null || tdesc.equals(desc);
    }

    static List<String> strings(Object o) {
        List<String> r = new ArrayList<>();
        if (o instanceof String s) r.add(s);
        else if (o instanceof List<?> l) for (Object x : l) r.add((String) x);
        return r;
    }

    static AnnotationNode find(List<AnnotationNode> list, String desc) {
        if (list == null) return null;
        for (AnnotationNode a : list) if (a.desc.equals(desc)) return a;
        return null;
    }

    static Object get(AnnotationNode a, String key) {
        if (a.values == null) return null;
        for (int i = 0; i < a.values.size(); i += 2) if (a.values.get(i).equals(key)) return a.values.get(i + 1);
        return null;
    }

    static void load(String jar, Map<String, ClassNode> into, String prefix) throws Exception {
        try (ZipFile zf = new ZipFile(jar)) {
            for (ZipEntry e : Collections.list(zf.entries())) {
                if (!e.getName().endsWith(".class") || e.getName().startsWith("META-INF")) continue;
                try (InputStream in = zf.getInputStream(e)) {
                    ClassNode cn = new ClassNode();
                    new ClassReader(in).accept(cn, ClassReader.SKIP_FRAMES);
                    into.putIfAbsent(cn.name, cn);
                }
            }
        }
    }
}
