import java.io.*; import java.util.*; import java.util.zip.*;
public class OfBuild {
  public static void main(String[] a) throws Exception {
    ZipFile of = new ZipFile(a[0]); ZipFile van = new ZipFile(a[1]);
    Properties map = new Properties(); map.load(of.getInputStream(of.getEntry("patch2.cfg")));
    ZipOutputStream out = new ZipOutputStream(new FileOutputStream(a[2])); int n=0, bad=0;
    for (Enumeration<? extends ZipEntry> e = of.entries(); e.hasMoreElements();) {
      ZipEntry z = e.nextElement(); String name = z.getName(); byte[] data = null; String outName = null;
      if (name.startsWith("srg/") && name.endsWith(".class")) { data = of.getInputStream(z).readAllBytes(); outName = name.substring(4); }
      else if (name.startsWith("patch/srg/") && name.endsWith(".class.xdelta")) {
        String key = name.substring(6, name.length() - 7); String base = map.getProperty(key);
        if (base == null) { System.out.println("no base for " + key); bad++; continue; }
        ZipEntry be = van.getEntry(base); if (be == null) { System.out.println("missing base " + base); bad++; continue; }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        new optifine.xdelta.GDiffPatcher(van.getInputStream(be).readAllBytes(), of.getInputStream(z), bo);
        data = bo.toByteArray(); outName = key.substring(4);
      }
      if (data != null) { out.putNextEntry(new ZipEntry(outName)); out.write(data); out.closeEntry(); n++; }
    }
    out.close(); System.out.println("classes " + n + " problems " + bad);
  }
}
