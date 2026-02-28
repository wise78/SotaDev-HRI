import java.lang.reflect.*;
import java.net.*;
import java.io.*;

public class JarInspector {
    public static void main(String[] args) throws Exception {
        String jarPath = args[0];
        URL[] urls = { new File(jarPath).toURI().toURL() };
        URLClassLoader cl = new URLClassLoader(urls);
        
        String[] classes = {
            "jp.pux.lib.PFAGRLibrary",
            "jp.pux.lib.PFAGRLibrary$PFAGR_ETH_RESULT",
            "jp.pux.lib.PFAGRLibrary$PFAGR_AGE_RESULT",
            "jp.pux.lib.PFAGRLibrary$PFAGR_GEN_RESULT",
            "jp.pux.lib.PFAGRLibrary$PFAGR_FACE_POSITION",
            "jp.pux.lib.PFAGRLibrary$AgeSexResult",
            "jp.vstone.camera.FaceDetectResult",
            "jp.vstone.camera.CRoboCamera"
        };
        
        for (String name : classes) {
            try {
                Class<?> c = cl.loadClass(name);
                System.out.println("\n=== " + name + " ===");
                System.out.println("  Type: " + (c.isInterface() ? "interface" : "class"));
                if (c.getSuperclass() != null)
                    System.out.println("  Extends: " + c.getSuperclass().getName());
                for (Class<?> iface : c.getInterfaces())
                    System.out.println("  Implements: " + iface.getName());
                for (Field f : c.getDeclaredFields())
                    System.out.println("  Field: " + Modifier.toString(f.getModifiers()) + " " + f.getType().getSimpleName() + " " + f.getName());
                for (Method m : c.getDeclaredMethods())
                    System.out.println("  Method: " + Modifier.toString(m.getModifiers()) + " " + m.getReturnType().getSimpleName() + " " + m.getName() + "(" + paramTypes(m) + ")");
                for (Constructor<?> con : c.getDeclaredConstructors())
                    System.out.println("  Constructor: " + con.getName() + "(" + conParamTypes(con) + ")");
            } catch (Exception e) {
                System.out.println("\n=== " + name + " === ERROR: " + e.getMessage());
            }
        }
    }
    
    static String paramTypes(Method m) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> p : m.getParameterTypes()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.getSimpleName());
        }
        return sb.toString();
    }
    
    static String conParamTypes(Constructor<?> c) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> p : c.getParameterTypes()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.getSimpleName());
        }
        return sb.toString();
    }
}
